# ETS2 / ATS setup — TruckTilt Virtual Wheel

## 1. Verify Windows sees the wheel

Run:

`Win + R` → `joy.cpl`

You should see:

**TruckTilt Virtual Steering Wheel**

Open Properties and use the test page.

You should have separate inputs for:

- Steering
- Throttle
- Brake
- Clutch
- Retarder
- Buttons 1–32
- Hat

## 2. Bind ETS2 / ATS

Select the TruckTilt wheel in the controller/input section.

Bind:

- Steering → Wheel axis
- Acceleration → Z axis
- Brake → Rz axis
- Clutch → Slider axis
- Retarder → Dial axis
- Clutch → Clutch
- Retarder → Retarder axis or the dedicated retarder buttons
- Truck controls → their numbered TruckTilt buttons

## 3. Remove old Xbox bindings

If this PC was previously used with TruckTilt v0.5/v0.6, remove the old Xbox controller from the ETS2 bindings or create a clean controller profile.

The v0.7 device is intentionally a different HID device. Do not keep old D-pad assignments from the Xbox build when testing the new wheel.

## 4. Button test order

Before binding 32 buttons in ETS2, test them in `joy.cpl` one at a time. The Android UI labels each button with its number.

Example:

`1 LEFT`

should light **Button 1** in Windows.

`8 WIPER`

should light **Button 8** only.

This prevents game-level binding confusion from being mistaken for a network or HID problem.
