# TruckTilt v0.7.4.1

Full replacement project for TruckTilt.

This release fixes the Android cockpit UI build error in v0.7.4. The `button()` helper requires a `Canvas` argument; the MODE, range, and CALIBRATE calls now pass the canvas correctly.

The cockpit layout remains:
- Large virtual steering wheel on the left
- Truck-control buttons in the center
- Large throttle, brake, clutch, and retarder controls on the right
- 32 independent digital buttons
- Wi-Fi UDP transport and automatic discovery
- Windows DirectInput virtual wheel architecture

## Build

Android is built by `.github/workflows/android.yml`.
Windows is built by `.github/workflows/windows.yml`.

Keep the repository `.git` directory when replacing the project contents.
