# TruckTilt Windows Host — Manual Binding

The host exposes the existing **TruckTilt Virtual Steering Wheel** as a custom DirectInput/HID device. It does not auto-edit `controls.sii` or inject keyboard keys.

## Controller inputs

- Steering: X axis
- Brake: Rx axis
- Throttle: Ry axis
- Clutch: Slider axis
- Retarder: Dial axis
- Buttons 1–32: existing controls, unchanged
- **Button 33: INTERIOR CAM**, independent of the existing CAMERA button

## Setup

1. Run `TruckTilt.Host.exe` as Administrator.
2. Start TruckTilt on Android and connect to the host.
3. Open ETS2/ATS Controls and manually bind each desired action.
4. Press INTERIOR CAM when the game asks for the input; it should register as button 33 on the same TruckTilt wheel.

The INTERIOR CAM control can be moved/resized using CUSTOMIZE on the phone. RESET LAYOUT remains directly below HAPTICS ON/OFF.
