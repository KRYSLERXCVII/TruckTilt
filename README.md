# TruckTilt v0.7.2

TruckTilt turns an Android phone into a Wi-Fi truck-simulator controller for ETS2/ATS.

## v0.7.2 ETS2 DirectInput axis compatibility

This release changes the Windows HID descriptor again after testing with ETS2.
The virtual device is still a custom wheel with 32 buttons, but its analog axes
now use the conventional DirectInput `DIJOYSTATE2` position-axis slots:

- **X** — steering
- **Z** — throttle
- **Rz** — brake
- **Slider** — clutch
- **Dial** — retarder

The previous v0.7.1 build used the HID `Wheel` usage for steering. That could be
visible in Windows `joy.cpl` but ETS2's axis-capture screen did not accept it.
HIDMaestro documents X/Y/Z/Rx/Ry/Rz/Slider/Dial as the eight DirectInput
position-axis slots, so v0.7.2 deliberately stays inside that vocabulary.

## First test

1. Replace the existing repository contents with this full project. Keep `.git`.
2. Push to GitHub.
3. Run the Windows GitHub Actions build.
4. Run `TruckTilt.Host.exe` as Administrator.
5. Open `Win + R` → `joy.cpl`.
6. Verify steering moves **X**, throttle moves **Z**, brake moves **Rz**, clutch
   moves **Slider**, and retarder moves **Dial**.
7. Open ETS2 Controls → Setup Controller Axes.
8. Click **Steering axis** and move the phone wheel. ETS2 should now capture an
   X-axis instead of leaving the field at `missing`.

Do not change Android input code for this test; the phone-to-Windows transport
and 32-button protocol are unchanged from v0.7.1.
