using HIDMaestro;

namespace TruckTilt.Host;

/// <summary>
/// Creates a DirectInput-compatible virtual steering wheel rather than an Xbox controller.
/// The descriptor deliberately uses the classic Generic Desktop position axes
/// (X, Z, Rz, Slider, Dial) because ETS2 binds these legacy DirectInput axis
/// slots reliably. The device also exposes 32 independent buttons and an 8-way hat.
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

        // Use the classic DIJOYSTATE2 position-axis vocabulary.
        //
        // X      = steering (centered)
        // Z      = throttle (0..100%)
        // Rz     = brake (0..100%)
        // Slider = clutch (0..100%)
        // Dial   = retarder (0..100%)
        //
        // IMPORTANT: do not use HMAxis.Wheel or the Simulation Controls
        // Steering/Throttle/Brake usages for this ETS2 device. ETS2's axis
        // setup screen expects the conventional DirectInput X/Z/Rz/etc slots.
        var descriptor = new HidDescriptorBuilder()
            .Joystick()
            .AddAxis(HMAxis.X, bits: 16, logicalMin: -32768, logicalMax: 32767)
            .AddAxis(HMAxis.Z, bits: 16)
            .AddAxis(HMAxis.Rz, bits: 16)
            .AddAxis(HMAxis.Slider, bits: 16)
            .AddAxis(HMAxis.Dial, bits: 16)
            .AddButtons(ButtonCount)
            .AddHat(positions: 8)
            .AddPidFfbBlock();

        _profile = new HMProfileBuilder()
            .Id("trucktilt-virtual-wheel")
            .Name("TruckTilt Virtual Steering Wheel")
            .Vendor("TruckTilt")
            .Vid(0xCAFE)
            .Pid(0x5454)
            .ProductString("TruckTilt Virtual Steering Wheel")
            .ManufacturerString("TruckTilt")
            .Type("wheel")
            .Connection("usb")
            .FromDescriptorBuilder(descriptor)
            .Notes("TruckTilt custom DirectInput wheel: steering, throttle, brake, clutch, retarder and 32 buttons.")
            .Build();

        Console.WriteLine($"Custom HID profile: {_profile.Name}");
        Console.WriteLine($"Type: {_profile.Type}  Axes: {_profile.AxisCount}  Buttons: {_profile.ButtonCount}");
        Console.WriteLine("Creating DirectInput/HID virtual wheel...");

        _controller = _context.CreateController(_profile, "TruckTilt-VirtualWheel");

        // Prepare the exact descriptor-declared axes once. Values are normalized
        // [0..1]; the Wheel field itself is signed [-32768..32767].
        _axes = new Dictionary<HMAxis, float>
        {
            [HMAxis.X] = 0.5f,
            [HMAxis.Z] = 0f,
            [HMAxis.Rz] = 0f,
            [HMAxis.Slider] = 0f,
            [HMAxis.Dial] = 0f,
        };

        // Publish the PID pool so DirectInput can treat the device as an
        // FFB-capable wheel. We currently log incoming FFB/output requests;
        // phone haptics are a future transport feature.
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

    public void Submit(
        double steering,
        double throttle,
        double brake,
        double clutch,
        double retarder,
        uint buttonMask)
    {
        if (!Ready || _disposed) return;

        steering = Math.Clamp(steering, -1.0, 1.0);
        throttle = Math.Clamp(throttle, 0.0, 1.0);
        brake = Math.Clamp(brake, 0.0, 1.0);
        clutch = Math.Clamp(clutch, 0.0, 1.0);
        retarder = Math.Clamp(retarder, 0.0, 1.0);

        _axes[HMAxis.X] = (float)((steering + 1.0) * 0.5);
        _axes[HMAxis.Z] = (float)throttle;
        _axes[HMAxis.Rz] = (float)brake;
        _axes[HMAxis.Slider] = (float)clutch;
        _axes[HMAxis.Dial] = (float)retarder;

        var state = new HMGamepadState
        {
            Axes = _axes,
            Buttons = (HMButton)buttonMask,
        };

        lock (_sync)
        {
            _controller.SubmitState(in state);
        }
    }

    private void OnOutputDecoded(object? sender, HMOutputDecodedEventArgs e)
    {
        // Keep the event handler intentionally lightweight. DirectInput/ETS2
        // may send FFB commands at any time. This gives us a clean hook for a
        // future phone haptic/rumble packet without blocking the SDK reader.
        Console.WriteLine($"FFB OUTPUT received: {e}");
    }

    public void Neutralize() => Submit(0, 0, 0, 0, 0, 0);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Ready = false;
        try { Neutralize(); } catch { }
        try { _controller.Dispose(); } finally { _context.Dispose(); }
    }
}
