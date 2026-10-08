# TruckTilt Smart Haptics v0.14.0

TruckTilt now uses telemetry-driven adaptive haptics. The Windows host reads the existing Rev. 12 `Local\SCSTelemetry` fields already used by the project and sends haptic events to the connected Android controller over the existing UDP connection.

## Events
- `SHIFT_HINT` — RPM-based shift reminder, silent at the highest forward gear.
- `SHIFT_UP` / `SHIFT_DOWN` — mechanical double-pulse on an actual gear change.
- `ENGINE_START` / `ENGINE_STOP` — ramped start/stop patterns.
- `RUMBLE|0..100` — adaptive continuous engine/road rumble intensity.
- `RUMBLE_STOP` — immediately stops adaptive rumble.

## Adaptive rumble
Engine rumble follows the verified engine RPM and RPM-max telemetry fields. Off-road rumble uses the existing wheel-ground, powered-wheel, wheel-substance and substance-name fields. Surface names such as gravel, dirt, mud, sand, grass, ground and snow are assigned different intensities. When multiple causes are active, TruckTilt uses the strongest intensity rather than stacking competing vibration loops.

The Android app still exposes only one user setting: **HAPTICS ON/OFF**. Turning it off immediately cancels active vibration.

No new telemetry offsets were invented for this version; the implementation stays within fields already present in the repository's existing Rev. 12 reader.
