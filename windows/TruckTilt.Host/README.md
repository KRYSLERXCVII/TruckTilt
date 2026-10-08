# TruckTilt v0.10.23 — Auto-Binding + Pedal Axis Fix

This Windows host package restores seamless ETS2/ATS automatic binding and fixes the throttle/brake axis mismatch.

## What changed

- ETS2/ATS are detected automatically. No game-selection button.
- `controls.sii` is backed up before TruckTilt changes it.
- TruckTilt DirectInput axes are bound as:
  - Steering → `joy.x`
  - Brake → `joy.rx`
  - Throttle → `joy.ry`
  - Clutch → `joy.sl1`
- `c_jcombined` is set to `0.000000` so throttle and brake remain independent.
- VirtualWheel now sends:
  - `RX = brake`
  - `RY = throttle`
  - `Slider = clutch`
  - `Dial = retarder`
- Existing UDP, camera touchpad/click, safety timeout, button logging, and HIDMaestro path are retained.
- Android code is not changed.

## Important

Replace the corresponding Windows host files in your TruckTilt repository with this package and let GitHub Actions build it.

Run the host as Administrator as before.

The binding uses the SCS documented `SYSTEM_ID.'DEVICE_ID'.INPUT_ID` format and the TruckTilt DirectInput ID discovered from `game.log.txt` / `global_controls.sii`.
