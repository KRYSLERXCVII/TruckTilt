using HIDMaestro;

namespace TruckTilt.Host;

/// <summary>
/// TruckTilt custom DirectInput wheel.
/// Uses HIDMaestro's canonical DirectInput-style layout:
/// X/Y = stick pair, Z/Rz = trigger/pedal pair, plus Slider and Dial.
/// The canonical stick/trigger declarations are intentional: they make
/// HIDMaestro expose the axes through the same profile metadata used by its
/// DirectInput encoder instead of relying on a hand-declared axis list.
/// </summary>
public sealed class VirtualWheel : IDisposable
{
    private readonly HMContext _context;
    private readonly HMProfile _profile;
    private readonly HMController _controller;
    private readonly Dictionary<HMAxis, float> _axes;
    private readonly object _sync = new();
    private bool _disposed;

    public bool Ready { get; private set; }
    public HMProfile Profile => _profile;
    public const int ButtonCount = 33;

    public VirtualWheel()
    {
        _context = new HMContext();
        _context.LoadDefaultProfiles();
        _context.InstallDriver();

        // Canonical DirectInput axis layout:
        // X/Y      = main stick pair (we use X for steering)
        // Z        = left trigger slot -> throttle
        // Rz       = right trigger slot -> brake
        // Slider   = clutch
        // Dial     = retarder
        //
        // AddStick/AddTrigger also populate HIDMaestro's profile axis-role
        // metadata. That is important for the descriptor encoder and avoids
        // the frozen-axis behaviour seen with the earlier hand-declared X/Z/Rz
        // descriptor in ETS2.
        var descriptor = new HidDescriptorBuilder()
            .Joystick()
            .AddStick("Left", bits: 16)
            .AddTrigger("Left", bits: 16)
            .AddTrigger("Right", bits: 16)
            .AddTrigger("Left", bits: 16)   // 3rd trigger slot -> Slider
            .AddTrigger("Right", bits: 16)  // 4th trigger slot -> Dial
            .AddButtons(32)
            // Add button 33 with explicit HID padding so the descriptor exposes
            // exactly 33 buttons (rather than rounding the count to 40).
            .AddRaw(new byte[] {
                0x05, 0x09,             // Usage Page (Button)
                0x09, 0x21,             // Usage (Button 33)
                0x15, 0x00, 0x25, 0x01,// Logical range 0..1
                0x75, 0x01, 0x95, 0x01,// One 1-bit field
                0x81, 0x02,             // Input (Data,Var,Abs)
                0x75, 0x07, 0x95, 0x01,// Seven padding bits
                0x81, 0x01              // Input (Const)
            })
            .AddHat(positions: 8)
            .AddPidFfbBlock();

        _profile = new HMProfileBuilder()
            .Id("trucktilt-virtual-wheel-v0744")
            .Name("TruckTilt Virtual Steering Wheel")
            .Vendor("TruckTilt")
            .Vid(0xCAFE)
            .Pid(0x5455)
            .ProductString("TruckTilt Virtual Steering Wheel")
            .ManufacturerString("TruckTilt")
            .Type("wheel")
            .Connection("usb")
            .Descriptor(descriptor.Build())
            // AddRaw contributes one byte (button 33 + 7 padding bits) that
            // the builder's tracked bit count cannot see; include that byte
            // plus the Report ID inserted by AddPidFfbBlock.
            .InputReportSize(descriptor.InputReportByteSize + 2)
            .Notes("TruckTilt ETS2 wheel: X steering, Rx brake, Ry throttle, Slider clutch, Dial retarder, exactly 33 buttons, 8-way POV. Extra Slider/Dial axes are driven directly by HID usage.")
            .Build();

        HostLog.Debug($"Custom HID profile: {_profile.Name}");
        HostLog.Debug($"Type: {_profile.Type}  Axes: {_profile.AxisCount}  Buttons: {_profile.ButtonCount}");
        HostLog.Debug("Available axes: " + string.Join(", ", _profile.AvailableAxes));
        HostLog.Debug("Sticks: " + string.Join(", ", _profile.Sticks.Select(s => $"{s.XAxis}/{s.YAxis}")));
        HostLog.Debug("Triggers: " + string.Join(", ", _profile.Triggers.Select(t => t.Axis)));
        HostLog.Debug("Creating DirectInput/HID virtual wheel...");

        _controller = _context.CreateController(_profile, "TruckTilt-VirtualWheel");

        // HIDMaestro's AddStick/AddTrigger allocator is positional.
        // With one stick followed by two triggers, the descriptor is:
        //   X/Y       = steering stick pair
        //   Rx        = first trigger slot (our brake)
        //   Ry        = second trigger slot (our throttle)
        //   Slider    = third trigger slot (our clutch)
        //   Dial      = fourth trigger slot (our retarder)
        // The four AddTrigger calls are intentionally used instead of AddAxis
        // for Slider/Dial: HIDMaestro's trigger encoder resolves the complete
        // [Rx, Ry, Slider, Dial] trigger pool and writes each declared field.
        // This avoids the frozen Slider/Dial behaviour seen with AddAxis. The joy.cpl screenshot made that mismatch visible.
        _axes = HMGamepadStateHelpers.StandardAxes(
            _profile,
            leftStickX: 0.5f,
            leftStickY: 0.5f,
            leftTrigger: 0f,
            rightTrigger: 0f);

        var triggers = _profile.Triggers;
        if (triggers.Count > 0) _axes[triggers[0].Axis] = 0f;
        if (triggers.Count > 1) _axes[triggers[1].Axis] = 0f;
        if (triggers.Count > 2) _axes[triggers[2].Axis] = 0f;
        if (triggers.Count > 3) _axes[triggers[3].Axis] = 0f;

        try
        {
            _controller.OutputDecoded += OnOutputDecoded;
            _controller.PublishPidPool(
                ramPoolSize: 0xFFFF,
                simultaneousEffectsMax: 16,
                deviceManagedPool: true,
                sharedParameterBlocks: false);
            _controller.PublishPidState(
                effectBlockIndex: 0,
                PidStateFlags.ActuatorsEnabled | PidStateFlags.ActuatorPower);
            HostLog.Debug("PID force-feedback interface: READY");
        }
        catch (Exception ex)
        {
            HostLog.Debug("PID force-feedback setup skipped: " + ex.Message);
        }

        Ready = true;
        Submit(0, 0, 0, 0, 0, 0);
    }

