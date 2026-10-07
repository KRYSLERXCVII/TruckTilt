# TruckTilt architecture

## Input pipeline

Android sensor/touch input is converted to normalized values.

The Android network worker sends those values at a fixed ~62 Hz cadence independent of drawing FPS.

UDP is intentionally used for the live input stream because the latest state matters more than retransmitting stale packets.

## Host pipeline

The Windows host:

1. Discovers the phone by UDP broadcast.
2. Receives input packets.
3. Parses the latest state.
4. Updates one HIDMaestro virtual wheel.
5. Neutralizes the device after 500 ms of silence.

## HID model

The virtual device is a custom HID joystick/racing-wheel style device rather than XInput.

Descriptor inputs:

- Simulation Controls Steering
- Simulation Controls Throttle
- Simulation Controls Brake
- Simulation Controls Clutch
- Generic Desktop Slider for retarder/auxiliary axis
- 32 buttons
- 8-way hat
- HID PID 1.0 force-feedback block

This gives ETS2/ATS independent DirectInput/HID inputs without Xbox D-pad semantics.
