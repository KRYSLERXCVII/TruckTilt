# TruckTilt v0.7.1

TruckTilt turns an Android phone into a Wi-Fi truck-simulator controller for ETS2/ATS.

## v0.7.1 DirectInput axis fix

The Windows virtual wheel now uses the standard Generic Desktop HID usages used by DirectInput:

- Wheel — steering
- Z — throttle
- Rz — brake
- Slider — clutch
- Dial — retarder

This replaces the previous Simulation Controls axis usages because Windows `joy.cpl` and older DirectInput consumers do not consistently expose those usages as testable joystick axes.

The device still exposes 32 independent buttons and an 8-way hat.

## Test

1. Build the Windows workflow from GitHub Actions.
2. Run the host as Administrator.
3. Open `Win + R` → `joy.cpl`.
4. Select **TruckTilt Virtual Steering Wheel** → Properties → Test.
5. Verify Wheel, Z, Rz, Slider and Dial respond.
6. Only after all axes respond, configure ETS2.
