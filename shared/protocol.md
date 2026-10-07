# TruckTilt UDP Protocol v0.4

Port: `45454`

## Discovery

Phone broadcasts:

`DISCOVER|TruckTilt|0.4`

Windows replies:

`HELLO|TruckTilt|0.4|<host-name>`

Phone then sends:

`CONNECT|<device-model>`

Windows replies:

`WELCOME|<host-name>`

## Input

Phone sends input at approximately 60 Hz:

`INPUT|seq|timestampMs|steering|throttle|brake|clutch|left|right|lights|highBeam|hazard|horn|airHorn|wiper|engineBrake|retarder|parking|cruise|gearUp|gearDown|engine|reserved`

All boolean values are `0` or `1`.

Steering is normalized from `-1.0` to `+1.0`.

## Ping

Phone sends:

`PING|timestampMs`

Windows replies:

`PONG|timestampMs`

The phone calculates round-trip latency from the timestamp.

## Safety

The Windows host should neutralize all inputs when no valid input packet has been received for more than 500 ms.

## v0.4 goals

- Fixed-rate input transmission independent of screen redraw.
- Real ping/RTT measurement.
- Actual input packet-rate measurement.
- Correct field ordering between Android and Windows.
- Virtual wheel range represented as ±450° for a 900° wheel.


## Virtual controller v0.5

Windows exposes an Xbox 360-style virtual controller through HIDMaestro:

- Left Stick X: steering (-1..+1)
- LT: brake (0..1)
- RT: throttle (0..1)
- A/B/X/Y/LB/RB/Back/Start/L3/R3/Guide: truck controls

The virtual controller is neutralized when the phone has not sent a valid input packet for 500 ms.
