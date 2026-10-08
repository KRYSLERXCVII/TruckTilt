# TruckTilt Haptics

TruckTilt deliberately exposes only one haptic setting in the Android cockpit:

**HAPTICS ON / HAPTICS OFF**

When enabled, the Windows host can send two automatic feedback events:

- **Gear shift reminder:** a short pulse when engine RPM reaches the shift threshold and another forward gear is available. No reminder is generated while the truck is already in its highest forward gear.
- **Rough/off-road surface:** a subtle pulse when a powered wheel is on the ground and the SCS telemetry substance is not classified as a normal road surface. The pulse repeats slowly while the condition continues.

## Telemetry requirement

The Windows host reads the SCS telemetry shared-memory map:

`Local\\SCSTelemetry`

TruckTilt does not crash or stop working when this map is unavailable. In that case the normal controller remains fully usable and telemetry-driven haptics simply remain inactive.

A compatible SCS telemetry plug-in that creates the shared-memory map must be installed in the game's:

`bin\\win_x64\\plugins`

The community `scs-sdk-plugin` project documents this shared-memory interface and the map name. Use a plugin build compatible with the installed ETS2/ATS version. The project is separate from TruckTilt and is not bundled into this repository.
