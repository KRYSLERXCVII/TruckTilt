# TruckTilt UDP Protocol

Port: `45454`

All messages are UTF-8 text for V0.2 simplicity.

## Discovery

Phone broadcasts:

`DISCOVER|TruckTilt|0.2`

Windows replies:

`HELLO|TruckTilt|0.2|<host-name>`

## Connect

Phone sends:

`CONNECT|<client-id>`

Windows replies:

`WELCOME|<host-name>`

## Input

Phone sends approximately 60 packets/second:

`INPUT|<sequence>|<unix-ms>|<steer>|<throttle>|<brake>|<clutch>|<left>|<right>|<hazard>|<lights>|<highbeam>|<horn>|<airhorn>|<wiper>|<enginebrake>|<retarder>|<cruise>|<parking>|<engine>|<gearup>|<geardown>`

Analog values are normalized `-1.0..1.0` for steering and `0.0..1.0` for pedals.

## Ping

Phone:

`PING|<unix-ms>`

Windows:

`PONG|<unix-ms>`

Phone calculates round-trip time from the echoed timestamp.

## Safety

Windows tracks the time of the latest INPUT packet. If no packet arrives for 500 ms, the host must force all analog values to neutral and all momentary controls off.
