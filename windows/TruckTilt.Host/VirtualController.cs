using System;
using HIDMaestro;

namespace TruckTilt.Host;

public sealed class VirtualController : IDisposable
{
    private HMContext? _context;
    private HMController? _controller;
    private HMProfile? _profile;

    private bool _initialized;
    private bool _disposed;

    public bool IsReady => _initialized && _controller != null;

    public void Initialize()
    {
        if (_disposed)
            throw new ObjectDisposedException(nameof(VirtualController));

        if (_initialized)
            return;

        Console.WriteLine("Initializing HIDMaestro virtual controller...");

        try
        {
            // Create HIDMaestro context.
            _context = new HMContext();

            // Load the built-in controller profiles.
            _context.LoadDefaultProfiles();

            // Use the Xbox 360 wired profile because ETS2 / ATS
            // can use it through XInput / DirectInput.
            _profile = _context.GetProfile("xbox-360-wired");

            if (_profile == null)
            {
                throw new InvalidOperationException(
                    "HIDMaestro Xbox 360 profile was not found."
                );
            }

            Console.WriteLine(
                $"HIDMaestro profile: {_profile.Name}"
            );

            // Install the HIDMaestro driver if required.
            // This requires the host application to run as Administrator.
            Console.WriteLine("Installing/checking HIDMaestro driver...");

            _context.InstallDriver();

            Console.WriteLine("HIDMaestro driver ready.");

            // Create the actual Windows virtual controller.
            _controller = _context.CreateController(
                _profile,
                "TruckTilt-Xbox360"
            );

            if (_controller == null)
            {
                throw new InvalidOperationException(
                    "Failed to create TruckTilt virtual controller."
                );
            }

            _initialized = true;

            Console.WriteLine("VIRTUAL CONTROLLER READY");
            Console.WriteLine(
                "ETS2 / ATS can now bind TruckTilt as a controller."
            );
        }
        catch
        {
            _initialized = false;

            try
            {
                _controller?.Dispose();
            }
            catch
            {
                // Ignore cleanup errors.
            }

            _controller = null;

            try
            {
                _context?.Dispose();
            }
            catch
            {
                // Ignore cleanup errors.
            }

            _context = null;

            throw;
        }
    }

    /// <summary>
    /// Submit the current TruckTilt input to the virtual Xbox 360 controller.
    ///
    /// steering:
    ///     -1 = full left
    ///      0 = center
    ///     +1 = full right
    ///
    /// throttle:
    ///      0 = released
    ///      1 = full throttle
    ///
    /// brake:
    ///      0 = released
    ///      1 = full brake
    /// </summary>
    public void Submit(
        float steering,
        float throttle,
        float brake,
        InputButtons buttons)
    {
        if (_disposed)
            return;

        if (!_initialized || _controller == null || _profile == null)
            return;

        // Clamp incoming values.
        steering = Math.Clamp(steering, -1.0f, 1.0f);
        throttle = Math.Clamp(throttle, 0.0f, 1.0f);
        brake = Math.Clamp(brake, 0.0f, 1.0f);

        // HIDMaestro uses 0..1 for analog values.
        //
        // Steering:
        // -1 -> 0
        //  0 -> 0.5
        // +1 -> 1
        float steeringAxis = (steering + 1.0f) * 0.5f;

        // Build the standard Xbox-style analog axes.
        var axes = HMGamepadStateHelpers.StandardAxes(
            _profile,

            leftStickX: steeringAxis,

            // Keep Y centered because TruckTilt does not use
            // the left stick Y axis.
            leftStickY: 0.5f,

            // Right stick unused.
            rightStickX: 0.5f,
            rightStickY: 0.5f,

            // Brake -> LT.
            leftTrigger: brake,

            // Throttle -> RT.
            rightTrigger: throttle
        );

        // ---------------------------------------------------------
        // Truck controls -> Xbox buttons
        // ---------------------------------------------------------

        HMButton hmButtons = 0;

        // IMPORTANT:
        // InputButtons members are STATIC definitions.
        // Therefore they must be referenced through InputButtons,
        // not through the 'buttons' instance.
        //
        // This fixes the CS0176 errors:
        //
        // buttons.Left       ❌
        // InputButtons.Left  ✅
        //
        // The actual pressed-state is obtained from the input packet.

        if (buttons.Has(InputButtons.Left))
            hmButtons |= HMButton.A;

        if (buttons.Has(InputButtons.Right))
            hmButtons |= HMButton.B;

        if (buttons.Has(InputButtons.Lights))
            hmButtons |= HMButton.X;

        if (buttons.Has(InputButtons.HighBeam))
            hmButtons |= HMButton.Y;

        if (buttons.Has(InputButtons.Hazard))
            hmButtons |= HMButton.LeftBumper;

        if (buttons.Has(InputButtons.Horn))
            hmButtons |= HMButton.RightBumper;

        if (buttons.Has(InputButtons.AirHorn))
            hmButtons |= HMButton.Back;

        if (buttons.Has(InputButtons.Wiper))
            hmButtons |= HMButton.Start;

        if (buttons.Has(InputButtons.EngineBrake))
            hmButtons |= HMButton.LeftStick;

        if (buttons.Has(InputButtons.Retarder))
            hmButtons |= HMButton.RightStick;

        if (buttons.Has(InputButtons.ParkingBrake))
            hmButtons |= HMButton.Guide;

        // Create the controller state.
        var state = new HMGamepadState
        {
            Axes = axes,
            Buttons = hmButtons
        };

        // Send state to Windows.
        _controller.SubmitState(in state);
    }

    /// <summary>
    /// Immediately release all analog inputs and buttons.
    /// Used when the phone disconnects or times out.
    /// </summary>
    public void Neutral()
    {
        if (_disposed)
            return;

        if (!_initialized || _controller == null || _profile == null)
            return;

        var axes = HMGamepadStateHelpers.StandardAxes(
            _profile,

            // Steering center.
            leftStickX: 0.5f,

            // Left stick Y center.
            leftStickY: 0.5f,

            // Right stick center.
            rightStickX: 0.5f,
            rightStickY: 0.5f,

            // Triggers released.
            leftTrigger: 0.0f,
            rightTrigger: 0.0f
        );

        var state = new HMGamepadState
        {
            Axes = axes,
            Buttons = 0
        };

        _controller.SubmitState(in state);
    }

    public void Dispose()
    {
        if (_disposed)
            return;

        _disposed = true;

        try
        {
            // Make sure the truck is left in a safe state.
            Neutral();
        }
        catch
        {
            // Ignore neutral-state errors during shutdown.
        }

        try
        {
            _controller?.Dispose();
        }
        catch
        {
            // Ignore controller cleanup errors.
        }

        _controller = null;

        try
        {
            _context?.Dispose();
        }
        catch
        {
            // Ignore context cleanup errors.
        }

        _context = null;
        _profile = null;
        _initialized = false;
    }
}
