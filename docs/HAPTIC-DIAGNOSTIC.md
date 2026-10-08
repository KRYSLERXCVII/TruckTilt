# TruckTilt Haptic Diagnostic

This temporary diagnostic sends `HAPTIC|TEST` immediately after the phone connects.

Expected Windows console:
`HAPTIC TX: TEST (phone should vibrate once)`

Expected Android logcat:
`HAPTIC RX: TEST`

The Android Haptics setting must be ON. Once the end-to-end path is confirmed, the TEST packet/logging can be removed and telemetry events can be tested independently.
