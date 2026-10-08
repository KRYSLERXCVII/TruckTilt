# TruckTilt UDP Protocol v0.12

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

## Haptics

Windows may send asynchronous haptic events to the connected phone:

`HAPTIC|1` — short gear-shift reminder pulse.

`HAPTIC|2` — short rough/off-road rumble pulse.

The Android app ignores these events when the single **Haptics OFF** switch is selected.

## Telemetry

The Windows host optionally reads the SCS telemetry shared-memory map named `Local\\SCSTelemetry`. If telemetry is unavailable, TruckTilt continues operating normally and simply sends no automatic haptic events.

## Ping

Phone sends:

`PING|timestampMs`

Windows replies:

`PONG|timestampMs`

## Safety

The Windows host neutralizes all axes and releases all 32 buttons when no valid input packet has been received for more than 500 ms.
