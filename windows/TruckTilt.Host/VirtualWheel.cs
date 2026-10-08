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
    public const int ButtonCount = 32;

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
            .AddButtons(ButtonCount)
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
            .FromDescriptorBuilder(descriptor)
            .Notes("TruckTilt ETS2 wheel: X steering, Rx brake, Ry throttle, Slider clutch, Dial retarder, 32 buttons, 8-way POV. Extra Slider/Dial axes are driven directly by HID usage.")
            .Build();

        Console.WriteLine($"Custom HID profile: {_profile.Name}");
        Console.WriteLine($"Type: {_profile.Type}  Axes: {_profile.AxisCount}  Buttons: {_profile.ButtonCount}");
        Console.WriteLine("Available axes: " + string.Join(", ", _profile.AvailableAxes));
        Console.WriteLine("Sticks: " + string.Join(", ", _profile.Sticks.Select(s => $"{s.XAxis}/{s.YAxis}")));
        Console.WriteLine("Triggers: " + string.Join(", ", _profile.Triggers.Select(t => t.Axis)));
        Console.WriteLine("Creating DirectInput/HID virtual wheel...");

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
            Console.WriteLine("PID force-feedback interface: READY");
        }
        catch (Exception ex)
        {
            Console.WriteLine("PID force-feedback setup skipped: " + ex.Message);
        }

        Ready = true;
        Submit(0, 0, 0, 0, 0, 0);
    }

    public void Submit(double steering, double throttle, double brake,
                       double clutch, double retarder, uint buttonMask)
    {
        if (!Ready || _disposed) return;

        steering = Math.Clamp(steering, -1.0, 1.0);
        throttle = Math.Clamp(throttle, 0.0, 1.0);
        brake = Math.Clamp(brake, 0.0, 1.0);
        clutch = Math.Clamp(clutch, 0.0, 1.0);
        retarder = Math.Clamp(retarder, 0.0, 1.0);

        // HIDMaestro uses [0..1] for all state values. Signed centered axes
        // use 0.5 as center, so steering -1..+1 becomes 0..1.
        _axes[HMAxis.X] = (float)((steering + 1.0) * 0.5);

        // HIDMaestro's public Profile.Triggers list intentionally exposes only
        // the semantic Left/Right trigger pair. Our descriptor contains two
        // additional trigger-slot fields (Slider and Dial), so those MUST be
        // addressed directly by their HID usages. The previous implementation
        // tried to access triggers[2]/[3], but that list only contains two
        // entries, leaving Slider and Dial permanently at their defaults.
        var triggers = _profile.Triggers;

        // DirectInput pedal order for TruckTilt:
        // first trigger slot (Rx)  = brake
        // second trigger slot (Ry) = throttle
        //
        // Keep the phone's progressive values unchanged; only swap which
        // physical HID axis receives them.
        if (triggers.Count > 0) _axes[triggers[0].Axis] = (float)brake;
        if (triggers.Count > 1) _axes[triggers[1].Axis] = (float)throttle;

        // Extra DirectInput pedal axes.
        _axes[HMAxis.Slider] = (float)clutch;
        _axes[HMAxis.Dial] = (float)retarder;

        var state = new HMGamepadState
        {
            Axes = _axes,
            Buttons = (HMButton)buttonMask,
        };

        lock (_sync)
            _controller.SubmitState(in state);
    }

    private void OnOutputDecoded(object? sender, HMOutputDecodedEventArgs e)
        => Console.WriteLine($"FFB OUTPUT received: {e}");

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
