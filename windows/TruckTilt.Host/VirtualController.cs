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

        Console.WriteLine(
            $"Using HIDMaestro profile: {_profile.Name}");

        // Driver installation is idempotent.
        // The application manifest requests Administrator privileges.
        Console.WriteLine("Checking/installing HIDMaestro driver...");

        _context.InstallDriver();

        Console.WriteLine("Creating virtual Xbox 360 controller...");

        _controller = _context.CreateController(
            _profile,
            "TruckTilt-Xbox360");

        Ready = true;

        // Start in a completely neutral state.
        Submit(
            0.0,
            0.0,
            0.0,
            InputButtons.None);

        Console.WriteLine("Virtual controller created successfully.");
    }

    /// <summary>
    /// Sends TruckTilt input to the virtual Xbox controller.
    ///
    /// Steering:
    ///   -1 = full left
    ///    0 = center
    ///   +1 = full right
    ///
    /// Throttle:
    ///    0 = released
    ///    1 = full throttle
    ///
    /// Brake:
    ///    0 = released
    ///    1 = full brake
    /// </summary>
    public void Submit(
        double steering,
        double throttle,
        double brake,
        InputButtons buttons)
    {
        if (!Ready)
            return;

        // Clamp all incoming values.
        steering = Math.Clamp(
            steering,
            -1.0,
            1.0);

        throttle = Math.Clamp(
            throttle,
            0.0,
            1.0);

        brake = Math.Clamp(
            brake,
            0.0,
            1.0);

        // HIDMaestro uses normalized 0..1 axis values.
        //
        // Steering:
        // -1.0 -> 0.0  (full left)
        //  0.0 -> 0.5  (center)
        // +1.0 -> 1.0  (full right)
        float steeringAxis =
            (float)((steering + 1.0) * 0.5);

        // Build Xbox-style analog axes.
        var axes = HMGamepadStateHelpers.StandardAxes(
            _profile,

            // Left stick X = TruckTilt steering.
            leftStickX: steeringAxis,

            // Left stick Y unused.
            leftStickY: 0.5f,

            // Right stick unused.
            rightStickX: 0.5f,
            rightStickY: 0.5f,

            // LT = brake.
            leftTrigger: (float)brake,

            // RT = throttle.
            rightTrigger: (float)throttle
        );

        // Convert TruckTilt buttons to Xbox buttons.
        HMButton xboxButtons = ToButtons(buttons);

        var state = new HMGamepadState
        {
            Axes = axes,
            Buttons = xboxButtons
        };

        lock (_sync)
        {
            _controller.SubmitState(in state);
        }
    }

    /// <summary>
    /// Converts TruckTilt button flags to HIDMaestro Xbox buttons.
    /// </summary>
    private static HMButton ToButtons(InputButtons buttons)
    {
        HMButton result = HMButton.None;

        if (Has(buttons, InputButtons.Left))
            result |= HMButton.A;

        if (Has(buttons, InputButtons.Right))
            result |= HMButton.B;

        if (Has(buttons, InputButtons.Lights))
            result |= HMButton.X;

        if (Has(buttons, InputButtons.HighBeam))
            result |= HMButton.Y;

        if (Has(buttons, InputButtons.Hazard))
            result |= HMButton.LeftBumper;

        if (Has(buttons, InputButtons.Horn))
            result |= HMButton.RightBumper;

        if (Has(buttons, InputButtons.AirHorn))
            result |= HMButton.Back;

        if (Has(buttons, InputButtons.Wiper))
            result |= HMButton.Start;

        if (Has(buttons, InputButtons.EngineBrake))
            result |= HMButton.LeftStick;

        if (Has(buttons, InputButtons.Retarder))
            result |= HMButton.RightStick;

        if (Has(buttons, InputButtons.ParkingBrake))
            result |= HMButton.Guide;

        return result;
    }

    /// <summary>
    /// Checks whether a particular TruckTilt button flag is active.
    /// </summary>
    private static bool Has(
        InputButtons value,
        InputButtons flag)
    {
        return (value & flag) == flag;
    }

    /// <summary>
    /// Immediately releases all controller inputs.
    /// Used when the phone disconnects or times out.
    /// </summary>
    public void Neutral()
    {
        if (!Ready)
            return;

        var axes = HMGamepadStateHelpers.StandardAxes(
            _profile,

            // Steering center.
            leftStickX: 0.5f,

            // Unused axes centered.
            leftStickY: 0.5f,
            rightStickX: 0.5f,
            rightStickY: 0.5f,

            // Triggers released.
            leftTrigger: 0.0f,
            rightTrigger: 0.0f
        );

        var state = new HMGamepadState
        {
            Axes = axes,
            Buttons = HMButton.None
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
            // Always neutralize the controller before shutdown.
            Neutral();
        }
        catch
        {
            // Ignore errors while shutting down.
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


// ============================================================
// TRUCKTILT INPUT BUTTON FLAGS
// ============================================================

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
