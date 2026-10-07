# Building TruckTilt

## Android

No Android Studio is required to build the APK in GitHub Actions.

1. Push this repository to GitHub.
2. Open **Actions**.
3. Run **Android APK**.
4. Download the `TruckTilt-debug-apk` artifact.

For local Android Studio development, open the `android` directory.

## Windows

Install .NET 8 SDK and run:

```powershell
dotnet run --project windows/TruckTilt.Host/TruckTilt.Host.csproj
```

The host listens on UDP port `45454`.

## Network

Phone and PC must be on the same Wi-Fi network, or the phone can connect to a Windows mobile hotspot.

TruckTilt controller data stays on the local network.

## First test

1. Start the Windows host.
2. Start TruckTilt on the Android phone.
3. Tap **SCAN PC**.
4. Select the discovered host.
5. Tap **CONNECT**.
6. Confirm the top-right indicator shows Connected and a measured RTT.
7. Rotate the virtual wheel or use gyro steering.
8. Watch the Windows console for steering/control values.

The current host does not yet create a virtual ETS2/ATS wheel. That is the next milestone.
