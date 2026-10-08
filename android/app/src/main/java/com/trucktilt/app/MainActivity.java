package com.trucktilt.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.ActivityInfo;
import android.os.BatteryManager;
import android.graphics.*;
import android.hardware.*;
import android.net.wifi.WifiManager;
import android.view.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TruckTilt v0.9 — branded Android controller build.
 * Android side of the Wi-Fi steering-wheel controller.
 *
 * The phone sends a normalized wheel/pedal state plus 32 independent buttons.
 * The Windows host exposes those inputs as a DirectInput-style virtual wheel.
 */
public class MainActivity extends Activity implements SensorEventListener {
    static final int PORT = 45454;
    static final int INPUT_INTERVAL_MS = 16;
    // Change this to your exact Buy Me a Coffee page if your username differs.
    static final String DONATE_URL = "https://buymeacoffee.com/kryslerxcvii";

    ControllerView view;
    SensorManager sensors;
    Sensor gyro, rotation;
    float gyroAngle = 0f, gyroBias = 0f, tiltAngle = 0f;
    long lastGyroNs = 0;




    UdpClient net;
    volatile boolean hapticsEnabled;
    Vibrator vibrator;
    boolean offRoadRumbleActive = false;

    final ScheduledExecutorService networkExecutor =
            Executors.newScheduledThreadPool(2);
    ScheduledFuture<?> inputFuture;
    ScheduledFuture<?> pingFuture;
    volatile boolean networkLoopRunning = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        hapticsEnabled = getSharedPreferences("trucktilt_settings", MODE_PRIVATE)
                .getBoolean("haptics_enabled", true);
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager)getSystemService(VIBRATOR_MANAGER_SERVICE);
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator)getSystemService(VIBRATOR_SERVICE);
        }

        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        view = new ControllerView(this);
        net = new UdpClient();
        setContentView(view);
        net.startDiscovery();
        startNetworkLoop();
    }

    void startNetworkLoop() {
        networkLoopRunning = true;
        inputFuture = networkExecutor.scheduleAtFixedRate(() -> {
            try { if (networkLoopRunning) net.sendCurrentInput(view); }
            catch (Throwable t) { net.lastSendError = t.getClass().getSimpleName(); }
        }, 0, INPUT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        pingFuture = networkExecutor.scheduleAtFixedRate(() -> {
            try { if (networkLoopRunning) net.sendPing(); }
            catch (Throwable t) { net.lastSendError = t.getClass().getSimpleName(); }
        }, 500, 1000, TimeUnit.MILLISECONDS);
    }

    @Override protected void onResume() {
        super.onResume();
        if (gyro != null) sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        if (rotation != null) sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override protected void onPause() {
        sensors.unregisterListener(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        networkLoopRunning = false;
        if (inputFuture != null) inputFuture.cancel(true);
        if (pingFuture != null) pingFuture.cancel(true);
        networkExecutor.shutdownNow();
        if (net != null) net.stop();
        stopOffRoadRumble();
        super.onDestroy();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (lastGyroNs == 0) lastGyroNs = e.timestamp;
            float dt = (e.timestamp - lastGyroNs) / 1_000_000_000f;
            lastGyroNs = e.timestamp;

            if (dt > 0 && dt < .2f) {
                // Gyroscope is the sole steering sensor in GYRO WHEEL mode.
                // Do not automatically zero or correct the accumulated angle.
                gyroAngle -= Math.toDegrees(e.values[2] - gyroBias) * dt;

                // The selected steering range controls the actual virtual
                // wheel travel: 360/540/720/900/1080 degrees.
                float half = view == null ? 450f : view.rangeDeg / 2f;
                gyroAngle = clamp(gyroAngle, -half, half);
            }
        } else if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] r = new float[9];
            SensorManager.getRotationMatrixFromVector(r, e.values);
            float[] o = new float[3];
            SensorManager.getOrientation(r, o);

            // Rotation-vector remains available for GYRO TILT mode only.
            tiltAngle = clamp((float)Math.toDegrees(o[2]), -45f, 45f);
        }
        if (view != null) view.invalidate();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    void setHapticsEnabled(boolean enabled) {
        hapticsEnabled = enabled;
        getSharedPreferences("trucktilt_settings", MODE_PRIVATE)
                .edit().putBoolean("haptics_enabled", enabled).apply();
        if (!enabled) stopOffRoadRumble();
        if (view != null) view.invalidate();
    }

    void handleHapticMessage(String message) {
        if (!hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return;
        final String type = message.trim().toUpperCase(Locale.US);
        runOnUiThread(() -> {
            if (!hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return;
            try {
                switch (type) {
                    case "SHIFT":
                        vibrateOneShot(32, 85);
                        break;
                    case "ENGINE_START":
                        vibrateWaveform(
                                new long[]{0, 55, 35, 95, 55},
                                new int[]{0, 55, 95, 55, 0}, -1);
                        break;
                    case "ENGINE_STOP":
                        vibrateWaveform(
                                new long[]{0, 80, 45, 55, 65},
                                new int[]{0, 70, 45, 25, 0}, -1);
                        break;
                    case "RUMBLE_START":
                        startOffRoadRumble();
                        break;
                    case "RUMBLE_STOP":
                        stopOffRoadRumble();
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
                // Haptics must never interrupt controller operation.
            }
        });
    }

    void vibrateOneShot(long durationMs, int amplitude) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            int a = Math.max(1, Math.min(255, amplitude));
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, a));
        } else {
            vibrator.vibrate(durationMs);
        }
    }

    void vibrateWaveform(long[] timings, int[] amplitudes, int repeat) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, repeat));
        } else {
            vibrator.vibrate(timings, repeat);
        }
    }

    void startOffRoadRumble() {
        if (offRoadRumbleActive || vibrator == null || !vibrator.hasVibrator()) return;
        offRoadRumbleActive = true;
        // Soft repeating cabin/road rumble. The repeat begins after the short
        // fade-in so it does not feel like repeated notification pulses.
        vibrateWaveform(
                new long[]{0, 250, 250, 200},
                new int[]{0, 42, 30, 36}, 1);
    }

    void stopOffRoadRumble() {
        offRoadRumbleActive = false;
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Throwable ignored) {}
        }
    }

    int getBatteryPercent() {
        try {
            BatteryManager bm = (BatteryManager)getSystemService(BATTERY_SERVICE);
            int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return Math.max(0, Math.min(100, level));
        } catch (Exception ignored) {
            return -1;
        }
    }

    void openDonate() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)));
        } catch (Exception ignored) {
            // No browser available; keep the controller running normally.
        }
    }

    void calibrate() {
        // Explicit calibration only: zero the current gyro wheel position.
        // No automatic recalibration happens while driving.
        gyroAngle = 0f;
        gyroBias = 0f;
        lastGyroNs = 0;

        view.wheelAngleDeg = 0f;
        view.steer = 0f;
        view.invalidate();
    }

    static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    class ControllerView extends View {
        final Paint p = new Paint(3);
        final Paint stroke = new Paint(3);
        final HashMap<String,RectF> controls = new HashMap<>();
        final HashMap<Integer,String> pointerControls = new HashMap<>();
        final boolean[] buttons = new boolean[32];

        // User-editable cockpit layout. Positions/sizes are stored normalized
        // to the screen so a layout survives different phone resolutions.
        final android.content.SharedPreferences layoutPrefs =
                getSharedPreferences("trucktilt_layout", MODE_PRIVATE);
        boolean customizing = false;
        String editKey = null;
        boolean editResizing = false;
        int editPointerId = -1;
        float editStartX = 0f, editStartY = 0f;
        final RectF editStartRect = new RectF();
        float editOffsetX = 0f, editOffsetY = 0f;

        float steer = 0f, throttle = 0f, brake = 0f, clutch = 0f, retarder = 0f;
        float wheelTouchStartAngle = 0f, wheelStartAngleDeg = 0f, wheelAngleDeg = 0f;
        float wheelLastAngle = 0f;
        boolean wheelDragging = false;

        // Relative camera-trackpad state. Deltas are consumed by the 60 Hz
        // network loop and sent separately from the HID controller packet.
        final Object cameraSync = new Object();
        float cameraDx = 0f, cameraDy = 0f;
        boolean cameraDragging = false;
        int cameraPointerId = -1;
        float cameraLastX = 0f, cameraLastY = 0f;
        float cameraTapStartX=0f, cameraTapStartY=0f;
        long cameraTapStartMs=0L;
        boolean cameraMoved=false;
        boolean cameraClickPending=false;
        boolean wheelReturning = false;
        int wheelPointerId = -1;
        int mode = 2; // 0 gyro wheel, 1 gyro tilt, 2 virtual wheel
        int rangeDeg = 900;
        final int[] ranges = {360, 540, 720, 900, 1080};
        final String[] buttonNames = {
                "LEFT", "RIGHT", "HAZARD", "LIGHTS",
                "HIGH BEAM", "HORN", "AIR HORN", "WIPER",
                "WIPER+", "WIPER-", "ENGINE BRAKE", "RETARDER+",
                "RETARDER-", "CRUISE", "CRUISE+", "CRUISE-",
                "PARK BRAKE", "ENGINE", "GEAR UP", "GEAR DOWN",
                "CAMERA", "ROUTE", "MAP", "MIRROR",
                "BEACON", "DIFF LOCK", "TRAILER", "WARNING",
                "LEFT MIRROR", "RIGHT MIRROR", "ENTER", "BACK"
        };

        RectF wheelRect = new RectF();
        int activePedalPointer = -1;
        String activePedal = null;

        ControllerView(Context c) {
            super(c);
            p.setTypeface(Typeface.create("sans", Typeface.BOLD));
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
        }

        void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
            p.setTextSize(size); p.setColor(color); p.setTextAlign(align);
            p.setStyle(Paint.Style.FILL); c.drawText(s, x, y, p);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w=getWidth(), h=getHeight();
            c.drawColor(Color.rgb(7,9,11));

            // Responsive control sizing. The layout positions remain the same,
            // but button dimensions grow with the actual landscape screen so
            // controls are easier to read and hit without crowding the cockpit.
            float controlH = Math.max(44f, Math.min(58f, h * 0.050f));
            float smallControlH = Math.max(42f, Math.min(54f, h * 0.046f));
            float controlText = Math.max(10f, Math.min(15f, controlH * 0.24f));

            // Header
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(18,22,26));
            c.drawRect(0,0,w,58,p);
            // Clean, light branding: keep the creator/version secondary to the app name.
            text(c,"TRUCKTILT",20,37,20,Color.WHITE,Paint.Align.LEFT);
            int battery = getBatteryPercent();
            if (battery >= 0) {
                int batteryColor = battery <= 15 ? Color.rgb(255,100,100)
                        : battery <= 30 ? Color.rgb(255,190,70)
                        : Color.WHITE;
                text(c, battery + "%", w - 18, 36, 13, batteryColor, Paint.Align.RIGHT);
            }
            text(c,"by KRYSLERXCVII  •  v0.12.6",136,37,15,Color.rgb(205,212,220),Paint.Align.LEFT);

            // Prominent support button: red only for the donation CTA so it is
            // immediately discoverable without overpowering the cockpit.
            donateButton(c,"DONATE",w-82,29,132,38);

            // Keep connection diagnostics clearly separated from the donation CTA.
            text(c,net.connected ? "● CONNECTED" : "● SEARCHING",
                    w-168,22,11,net.connected?Color.rgb(139,195,74):Color.rgb(255,180,50),Paint.Align.RIGHT);
            String status=net.connected ? net.rttMs+" ms  •  "+net.rate+" Hz  •  TX "+net.txCount : "Wi-Fi only • auto discovery";
            text(c,status,w-168,42,10,Color.LTGRAY,Paint.Align.RIGHT);

            // Truck cockpit layout:
            // LEFT   = steering + frequently used steering-wheel controls
            // CENTER = grouped truck functions + camera touchpad
            // RIGHT  = progressive pedals + drivetrain controls
            float leftWidth=w*.34f;
            float centerLeft=w*.36f, centerRight=w*.72f;
            float pedalLeft=w*.75f, pedalRight=w*.99f;

            // -----------------------------------------------------------------
            // STEERING AREA
            // -----------------------------------------------------------------
            float wheelCx=leftWidth*.50f;
            float wheelCy=h*.56f;
            float radius=Math.min(leftWidth*.39f,h*.34f);
            wheelRect.set(wheelCx-radius,wheelCy-radius,wheelCx+radius,wheelCy+radius);

            // Keep the three setup controls: MODE, steering range and CALIBRATE.
            button(c,"MODE",wheelCx-88,78,105,38,false);
            button(c,rangeDeg+"°",wheelCx+20,78,88,38,false);
            button(c,"CALIBRATE",wheelCx+111,78,115,38,false);
            button(c,"CUSTOMIZE",wheelCx+215,78,135,38,customizing);
            button(c,hapticsEnabled ? "HAPTICS ON" : "HAPTICS OFF",wheelCx+215,124,135,34,hapticsEnabled);
            if(customizing)
                button(c,"RESET LAYOUT",wheelCx+215,164,135,34,false);

            // Steering-wheel quick controls: indicators on either side, horns above.
            // Indicators sit fully OUTSIDE the steering wheel so they remain visible
            // and easy to tap; they are never covered by the wheel graphic.
            // Steering-wheel quick controls: LEFT, HORN and RIGHT share one
            // easy-to-reach horizontal row above the wheel. Air horn sits directly
            // below the horn so the three primary controls stay visually grouped.
            float quickY=wheelCy-radius-34f;
            float quickW=Math.min(112f, Math.max(92f, w * 0.052f));
            float quickGap=Math.max(16f, w * 0.010f);
            // Keep LEFT, HORN and RIGHT on one row, fully inside the screen.
            // This avoids the indicator buttons being hidden behind/off the wheel.
            drawMappedButton(c,0,wheelCx-quickW-quickGap,quickY,quickW,controlH); // LEFT
            drawMappedButton(c,5,wheelCx,quickY,quickW,controlH);                 // HORN
            drawMappedButton(c,1,wheelCx+quickW+quickGap,quickY,quickW,controlH); // RIGHT
            drawMappedButton(c,6,wheelCx,wheelCy-radius-82,quickW,smallControlH);      // AIR HORN

            drawWheel(c,wheelCx,wheelCy,radius);

            // -----------------------------------------------------------------
            // CENTER: logical truck controls. Frequently used controls are
            // grouped instead of following the raw button-number order.
            // -----------------------------------------------------------------
            float colW=(centerRight-centerLeft)/4f;
            float buttonW=Math.min(170f, colW-8f);
            float centerButtonH = controlH;
            float top=86f;
            // Add breathing room between button rows so adjacent controls are
            // easier to distinguish and tap.
            float rowGap=Math.min(62f,(h*.48f-top)/7f);

            // Row 1 — lighting
            int[] lighting={3,2,4,24};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,lighting[col],x,top+rowGap*.5f,buttonW,centerButtonH);
            }

            // Row 2 — cruise cluster + engine. Cruise is the main button,
            // with + and - immediately beside it.
            int[] cruiseRow={17,13,14,15};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,cruiseRow[col],x,top+rowGap*1.5f,buttonW,centerButtonH);
            }

            // Row 3 — parking brake and wipers
            int[] wiperRow={16,7,8,9};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,wiperRow[col],x,top+rowGap*2.5f,buttonW,centerButtonH);
            }

            // Row 4 — mirrors and camera
            int[] viewRow={28,29,23,20};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,viewRow[col],x,top+rowGap*3.5f,buttonW,centerButtonH);
            }

            // Row 5 — navigation / truck utility
            int[] utilityRow={21,22,25,26};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,utilityRow[col],x,top+rowGap*4.5f,buttonW,centerButtonH);
            }

            // Row 6 — remaining utility controls
            int[] utilityRow2={27,30,31};
            for(int col=0;col<3;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,utilityRow2[col],x,top+rowGap*5.5f,buttonW,centerButtonH);
            }

            // Camera touchpad occupies the lower center and remains independent
            // from every truck button.
            float cameraLeft=centerLeft+8f, cameraRight=centerRight-8f;
            float cameraTop=top+rowGap*7f+14f, cameraBottom=h*.94f;
            if(cameraBottom>cameraTop+70f)
                drawCameraPad(c,cameraLeft,cameraTop,cameraRight,cameraBottom);

            // -----------------------------------------------------------------
            // PEDALS / DRIVETRAIN
            // -----------------------------------------------------------------
            float pw=Math.min(190,(pedalRight-pedalLeft-24)/2f);
            float px1=pedalLeft+(pedalRight-pedalLeft)*.27f;
            float px2=pedalLeft+(pedalRight-pedalLeft)*.73f;

            text(c,"PEDALS",(pedalLeft+pedalRight)/2f,78,14,Color.LTGRAY,Paint.Align.CENTER);
            float py1=h*.37f;
            drawProgressivePedal(c,"THROTTLE",px1,py1,pw,270,throttle);
            drawProgressivePedal(c,"BRAKE",px2,py1,pw,270,brake);

            // Gear and engine controls are deliberately close to the pedals.
            drawMappedButton(c,18,px1,h*.59f,pw*.88f,controlH); // GEAR UP
            drawMappedButton(c,10,px2,h*.59f,pw*.88f,controlH); // ENGINE BRAKE (swapped into Gear Down position)
            drawMappedButton(c,19,px1,h*.68f,pw*.88f,controlH); // GEAR DOWN (swapped into Engine Brake position)
            drawMappedButton(c,11,px2,h*.68f,pw*.88f,controlH); // RETARDER+
            drawMappedButton(c,12,px2,h*.77f,pw*.88f,controlH); // RETARDER-

            drawPedal(c,"CLUTCH",px1,h*.84f,pw*.88f,62,clutch);
            drawPedal(c,"RETARDER",px2,h*.84f,pw*.88f,62,retarder);

            String modeText=mode==0?"GYRO WHEEL":mode==1?"GYRO TILT":"VIRTUAL WHEEL";
            text(c,modeText,wheelCx,wheelCy+8,14,Color.LTGRAY,Paint.Align.CENTER);
            text(c,String.format(Locale.US,"%+.0f° / %d°",currentSteeringDegrees(),rangeDeg),
                    wheelCx,wheelCy+36,15,Color.rgb(139,195,74),Paint.Align.CENTER);

            if(customizing)
                drawCustomizeOverlay(c);

            if(net.lastSendError!=null)
                text(c,"TX ERROR: "+net.lastSendError,w-18,55,10,Color.rgb(255,100,100),Paint.Align.RIGHT);
        }

        float currentSteeringDegrees() {
            if(mode==0) return gyroAngle;
            if(mode==1) return (tiltAngle/45f)*(rangeDeg/2f);
            return wheelAngleDeg;
        }

        void drawWheel(Canvas c,float cx,float cy,float r) {
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(33,38,43));
            c.drawCircle(cx,cy,r,p);
            stroke.setStrokeWidth(11); stroke.setColor(Color.rgb(92,102,110));
            c.drawCircle(cx,cy,r-8,stroke);
            c.save(); c.rotate(currentSteeringDegrees()/2f,cx,cy);
            p.setColor(Color.rgb(67,74,80));
            c.drawRoundRect(cx-r*.10f,cy-r*.92f,cx+r*.10f,cy-r*.32f,10,10,p);
            c.drawRoundRect(cx-r*.92f,cy-r*.10f,cx-r*.32f,cy+r*.10f,10,10,p);
            c.drawRoundRect(cx+r*.32f,cy-r*.10f,cx+r*.92f,cy+r*.10f,10,10,p);
            p.setColor(Color.rgb(15,18,21)); c.drawCircle(cx,cy,r*.25f,p); c.restore();
            p.setColor(Color.rgb(139,195,74)); c.drawCircle(cx,cy,5,p);
        }

        void drawCameraPad(Canvas c,float left,float top,float right,float bottom) {
            RectF r=new RectF(left,top,right,bottom);
            r=customRect("CAMERA_PAD",r);
            controls.put("CAMERA_PAD",new RectF(r));

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(18,23,28));
            c.drawRoundRect(r,16,16,p);

            stroke.setStrokeWidth(2);
            stroke.setColor(Color.rgb(58,68,76));
            c.drawRoundRect(r,16,16,stroke);

            drawControlLabel(c,"CAMERA TOUCHPAD",r,14,Color.WHITE);
            text(c,"SWIPE TO LOOK",r.centerX(),
                    r.centerY()+Math.min(30f,r.height()*0.18f),
                    10,Color.LTGRAY,Paint.Align.CENTER);
            text(c,"←  LOOK  →",r.centerX(),r.bottom-18,10,
                    Color.rgb(139,195,74),Paint.Align.CENTER);
        }

        void drawControlLabel(Canvas c, String label, RectF r, float baseSize, int color) {
            // Text belongs to the control rectangle: its position and size are
            // derived from the customized rectangle, never the original layout.
            float size = Math.max(9f, Math.min(baseSize * 1.45f,
                    Math.min(r.height() * 0.30f, r.width() / Math.max(6f, label.length() * 0.58f))));
            text(c,label,r.centerX(),r.centerY()+size*0.34f,size,color,Paint.Align.CENTER);
        }

        void drawMappedButton(Canvas c,int index,float cx,float cy,float bw,float bh) {
            boolean active=buttons[index];
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            r=customRect("B"+index,r);
            controls.put("B"+index,new RectF(r));
            p.setStyle(Paint.Style.FILL);
            p.setColor(active?Color.rgb(139,195,74):Color.rgb(27,32,37));
            c.drawRoundRect(r,9,9,p);
            drawControlLabel(c,buttonNames[index],r,9,
                    active?Color.rgb(7,9,11):Color.WHITE);
        }

        void button(Canvas c,String label,float cx,float cy,float bw,float bh,boolean active) {
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            controls.put(label,r);
            p.setStyle(Paint.Style.FILL); p.setColor(active?Color.rgb(139,195,74):Color.rgb(27,32,37));
            c.drawRoundRect(r,9,9,p);
            text(c,label,cx,cy+4,10,active?Color.rgb(7,9,11):Color.WHITE,Paint.Align.CENTER);
        }

        void donateButton(Canvas c,String label,float cx,float cy,float bw,float bh) {
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            controls.put(label,r);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(190,42,52));
            c.drawRoundRect(r,10,10,p);
            text(c,label,cx,cy+4,11,Color.WHITE,Paint.Align.CENTER);
        }

        void drawProgressivePedal(Canvas c,String label,float cx,float cy,float bw,float bh,float value) {
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            r=customRect("P_"+label,r);
            controls.put("P_"+label,new RectF(r));

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(24,29,34));
            c.drawRoundRect(r,12,12,p);

            float v=clamp(value,0f,1f);
            if(v>0.001f){
                p.setColor(Color.rgb(139,195,74));
                float fillTop=r.bottom-(r.height()*v);
                c.drawRoundRect(new RectF(r.left,r.top,r.right,r.bottom),12,12,p);
                // Cut the fill back to the actual progress area using the background color.
                p.setColor(Color.rgb(24,29,34));
                c.drawRoundRect(new RectF(r.left,r.top,r.right,fillTop),12,12,p);
                p.setColor(Color.rgb(139,195,74));
                c.drawRect(r.left,fillTop,r.right,r.bottom,p);
            }

            // Progress marker.
            float markerY=r.bottom-(r.height()*v);
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(r.left+10,markerY-3,r.right-10,markerY+3),3,3,p);

            drawControlLabel(c,label,r,10,Color.WHITE);
            text(c,String.format(Locale.US,"%d%%",(int)(v*100f)),
                    r.centerX(),r.centerY()+Math.min(24f,r.height()*0.25f),
                    9,Color.LTGRAY,Paint.Align.CENTER);
        }

        void drawPedal(Canvas c,String label,float cx,float cy,float bw,float bh,float value) {
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            r=customRect("P_"+label,r);
            controls.put("P_"+label,new RectF(r));
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(24,29,34)); c.drawRoundRect(r,10,10,p);
            if(value>0.001f){
                p.setColor(Color.rgb(139,195,74));
                c.drawRoundRect(new RectF(r.left,r.top,r.left+bw*clamp(value,0,1),r.bottom),10,10,p);
            }
            drawControlLabel(c,label,r,10,Color.WHITE);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            final int action=e.getActionMasked();
            final int actionIndex=e.getActionIndex();
            final int pointerId=e.getPointerId(actionIndex);
            final float x=e.getX(actionIndex), y=e.getY(actionIndex);

            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
                if(hit("CUSTOMIZE",x,y)){
                    customizing=!customizing;
                    editKey=null; editResizing=false;
                    invalidate(); return true;
                }
                if(hit(hapticsEnabled ? "HAPTICS ON" : "HAPTICS OFF",x,y)){
                    setHapticsEnabled(!hapticsEnabled);
                    return true;
                }
                if(customizing) {
                    if(hit("RESET LAYOUT",x,y)){
                        resetLayout();
                        return true;
                    }
                    if(action==MotionEvent.ACTION_POINTER_DOWN) return true;

                    String k=findEditableKey(x,y);
                    if(k!=null){
                        editKey=k;
                        editPointerId=pointerId;
                        RectF r=controls.get(k);
                        editStartRect.set(r);
                        editStartX=x;
                        editStartY=y;
                        editResizing=isResizeHandle(r,x,y);
                        editOffsetX=x-r.centerX();
                        editOffsetY=y-r.centerY();
                        invalidate();
                    } else {
                        editKey=null;
                        editPointerId=-1;
                        editResizing=false;
                        invalidate();
                    }
                    return true;
                }

                if(hit("DONATE",x,y)){ openDonate(); return true; }
                if(hit("MODE",x,y)){ mode=(mode+1)%3; invalidate(); return true; }
                if(hit(rangeDeg+"°",x,y)){ cycleRange(); invalidate(); return true; }
                if(hit("CALIBRATE",x,y)){ calibrate(); return true; }

                if(wheelRect.contains(x,y) && wheelPointerId==-1) {
                    wheelReturning=false;
                    wheelPointerId=pointerId; wheelDragging=true;
                    wheelTouchStartAngle=angleFromCenter(x,y);
                    wheelLastAngle=wheelTouchStartAngle;
                    wheelStartAngleDeg=wheelAngleDeg;
                    return true;
                }

                if(hit("CAMERA_PAD",x,y) && cameraPointerId==-1) {
                    cameraPointerId=pointerId;
                    cameraDragging=true;
                    cameraLastX=x; cameraLastY=y;
                    cameraTapStartX=x;
                    cameraTapStartY=y;
                    cameraTapStartMs=System.currentTimeMillis();
                    cameraMoved=false;
                    return true;
                }

                String pedal=pedalAt(x,y);
                if(pedal!=null && activePedalPointer==-1){
                    activePedalPointer=pointerId;
                    activePedal=pedal;
                    if("THROTTLE".equals(pedal) || "BRAKE".equals(pedal))
                        setPedal(pedal,pedalValueAt(pedal,y));
                    else
                        setPedal(pedal,1f);
                    invalidate(); return true;
                }

                for(int i=0;i<32;i++) {
                    if(hit("B"+i,x,y)) {
                        buttons[i]=true;
                        pointerControls.put(pointerId,"B"+i);
                        // Short, device-appropriate click feedback for every cockpit button.
                        // It is controlled by the single HAPTICS ON/OFF setting.
                        if(hapticsEnabled) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        }
                        invalidate();
                        return true;
                    }
                }
                return true;
            }

            if(customizing) {
                if(editPointerId>=0 && action==MotionEvent.ACTION_MOVE &&
                   e.findPointerIndex(editPointerId)>=0 && editKey!=null) {
                    int idx=e.findPointerIndex(editPointerId);
                    float mx=e.getX(idx), my=e.getY(idx);
                    RectF r=controls.get(editKey);
                    if(r!=null) {
                        float dx=mx-editStartX;
                        float dy=my-editStartY;

                        if(editResizing){
                            // Continuous bottom-right resize. No grid or step snapping.
                            float minW=40f, minH=32f;
                            float right=clamp(editStartRect.right+dx,
                                    editStartRect.left+minW,getWidth()-8f);
                            float bottom=clamp(editStartRect.bottom+dy,
                                    editStartRect.top+minH,getHeight()-8f);
                            r.set(editStartRect.left,editStartRect.top,right,bottom);
                        } else {
                            // 1:1 movement: finger delta equals control delta.
                            float nx=editStartRect.left+dx;
                            float ny=editStartRect.top+dy;
                            float maxX=getWidth()-editStartRect.width()-8f;
                            float maxY=getHeight()-editStartRect.height()-8f;
                            nx=clamp(nx,8f,Math.max(8f,maxX));
                            ny=clamp(ny,62f,Math.max(62f,maxY));
                            r.set(nx,ny,
                                  nx+editStartRect.width(),
                                  ny+editStartRect.height());
                        }
                        invalidate();
                    }
                    return true;
                }
                if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                    if(editKey!=null) saveCustomRect(editKey,controls.get(editKey));
                    editPointerId=-1;
                    editResizing=false;
                    return true;
                }
                return true;
            }

            // Multi-touch: update each active control independently. Steering,
            // throttle/brake, and camera each keep their own pointer ID, so one
            // finger can pan the camera while another operates a pedal or wheel.
            if(action==MotionEvent.ACTION_MOVE && activePedalPointer>=0) {
                int idx=e.findPointerIndex(activePedalPointer);
                if(idx>=0 && ("THROTTLE".equals(activePedal) || "BRAKE".equals(activePedal))) {
                    setPedal(activePedal,pedalValueAt(activePedal,e.getY(idx)));
                    invalidate();
                }
            }

            if(action==MotionEvent.ACTION_MOVE && wheelDragging && wheelPointerId>=0) {
                int idx=e.findPointerIndex(wheelPointerId);
                if(idx>=0) {
                    float a=angleFromCenter(e.getX(idx),e.getY(idx));
                    float delta=normalizeDelta(a-wheelLastAngle);
                    wheelLastAngle=a;

                    // Hard steering stops. Continuous angle accumulation prevents
                    // atan2() wraparound from suddenly reversing steering.
                    float maxAngle=rangeDeg/2f;
                    wheelAngleDeg=clamp(
                            wheelAngleDeg + delta*(rangeDeg/360f),
                            -maxAngle,
                            maxAngle
                    );
                    steer=clamp(wheelAngleDeg/maxAngle,-1f,1f);
                    invalidate();
                }
            }

            // Do NOT return here: camera movement must be allowed to run in
            // the same ACTION_MOVE event as steering or a pedal. Each active
            // control uses its own pointer ID, so all three can move together.
            if(action==MotionEvent.ACTION_MOVE && cameraDragging && cameraPointerId>=0) {
                int idx=e.findPointerIndex(cameraPointerId);
                if(idx>=0) {
                    float nx=e.getX(idx), ny=e.getY(idx);
                    synchronized(cameraSync) {
                        cameraDx += nx-cameraLastX;
                        cameraDy += ny-cameraLastY;
                    }
                    if(Math.abs(nx-cameraTapStartX)>10f ||
                       Math.abs(ny-cameraTapStartY)>10f) {
                        cameraMoved=true;
                    }
                    cameraLastX=nx; cameraLastY=ny;
                }
                return true;
            }

            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP || action==MotionEvent.ACTION_CANCEL) {
                String pc=pointerControls.remove(pointerId);
                if(pc!=null && pc.startsWith("B")) {
                    int i=Integer.parseInt(pc.substring(1)); buttons[i]=false;
                }
                if(pointerId==wheelPointerId){
                    wheelPointerId=-1;
                    wheelDragging=false;
                    // Virtual wheel is spring-loaded: release smoothly returns steering to center.
                    startWheelReturn();
                }
                if(pointerId==cameraPointerId){
                    long duration=System.currentTimeMillis()-cameraTapStartMs;
                    if(action!=MotionEvent.ACTION_CANCEL &&
                       !cameraMoved && duration<=450L) {
                        synchronized(cameraSync) {
                            cameraClickPending=true;
                        }
                    }
                    cameraPointerId=-1;
                    cameraDragging=false;
                    cameraMoved=false;
                }
                if(pointerId==activePedalPointer){ activePedalPointer=-1; setPedal(activePedal,0f); activePedal=null; }
                invalidate(); return true;
            }
            return true;
        }

        boolean isEditableKey(String key) {
            return key != null && (key.startsWith("B") || key.startsWith("P_") || "CAMERA_PAD".equals(key));
        }

        String findEditableKey(float x,float y) {
            // Buttons first: they are the primary customizable controls.
            for(int i=0;i<32;i++) {
                RectF r=controls.get("B"+i);
                if(r!=null && r.contains(x,y)) return "B"+i;
            }
            for(String s:new String[]{"THROTTLE","BRAKE","CLUTCH","RETARDER"}) {
                RectF r=controls.get("P_"+s);
                if(r!=null && r.contains(x,y)) return "P_"+s;
            }
            RectF cam=controls.get("CAMERA_PAD");
            if(cam!=null && cam.contains(x,y)) return "CAMERA_PAD";
            return null;
        }

        boolean isResizeHandle(RectF r,float x,float y) {
            // Larger handle makes resizing easier; resizing itself remains 1:1.
            return r!=null && x>=r.right-44f && y>=r.bottom-44f;
        }

        RectF customRect(String key,RectF def) {
            // While editing, keep the live in-memory rectangle. The old version
            // reloaded the last saved Preferences value on every redraw, so the
            // control jumped back between touch events and felt like snapping.
            if(customizing && key.equals(editKey)) {
                RectF live=controls.get(key);
                if(live!=null) return new RectF(live);
            }

            if(!layoutPrefs.contains(key+"_cx")) return def;
            float cx=layoutPrefs.getFloat(key+"_cx",def.centerX()/Math.max(1,getWidth()))*getWidth();
            float cy=layoutPrefs.getFloat(key+"_cy",def.centerY()/Math.max(1,getHeight()))*getHeight();
            float bw=layoutPrefs.getFloat(key+"_w",def.width()/Math.max(1,getWidth()))*getWidth();
            float bh=layoutPrefs.getFloat(key+"_h",def.height()/Math.max(1,getHeight()))*getHeight();
            bw=Math.max(40f,bw); bh=Math.max(32f,bh);
            cx=clamp(cx,bw/2f,getWidth()-bw/2f);
            cy=clamp(cy,62f+bh/2f,getHeight()-bh/2f);
            return new RectF(cx-bw/2f,cy-bh/2f,cx+bw/2f,cy+bh/2f);
        }

        void saveCustomRect(String key,RectF r) {
            if(r==null || getWidth()<=0 || getHeight()<=0) return;
            layoutPrefs.edit()
                    .putFloat(key+"_cx",r.centerX()/getWidth())
                    .putFloat(key+"_cy",r.centerY()/getHeight())
                    .putFloat(key+"_w",r.width()/getWidth())
                    .putFloat(key+"_h",r.height()/getHeight())
                    .apply();
        }

        void resetLayout() {
            layoutPrefs.edit().clear().apply();
            editKey=null;
            editResizing=false;
            invalidate();
        }

        void drawCustomizeOverlay(Canvas c) {
            // Editor hint bar.
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(225,18,23,28));
            c.drawRoundRect(new RectF(getWidth()/2f-250,getHeight()-58,
                    getWidth()/2f+250,getHeight()-12),12,12,p);
            text(c,"CUSTOMIZE • DRAG FREELY • RESIZE FREELY • DONE TO LOCK",
                    getWidth()/2f,getHeight()-30,11,Color.WHITE,Paint.Align.CENTER);

            if(editKey!=null) {
                RectF r=controls.get(editKey);
                if(r!=null) {
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setStrokeWidth(3);
                    stroke.setColor(Color.rgb(139,195,74));
                    c.drawRoundRect(r,10,10,stroke);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.rgb(139,195,74));
                    c.drawCircle(r.right-12,r.bottom-12,9,p);
                    text(c,editKey.startsWith("B") ?
                            buttonNames[Integer.parseInt(editKey.substring(1))] : editKey,
                            r.centerX(),Math.max(18,r.top-8),10,Color.rgb(139,195,74),Paint.Align.CENTER);
                }
            }
        }

        void startWheelReturn(){
            if(Math.abs(wheelAngleDeg) < 0.5f){
                wheelAngleDeg=0f;
                steer=0f;
                wheelReturning=false;
                invalidate();
                return;
            }

            wheelReturning=true;

            postOnAnimation(new Runnable(){
                @Override public void run(){
                    if(!wheelReturning || wheelDragging) return;

                    // Exponential spring-back: fast initially, gentle near center.
                    wheelAngleDeg *= 0.78f;

                    if(Math.abs(wheelAngleDeg) < 0.5f){
                        wheelAngleDeg=0f;
                        steer=0f;
                        wheelReturning=false;
                        invalidate();
                        return;
                    }

                    steer=clamp(wheelAngleDeg/(rangeDeg/2f),-1f,1f);
                    invalidate();
                    postOnAnimation(this);
                }
            });
        }

        boolean hit(String key,float x,float y){ RectF r=controls.get(key); return r!=null&&r.contains(x,y); }
        String pedalAt(float x,float y){
            for(String s:new String[]{"CLUTCH","BRAKE","THROTTLE","RETARDER"}) if(hit("P_"+s,x,y)) return s;
            return null;
        }
        float pedalValueAt(String s,float y){
            RectF r=controls.get("P_"+s);
            if(r==null) return 0f;
            // Top = 100%, bottom = 0%.
            return clamp((r.bottom-y)/r.height(),0f,1f);
        }

        float[] consumeCameraDelta(){
            synchronized(cameraSync){
                float[] d=new float[]{cameraDx,cameraDy};
                cameraDx=0f; cameraDy=0f;
                return d;
            }
        }

        boolean consumeCameraClick(){
            synchronized(cameraSync){
                boolean click=cameraClickPending;
                cameraClickPending=false;
                return click;
            }
        }

        void setPedal(String s,float v){
            if("CLUTCH".equals(s)) clutch=v;
            else if("BRAKE".equals(s)) brake=v;
            else if("THROTTLE".equals(s)) throttle=v;
            else if("RETARDER".equals(s)) retarder=v;
        }
        void cycleRange(){
            int pos=0; for(int i=0;i<ranges.length;i++) if(ranges[i]==rangeDeg) pos=i;
            rangeDeg=ranges[(pos+1)%ranges.length];
            gyroAngle=clamp(gyroAngle,-rangeDeg/2f,rangeDeg/2f);
            wheelAngleDeg=clamp(wheelAngleDeg,-rangeDeg/2f,rangeDeg/2f);
        }
        float angleFromCenter(float x,float y){ return (float)Math.toDegrees(Math.atan2(y-wheelRect.centerY(),x-wheelRect.centerX())); }
        float normalizeDelta(float d){ while(d>180)d-=360; while(d<-180)d+=360; return d; }
    }

    class UdpClient {
        DatagramSocket socket;
        volatile boolean connected=false;
        volatile int rttMs=0, rate=0;
        volatile long txCount=0;
        volatile String lastSendError=null;
        InetAddress host; int hostPort=PORT;
        final AtomicBoolean running=new AtomicBoolean(true);
        Thread rx,discovery;
        WifiManager.MulticastLock lock;
        long sequence=0, rateWindowPackets=0, rateWindowStart=System.currentTimeMillis();

        void startDiscovery(){
            try{
                WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                lock=wm.createMulticastLock("TruckTilt"); lock.setReferenceCounted(true); lock.acquire();
                socket=new DatagramSocket(); socket.setBroadcast(true); socket.setSoTimeout(0);
                rx=new Thread(this::receiveLoop,"TruckTilt-RX"); rx.start();
                discovery=new Thread(()->{
                    while(running.get()&&!connected){
                        try{ sendRaw("DISCOVER|TruckTilt|0.7.2",InetAddress.getByName("255.255.255.255"),PORT); Thread.sleep(1000); }
                        catch(Exception ignored){}
                    }
                },"TruckTilt-Discovery"); discovery.start();
            }catch(Exception e){ connected=false; lastSendError=e.getClass().getSimpleName(); }
        }

        void receiveLoop(){
            byte[] buf=new byte[2048];
            try{
                while(running.get()){
                    DatagramPacket p=new DatagramPacket(buf,buf.length); socket.receive(p);
                    String s=new String(p.getData(),p.getOffset(),p.getLength());
                    if(s.startsWith("HELLO|TruckTilt|")){
                        host=p.getAddress(); hostPort=p.getPort(); connected=true;
                        sendRaw("CONNECT|"+android.os.Build.MODEL,host,hostPort);
                    } else if(s.startsWith("WELCOME|")) connected=true;
                    else if(s.startsWith("HAPTIC|")){
                        // Haptics are sent asynchronously by the Windows host.
                        // Keep them on the same UDP connection as controller data.
                        String payload = s.substring(7).trim();
                        if(!payload.isEmpty()) handleHapticMessage(payload);
                    } else if(s.startsWith("PONG|")){
                        try{ long sent=Long.parseLong(s.substring(5).trim()); rttMs=(int)Math.max(0,System.currentTimeMillis()-sent); }
                        catch(Exception ignored){}
                    }
                    view.postInvalidate();
                }
            }catch(Exception ignored){ connected=false; }
        }

        void sendCurrentInput(ControllerView v){
            if(!connected||host==null)return;
            float s;
            if(v.mode==0)s=clamp(gyroAngle/(v.rangeDeg/2f),-1f,1f);
            else if(v.mode==1)s=clamp(tiltAngle/45f,-1f,1f);
            else s=clamp(v.wheelAngleDeg/(v.rangeDeg/2f),-1f,1f);
            v.steer=s;
            long seq=++sequence, ts=System.currentTimeMillis();
            StringBuilder m=new StringBuilder(256);
            m.append("INPUT|").append(seq).append('|').append(ts)
             .append('|').append(String.format(Locale.US,"%.5f",s))
             .append('|').append(String.format(Locale.US,"%.5f",v.throttle))
             .append('|').append(String.format(Locale.US,"%.5f",v.brake))
             .append('|').append(String.format(Locale.US,"%.5f",v.clutch))
             .append('|').append(String.format(Locale.US,"%.5f",v.retarder));
            for(boolean b:v.buttons)m.append('|').append(b?1:0);
            try{
                sendRaw(m.toString(),host,hostPort); txCount++; rateWindowPackets++; lastSendError=null;

                float[] cam=v.consumeCameraDelta();
                if(Math.abs(cam[0]) > 0.01f || Math.abs(cam[1]) > 0.01f){
                    // Android Y grows downward; Windows mouse Y has the same
                    // screen convention, so no inversion is needed here.
                    String cm=String.format(Locale.US,"CAMERA|%.2f|%.2f",cam[0],cam[1]);
                    sendRaw(cm,host,hostPort);
                }

                if(v.consumeCameraClick()){
                    sendRaw("CLICK|LEFT",host,hostPort);
                }
            }catch(Exception ex){ lastSendError=ex.getClass().getSimpleName(); }
            long now=System.currentTimeMillis();
            if(now-rateWindowStart>=1000){rate=(int)rateWindowPackets;rateWindowPackets=0;rateWindowStart=now;}
        }

        void sendPing(){ if(!connected||host==null)return; try{sendRaw("PING|"+System.currentTimeMillis(),host,hostPort);}catch(Exception ex){lastSendError=ex.getClass().getSimpleName();} }
        void sendRaw(String s,InetAddress a,int p)throws Exception{
            if(socket==null||socket.isClosed())throw new IllegalStateException("socket_closed");
            byte[] d=s.getBytes(java.nio.charset.StandardCharsets.UTF_8); socket.send(new DatagramPacket(d,d.length,a,p));
        }
        void stop(){
            running.set(false); connected=false;
            if(socket!=null)try{socket.close();}catch(Exception ignored){}
            if(lock!=null)try{lock.release();}catch(Exception ignored){}
        }
    }
}