    public void Submit(double steering, double throttle, double brake,
                       double clutch, double retarder, ulong buttonMask)
    {
        if (!Ready || _disposed) return;

        steering = Math.Clamp(steering, -1.0, 1.0);
        throttle = Math.Clamp(throttle, 0.0, 1.0);
        brake = Math.Clamp(brake, 0.0, 1.0);
        clutch = Math.Clamp(clutch, 0.0, 1.0);
        retarder = Math.Clamp(retarder, 0.0, 1.0);

        // HMButton is a 32-bit semantic enum. To expose button 33 on the SAME
        // DirectInput wheel, emit the descriptor's raw input report. This keeps
        // all 33 button bits independent without introducing another device.
        // Input layout: X, Y, Rx, Ry, Slider, Dial (16-bit little-endian),
        // 33 button bits (5 bytes including descriptor padding), then the hat.
        var report = new byte[18];
        WriteAxis(report, 0, (steering + 1.0) * 0.5);
        WriteAxis(report, 2, 0.5);       // Y centered
        WriteAxis(report, 4, brake);     // Rx
        WriteAxis(report, 6, throttle);  // Ry
        WriteAxis(report, 8, clutch);    // Slider
        WriteAxis(report, 10, retarder); // Dial
        for (int i = 0; i < ButtonCount; i++)
        {
            if ((buttonMask & (1UL << i)) != 0)
                report[12 + (i / 8)] |= (byte)(1 << (i % 8));
        }
        report[17] = 8; // Hat neutral position (8 = centered for this descriptor)

        lock (_sync)
            _controller.SubmitRawReport(report);
    }

    private static void WriteAxis(byte[] report, int offset, double value)
    {
        ushort raw = (ushort)Math.Round(Math.Clamp(value, 0.0, 1.0) * ushort.MaxValue);
        report[offset] = (byte)(raw & 0xFF);
        report[offset + 1] = (byte)(raw >> 8);
    }

    private void OnOutputDecoded(object? sender, HMOutputDecodedEventArgs e)
        => HostLog.Debug($"FFB OUTPUT received: {e}");

    public void Neutralize() => Submit(0, 0, 0, 0, 0, 0);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Ready = false;
        try { _controller.SubmitState(new HMGamepadState { Axes = _axes }); } catch { }
        try { _controller.Dispose(); } finally { _context.Dispose(); }
    }
}
