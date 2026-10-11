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
        Console.WriteLine("Creating HIDMaestro context...");
        _context = new HMContext();

        Console.WriteLine("Loading HIDMaestro profiles...");
        _context.LoadDefaultProfiles();

        _profile = _context.GetProfile("xbox-360-wired")
            ?? throw new InvalidOperationException(
                "HIDMaestro Xbox 360 Wired profile is not available.");

        Console.WriteLine($"Using HIDMaestro profile: {_profile.Name}");

        Console.WriteLine("Checking/installing HIDMaestro driver...");
        _context.InstallDriver();

        Console.WriteLine("Creating virtual Xbox 360 controller...");

        _controller = _context.CreateController(
            _profile,
            "TruckTilt-Xbox360");

        Ready = true;

        Neutral();

        Console.WriteLine("Virtual controller created successfully.");
    }

    /// <summary>
    /// Sends TruckTilt input to the virtual Xbox 360 controller.
    ///
    /// Steering: -1 = left, 0 = center, +1 = right
    /// Throttle:  0 = released, 1 = full
    /// Brake:     0 = released, 1 = full
    /// </summary>
    public void Submit(
        double steering,
        double throttle,
        double brake,
        InputButtons buttons)
    {
        if (!Ready)
            return;

        steering = Math.Clamp(steering, -1.0, 1.0);
        throttle = Math.Clamp(throttle, 0.0, 1.0);
        brake = Math.Clamp(brake, 0.0, 1.0);

        // HIDMaestro normalizes centered axes to 0..1.
        // -1 -> 0, 0 -> 0.5, +1 -> 1.
        float steeringAxis = (float)((steering + 1.0) * 0.5);

        var axes = HMGamepadStateHelpers.StandardAxes(
            _profile,
            leftStickX: steeringAxis,
            leftStickY: 0.5f,
            rightStickX: 0.5f,
            rightStickY: 0.5f,
            leftTrigger: (float)brake,
            rightTrigger: (float)throttle
        );

        HMButton xboxButtons = HMButton.None;

        // ETS2/ATS-friendly layout:
        //
        // LB       = Left indicator
        // RB       = Right indicator
        // Y        = Hazard
        // A        = Horn
        // B        = Air horn
        // X        = Lights
        //
        // D-pad UP    = High beam
        // D-pad LEFT  = Wiper
        // D-pad DOWN  = Engine brake
        // D-pad RIGHT = Retarder
        //
        // BACK     = Parking brake
        //
        // Steering  = Left stick X
        // Brake     = LT
        // Throttle  = RT

        if (Has(buttons, InputButtons.Left))
            xboxButtons |= HMButton.LeftBumper;

        if (Has(buttons, InputButtons.Right))
            xboxButtons |= HMButton.RightBumper;

        if (Has(buttons, InputButtons.Hazard))
            xboxButtons |= HMButton.Y;

        if (Has(buttons, InputButtons.Horn))
            xboxButtons |= HMButton.A;

        if (Has(buttons, InputButtons.AirHorn))
            xboxButtons |= HMButton.B;

        if (Has(buttons, InputButtons.Lights))
            xboxButtons |= HMButton.X;

        if (Has(buttons, InputButtons.ParkingBrake))
            xboxButtons |= HMButton.Back;

        // D-pad controls.
        //
        // Xbox D-pad is a single hat. If multiple directional commands
        // are pressed simultaneously, we support diagonals as well.
        HMHat hat = BuildHat(buttons);

        var state = new HMGamepadState
        {
            Axes = axes,
            Buttons = xboxButtons,
            Hat = hat
        };

        lock (_sync)
        {
            _controller.SubmitState(in state);
        }
    }

    private static HMHat BuildHat(InputButtons buttons)
    {
        bool up = Has(buttons, InputButtons.HighBeam);
        bool left = Has(buttons, InputButtons.Wiper);
        bool down = Has(buttons, InputButtons.EngineBrake);
        bool right = Has(buttons, InputButtons.Retarder);

        if (up && right)
            return HMHat.NorthEast;

        if (up && left)
            return HMHat.NorthWest;

        if (down && right)
            return HMHat.SouthEast;

        if (down && left)
            return HMHat.SouthWest;

        if (up)
            return HMHat.North;

        if (right)
            return HMHat.East;

        if (down)
            return HMHat.South;

        if (left)
            return HMHat.West;

        return HMHat.None;
    }

    private static bool Has(
        InputButtons value,
        InputButtons flag)
    {
        return (value & flag) == flag;
    }

    /// <summary>
    /// Releases every axis, trigger, button and D-pad direction.
    /// </summary>
    public void Neutral()
    {
        if (!Ready && _controller == null)
            return;

        var axes = HMGamepadStateHelpers.StandardAxes(
            _profile,
            leftStickX: 0.5f,
            leftStickY: 0.5f,
            rightStickX: 0.5f,
            rightStickY: 0.5f,
            leftTrigger: 0.0f,
            rightTrigger: 0.0f
        );

        var state = new HMGamepadState
        {
            Axes = axes,
            Buttons = HMButton.None,
            Hat = HMHat.None
        };

        lock (_sync)
        {
            _controller.SubmitState(in state);
        }
    }

    public void Dispose()
    {
        if (!Ready)
            return;

        Ready = false;

        try
        {
            Neutral();
        }
        catch
        {
            // Ignore errors during shutdown.
        }

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
