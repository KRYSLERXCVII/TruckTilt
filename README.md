# TruckTilt

**TruckTilt is a Wi-Fi mobile controller that turns an Android phone into a customizable cockpit for Euro Truck Simulator 2 (ETS2) and American Truck Simulator (ATS).**

The phone acts as the physical controller while a lightweight Windows host receives the controls over the local network and exposes them to the simulator as a Windows game controller. TruckTilt is designed around low-latency local control, configurable truck controls, phone gyro steering, a virtual steering wheel, progressive pedals, and a customizable cockpit layout.

> **TruckTilt — Turn your phone into a customizable truck cockpit.**

---

## What TruckTilt Does

- 📱 Android phone controller
- 🖥️ Windows host application
- 📡 Wi-Fi-only local communication
- ⚡ UDP input transport with automatic LAN discovery
- 🎮 Windows virtual controller architecture
- 🚛 Designed for ETS2 and ATS
- 🛞 Multiple steering methods
- 🎛️ Progressive throttle and brake controls
- 📷 Camera touchpad for looking around
- 🧩 Customizable cockpit layout
- 💾 Saved custom control positions and sizes
- 🔴 Safe neutralization when the phone disconnects

---

## Important Project Evolution

### v0.1 — Project Foundation
Established the TruckTilt concept and the basic Android-to-Windows controller architecture for truck simulators.

### v0.2 — Android + Windows Build Pipeline
Added the project structure and GitHub Actions build workflow so Android and Windows builds can be produced from GitHub without requiring Android Studio for the normal build process.

### v0.3 — Initial Working Controller
Established the landscape Android cockpit, Windows host communication, automatic LAN discovery, UDP input transport, and the first working steering/control interface.

### v0.4 — Input & Networking Improvements
Improved the transport layer with fixed-rate input transmission, connection handling, packet diagnostics, sequence/input tracking, and virtual steering-range handling.

### v0.4.1 / v0.4.2 — Diagnostics & Reliable Input
Added better Windows host diagnostics, real connection/latency reporting, packet-rate reporting, and a background Android input loop for more reliable continuous transmission.

### v0.5 — Virtual Windows Controller
Integrated **HIDMaestro** to create a Windows virtual Xbox-style controller. Steering, throttle, brake and truck buttons could now be exposed to ETS2/ATS through a standard controller interface.

### v0.6 — Truck Control Mapping
Expanded the controller mapping for truck functions such as indicators, lights, horn, air horn, wipers, engine brake, retarder and parking brake.

### v0.7 — Controller Refinement
Refined the controller axes and truck-control behavior, including throttle, brake, clutch and retarder handling.

### v0.8 / v0.9 — Advanced Cockpit Controls
The Android cockpit evolved into a more complete truck-control interface with:
- Virtual steering wheel
- Gyro steering
- Progressive throttle and brake
- Gear controls
- Cruise controls
- Retarder controls
- Truck lighting controls
- Mirror, camera, map and route controls
- Camera touchpad
- Multitouch steering + pedal operation
- Responsive cockpit sizing and spacing

### v0.10 — Customizable Cockpit
Added a proper cockpit editor.

Users can:
- Drag supported controls freely
- Resize controls
- Position pedals and buttons where they want
- Customize the camera touchpad
- Save their layout
- Reset the layout

The customization system uses normalized screen positions so layouts can adapt to different landscape screen sizes.

### v0.10.1 — Free Drag & Resize
Improved customization so controls move continuously with the user's finger instead of using coarse movement steps.

### v0.10.2 — Linked Labels
Button labels became part of their control rectangles so text stays centered and scales naturally when a control is resized.

### v0.10.3 — Live Customization Fix
Fixed a visual/input issue where a control could appear to snap back during live dragging because the saved layout was being redrawn over the live position.

Live editing now uses the active in-memory rectangle until the edit is finished.

---

## Current Steering Modes

TruckTilt supports the main steering styles needed for a mobile truck controller:

### Gyro Wheel
Rotate the phone like a steering wheel.

### Gyro Tilt
Tilt the phone left and right to steer.

---

## Camera Touchpad

The cockpit includes a dedicated camera touchpad.

It uses relative touch movement so the user can swipe:

- Left / right
- Up / down

to look around in the simulator.

The phone sends camera movement information to the Windows host, which handles the corresponding relative camera input.

---

## Safety

TruckTilt is designed to fail safely.

If the phone connection stops sending valid input, the Windows host can neutralize the virtual controller instead of leaving throttle, brake or steering stuck.

This is especially important for a controller intended for continuous truck-simulator use.

---

## Build

### Android

The Android application is built through:

```text
.github/workflows/android.yml
```

### Windows

The Windows host is built through:

```text
.github/workflows/windows.yml
```

The project is intended to be buildable through GitHub Actions without requiring Android Studio for the normal Android build workflow.

---

## Repository Structure

Important project areas:

```text
TruckTilt/
├── android/
│   └── app/
├── windows/
│   └── TruckTilt.Host/
├── .github/
│   └── workflows/
│       ├── android.yml
│       └── windows.yml
└── README.md
```

Keep the `.git` directory when replacing project contents in an existing repository.

---

## Support

If the project is useful to you, optional support is available through:

**Buy Me a Coffee — KRYSLERXCVII**

https://buymeacoffee.com/kryslerxcvii

Support is optional and does not affect the controller functionality.

---

## Project Status

TruckTilt currently has a working Android cockpit, Windows host, Wi-Fi/UDP communication, virtual controller output, steering/pedal controls, camera touchpad, and customizable cockpit layout.

The project is continuing toward a polished, community-friendly mobile controller for truck simulators.

If the repository is intended to be open source, a standard license such as MIT can be added as a separate `LICENSE` file.
