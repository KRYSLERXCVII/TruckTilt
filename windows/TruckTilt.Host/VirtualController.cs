using HIDMaestro;

namespace TruckTilt.Host;

public sealed class VirtualController : IDisposable
{
    private readonly HMContext _context;
    private readonly HMProfile _profile;
    private readonly HMController _controller;
    private readonly object _sync = new();

    public bool Ready { get; private set; }

    public VirtualController()
    {
        _context = new HMContext();
        _context.LoadDefaultProfiles();

        _profile = _context.GetProfile("xbox-360-wired")
            ?? throw new InvalidOperationException(
                "HIDMaestro Xbox 360 Wired profile is not available.");

        // Driver installation is idempotent; first launch requires elevation.
        _context.InstallDriver();

        _controller = _context.CreateController(
            _profile,
            "TruckTilt-Xbox360");

        Ready = true;
        Submit(0, 0, 0, InputButtons.None);
    }

    public void Submit(
        double steering,
        double throttle,
        double brake,
        InputButtons buttons)
    {
        if (!Ready) return;

        steering = Math.Clamp(steering, -1.0, 1.0);
        throttle = Math.Clamp(throttle, 0.0, 1.0);
        brake = Math.Clamp(brake, 0.0, 1.0);

        // HIDMaestro normalizes centered analog axes to [0..1].
        // X=0.0 is full left, 0.5 center, 1.0 full right.
        var axes = HMGamepadStateHelpers.StandardAxes(
            _profile,
            leftStickX: (float)((steering + 1.0) * 0.5),
            leftStickY: 0.5f,
            rightStickX: 0.5f,
            rightStickY: 0.5f,
            leftTrigger: (float)brake,
            rightTrigger: (float)throttle);

        var state = new HMGamepadState
        {
            Axes = axes,
            Buttons = ToButtons(buttons)
        };

        lock (_sync)
        {
            _controller.SubmitState(in state);
        }
    }

    private static HMButton ToButtons(InputButtons b)
    {
        HMButton result = HMButton.None;

        if (b.Left) result |= HMButton.A;
        if (b.Right) result |= HMButton.B;
        if (b.Lights) result |= HMButton.X;
        if (b.HighBeam) result |= HMButton.Y;
        if (b.Hazard) result |= HMButton.LeftBumper;
        if (b.Horn) result |= HMButton.RightBumper;
        if (b.AirHorn) result |= HMButton.Back;
        if (b.Wiper) result |= HMButton.Start;
        if (b.EngineBrake) result |= HMButton.LeftStick;
        if (b.Retarder) result |= HMButton.RightStick;
        if (b.ParkingBrake) result |= HMButton.Guide;

        return result;
    }

    public void Dispose()
    {
        Ready = false;
        try
        {
            _controller.Dispose();
        }
        finally
        {
            _context.Dispose();
        }
    }
}

[Flags]
public enum InputButtons
{
    None = 0,
    Left = 1 << 0,
    Right = 1 << 1,
    Lights = 1 << 2,
    HighBeam = 1 << 3,
    Hazard = 1 << 4,
    Horn = 1 << 5,
    AirHorn = 1 << 6,
    Wiper = 1 << 7,
    EngineBrake = 1 << 8,
    Retarder = 1 << 9,
    ParkingBrake = 1 << 10,
}
