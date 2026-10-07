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
            .AddAxis(HMAxis.Slider, bits: 16)
            .AddAxis(HMAxis.Dial, bits: 16)
            .AddButtons(ButtonCount)
            .AddHat(positions: 8)
            .AddPidFfbBlock();

        _profile = new HMProfileBuilder()
            .Id("trucktilt-virtual-wheel-v0742")
            .Name("TruckTilt Virtual Steering Wheel")
            .Vendor("TruckTilt")
            .Vid(0xCAFE)
            .Pid(0x5455)
            .ProductString("TruckTilt Virtual Steering Wheel")
            .ManufacturerString("TruckTilt")
            .Type("wheel")
            .Connection("usb")
            .FromDescriptorBuilder(descriptor)
            .Notes("TruckTilt ETS2 wheel: X steering, Rx throttle, Ry brake, Slider clutch, Dial retarder, 32 buttons, 8-way POV.")
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
        //   Rx        = first trigger slot (our throttle)
        //   Ry        = second trigger slot (our brake)
        //   Slider    = clutch
        //   Dial      = retarder
        // Do NOT write Z/Rz here: those usages are not declared by this
        // descriptor. The joy.cpl screenshot made that mismatch visible.
        _axes = HMGamepadStateHelpers.StandardAxes(
            _profile,
            leftStickX: 0.5f,
            leftStickY: 0.5f,
            leftTrigger: 0f,
            rightTrigger: 0f);

        var triggers = _profile.Triggers;
        if (triggers.Count > 0) _axes[triggers[0].Axis] = 0f;
        if (triggers.Count > 1) _axes[triggers[1].Axis] = 0f;
        _axes[HMAxis.Slider] = 0f;
        _axes[HMAxis.Dial] = 0f;

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

        // Use the actual descriptor-declared trigger usages.
        // AddTrigger("Left") allocates Rx here; AddTrigger("Right") allocates Ry.
        var triggers = _profile.Triggers;
        if (triggers.Count > 0) _axes[triggers[0].Axis] = (float)throttle;
        if (triggers.Count > 1) _axes[triggers[1].Axis] = (float)brake;

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
