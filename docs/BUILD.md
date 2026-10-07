# Building TruckTilt

## Android

GitHub Actions is the recommended build path.

The Android workflow uses Java 17 and Gradle 8.9 and produces a debug APK.

No Android Studio is required for the normal GitHub build.

## Windows

The Windows workflow:

1. Installs .NET 10.
2. Downloads the pinned HIDMaestro v1.11.0 release.
3. Finds `HIDMaestro.Core.dll` recursively.
4. Restores and builds the host.
5. Publishes a self-contained single-file `win-x64` executable.
6. Creates a ZIP containing the executable and instructions.

Local Windows builds require `HIDMaestro.Core.dll` in:

`windows/TruckTilt.Host/third_party/HIDMaestro.Core.dll`

Run the host elevated because virtual HID creation requires Administrator privileges.
