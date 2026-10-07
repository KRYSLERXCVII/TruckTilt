# TruckTilt UDP Protocol v0.7.2

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

There are exactly 32 independent digital button fields after the five analog axes.

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

The Windows host neutralizes all axes and releases all 32 buttons when no valid input packet has been received for more than 500 ms.

## Virtual device

The Windows host does **not** create an Xbox/XInput controller.

It creates a custom HID/DirectInput-style steering wheel:

- Steering — signed 16-bit simulation-control axis
- Throttle — 16-bit simulation-control axis
- Brake — 16-bit simulation-control axis
- Clutch — 16-bit simulation-control axis
- Retarder — 16-bit slider axis
- 32 independent buttons
- 8-way hat
- PID force-feedback interface
