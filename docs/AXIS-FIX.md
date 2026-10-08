# TruckTilt v0.7.4.4 Axis Fix

The virtual wheel declares four trigger-slot usages: Rx, Ry, Slider and Dial.
HIDMaestro's `HMProfile.Triggers` semantic list exposes only the first two
Left/Right trigger roles. Therefore Slider and Dial must be written directly
through `HMGamepadState.Axes[HMAxis.Slider]` and `HMGamepadState.Axes[HMAxis.Dial]`.

v0.7.4.4 makes that correction. No Android APK change is required.

Expected joy.cpl behavior:
- X Axis: steering
- X Rotation: throttle
- Y Rotation: brake
- Slider: clutch
- Dial: retarder
