# TruckTilt

Turn your Android phone into a Wi-Fi truck controller for Euro Truck Simulator 2 / American Truck Simulator.

## Current build

### Android
GitHub Actions builds the Android APK automatically.

### Windows
GitHub Actions builds a **self-contained, ready-to-run `TruckTilt.Host.exe`**.

You do **not** need to install .NET on the Windows PC to run that published executable.

The Windows workflow also creates:

- `TruckTilt.Host.exe`
- `TruckTilt-Windows-x64.zip`

## GitHub build

1. Upload this project to your GitHub repository.
2. Push to `main`, or open **Actions → Windows Host → Run workflow**.
3. Open the completed workflow run.
4. Download **TruckTilt-Windows-x64** for the EXE.
5. Download **TruckTilt-Windows-x64-ZIP** for the packaged Windows build.
6. Download the Android artifact from the Android workflow.

## Important

This version is the network/input foundation. The Windows host currently receives and displays controller input; the final ETS2/ATS analog virtual-controller integration is a separate stage.

## Network

UDP port: `45454`

The phone and Windows PC should be on the same LAN, including a Windows Mobile Hotspot connection.
