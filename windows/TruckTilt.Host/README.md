# TruckTilt v0.12.6 — Auto-Binding Normal Pedal Mode

This Windows host package automatically configures the active ETS2/ATS profile to match the known-good TruckTilt manual controller setup.

## Target controller setup

- Steering → X Axis (`joy.x`) — **Normal**
- Acceleration → Y Rotation (`joy.ry`) — **Normal**
- Brake → X Rotation (`joy.rx`) — **Normal**
- Clutch → Slider (`joy.sl1`) — **Normal**
- Combined pedals → **OFF**

TruckTilt's virtual DirectInput wheel exposes RX as brake and RY as throttle.

## What changed

- ETS2/ATS are detected automatically. No game-selection button.
- The binder now handles Windows-style as well as SCS/Linux-style profile paths when locating the active profile from `game.log.txt`.
- `controls.sii` is backed up before TruckTilt changes it.
- The four axis aliases are enforced: `joy.x`, `joy.ry`, `joy.rx`, `joy.sl1`.
- Combined pedals are forced OFF with `c_jcombined 0.000000`.
- Normal axis mode is explicitly enforced by disabling centered and inverted flags for steering, throttle, brake, and clutch.
- The host logs the exact automatic configuration applied.
- VirtualWheel mapping remains: RX = brake, RY = throttle, Slider = clutch, Dial = retarder.
- Existing UDP, camera touchpad/click, safety timeout, button logging, and HIDMaestro path are retained.
- Android code is not changed.

## Important

Run the Windows host as Administrator as before.

The binder uses the SCS documented `SYSTEM_ID.'DEVICE_ID'.INPUT_ID` format and discovers the TruckTilt DirectInput ID from `game.log.txt` / `global_controls.sii`. SCS documents `j_throttle`, `j_brake`, and `c_jcombined` as the relevant profile controls.


Haptics are optional and controlled by a single ON/OFF setting in the Android app.
