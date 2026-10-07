# TruckTilt v0.7

**Turn your Android phone into a customizable virtual truck steering wheel for Euro Truck Simulator 2 and American Truck Simulator.**

This version deliberately moves away from the Xbox controller model.

## New architecture

```text
Android phone
   │
   │ Wi-Fi / UDP 45454
   ▼
TruckTilt Windows Host
   │
   │ HIDMaestro
   ▼
TruckTilt Virtual Steering Wheel
   │
   ├── Steering axis
   ├── Throttle axis
   ├── Brake axis
   ├── Clutch axis
   ├── Retarder axis
   ├── 32 independent buttons
   └── 8-way hat
   │
   ▼
ETS2 / ATS (DirectInput/HID)
```

## Why this is different

The old builds exposed an Xbox 360-style controller. That limited the input surface and caused D-pad bindings to collide with ETS2 camera/map/navigation assignments.

v0.7 creates a **custom HID steering wheel** with dedicated inputs. ETS2 can bind each button separately rather than receiving Xbox D-pad semantics.

HIDMaestro's current SDK supports custom HID descriptors, arbitrary button counts, simulation-control axes, and PID force feedback. The pinned CI build uses HIDMaestro v1.11.0.

## Android controller

- Landscape only
- Wi-Fi only
- Automatic PC discovery
- Virtual Wheel steering
- Gyro Wheel steering
- Gyro Tilt steering
- 360 / 540 / 720 / 900 / 1080 degree virtual range
- Throttle, brake, clutch and retarder axes
- 32 individually numbered truck buttons
- Live RTT, packet rate and TX counter
- Calibration
- Multitouch: wheel + buttons/pedals can be used together

## 32 default buttons

1. Left indicator
2. Right indicator
3. Hazard
4. Lights
5. High beam
6. Horn
7. Air horn
8. Wiper
9. Wiper+
10. Wiper-
11. Engine brake
12. Retarder+
13. Retarder-
14. Cruise
15. Cruise+
16. Cruise-
17. Parking brake
18. Engine start/stop
19. Gear up
20. Gear down
21. Camera
22. Route advisor
23. Map
24. Mirror
25. Beacon
26. Differential lock
27. Trailer
28. Warning lights
29. Left mirror
30. Right mirror
31. Enter
32. Back

These are only defaults. The long-term UI will allow users to remap and reposition them.

## Windows first run

The Windows host requests Administrator privileges because HIDMaestro installs its virtual HID driver on first use.

After the host starts:

1. Open `joy.cpl`.
2. Find **TruckTilt Virtual Steering Wheel**.
3. Test the axes/buttons there.
4. Open ETS2 or ATS.
5. Select the TruckTilt wheel as the controller.
6. Bind Steering, Throttle, Brake and the individual buttons.

If ETS2 is still showing the old Xbox controller, remove the old TruckTilt Xbox device/binding and select the new wheel.

## GitHub Actions

No Android Studio is required.

### Android

`Actions → TruckTilt Android APK → Run workflow`

Download the APK artifact.

### Windows

`Actions → TruckTilt Windows Virtual Wheel → Run workflow`

Download either:

- `TruckTilt-Windows-v0.7-x64` — EXE
- `TruckTilt-Windows-v0.7-x64-ZIP` — packaged host

The workflow downloads HIDMaestro automatically, so the DLL is not committed to the repository.

## Requirements

### Phone

- Android 8.0+ (API 26+)
- Gyroscope recommended for Gyro Wheel
- Wi-Fi

### PC

- Windows 10/11 x64
- ETS2 / ATS
- Administrator permission on first TruckTilt Host launch
- Same LAN as the phone; Windows Mobile Hotspot is supported

## Important limitation

The HID device exposes a PID force-feedback interface, but v0.7 does **not** yet translate ETS2/ATS force-feedback effects into phone haptics. The output hook is present so this can be added later without changing the input architecture.

## Repository

https://github.com/KRYSLERXCVII/TruckTilt
