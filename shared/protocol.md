# TruckTilt UDP Protocol v0.7.4

Port: `45454`

## Discovery

Phone broadcasts:

`DISCOVER|TruckTilt|0.7.2`

Windows replies:

`HELLO|TruckTilt|0.7.2|<host-name>`

Phone then sends:

`CONNECT|<device-model>`

Windows replies:

`WELCOME|<host-name>|0.7`

## Input

Phone sends at approximately 62 Hz:

`INPUT|seq|timestampMs|steering|throttle|brake|clutch|retarder|b0|b1|...|b31`

There are 33 independent digital button fields after the five analog axes. Button 33 is INTERIOR CAM; the existing 32 indices are unchanged.

Analog values:

- `steering`: `-1.0 .. +1.0`
- `throttle`: `0.0 .. 1.0`
- `brake`: `0.0 .. 1.0`
- `clutch`: `0.0 .. 1.0`
- `retarder`: `0.0 .. 1.0`

Buttons are `0` or `1` and map one-to-one to HID button indices 1–32.

## Ping

Phone sends:

`PING|timestampMs`

Windows replies:

`PONG|timestampMs`

## Safety

The Windows host neutralizes all axes and releases all 33 buttons when no valid input packet has been received for more than 500 ms.

## Virtual device

The Windows host does **not** create an Xbox/XInput controller.

It creates a custom HID/DirectInput-style steering wheel:

- Steering — signed 16-bit simulation-control axis
- Throttle — 16-bit simulation-control axis
- Brake — 16-bit simulation-control axis
- Clutch — 16-bit simulation-control axis
- Retarder — 16-bit slider axis
- 33 independent buttons
- 8-way hat
- PID force-feedback interface

## Haptics

Windows may send optional haptic commands to the connected Android phone:

- `HAPTIC|SHIFT` — short shift reminder pulse.
- `HAPTIC|ENGINE_START` — short engine-start pattern.
- `HAPTIC|ENGINE_STOP` — short engine-stop pattern.
- `HAPTIC|RUMBLE_START` — start a repeating low-intensity off-road rumble.
- `HAPTIC|RUMBLE_STOP` — stop the off-road rumble.

The Android app has a single Haptics ON/OFF setting. When OFF, all incoming haptic commands are ignored and any active rumble is cancelled.


## Telemetry HUD (Windows host to phone)

When the SCS telemetry shared-memory map is active, the Windows host sends:

`HUD|gear|engineRpm|engineRpmMax|forwardGearCount`

The Android app uses this packet to show the current gear and an RPM-based shift prompt. `gear < 0` is reverse, `gear = 0` is neutral, and positive values are forward gears. The shift-up prompt appears at 86% of the engine RPM limit when another forward gear is available; a shift-down prompt appears below 22% RPM in gears above first. These are visual prompts only and do not change game gears automatically.
