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
