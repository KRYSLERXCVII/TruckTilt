# ETS2 / ATS setup — TruckTilt v0.7.3

1. Run `TruckTilt.Host.exe` as Administrator.
2. Confirm the console prints the available axes and that it creates `TruckTilt Virtual Steering Wheel`.
3. Open `Win + R` → `joy.cpl` → Properties → Test.
4. Confirm the following axes move:
   - X Axis = steering
   - Z Axis = throttle
   - Z Rotation = brake
   - Slider = clutch
   - Dial = retarder
5. In ETS2/ATS Controls, select the TruckTilt device and manually bind each axis by clicking its `missing` field and then moving the corresponding phone control.
6. Do not use the Xbox/XInput controller preset.
7. Keep steering centered at rest; use 0% deadzone and adjust sensitivity after binding.

If `joy.cpl` axes do not move, do not troubleshoot ETS2 yet: check the TruckTilt host console INPUT lines first. The console must show changing STEER/THR/BRK/CLT/RET values when the phone controls move.
