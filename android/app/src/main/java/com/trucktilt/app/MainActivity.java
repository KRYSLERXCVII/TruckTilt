package com.trucktilt.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.hardware.*;
import android.net.wifi.WifiManager;
import android.view.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TruckTilt v0.7.4
 * Android side of the Wi-Fi steering-wheel controller.
 *
 * The phone sends a normalized wheel/pedal state plus 32 independent buttons.
 * The Windows host exposes those inputs as a DirectInput-style virtual wheel.
 */
public class MainActivity extends Activity implements SensorEventListener {
    static final int PORT = 45454;
    static final int INPUT_INTERVAL_MS = 16;

    ControllerView view;
    SensorManager sensors;
    Sensor gyro, rotation;
    float gyroAngle = 0f, gyroBias = 0f, tiltAngle = 0f;
    long lastGyroNs = 0;
    UdpClient net;

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
        super.onDestroy();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (lastGyroNs == 0) lastGyroNs = e.timestamp;
            float dt = (e.timestamp - lastGyroNs) / 1_000_000_000f;
            lastGyroNs = e.timestamp;
            if (dt > 0 && dt < .2f) {
                gyroAngle -= Math.toDegrees(e.values[2] - gyroBias) * dt;
                float half = view == null ? 450f : view.rangeDeg / 2f;
                gyroAngle = clamp(gyroAngle, -half, half);
            }
        } else if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] r = new float[9];
            SensorManager.getRotationMatrixFromVector(r, e.values);
            float[] o = new float[3];
            SensorManager.getOrientation(r, o);
            tiltAngle = clamp((float)Math.toDegrees(o[2]), -45f, 45f);
        }
        if (view != null) view.invalidate();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    void calibrate() {
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
            text(c,"TRUCKTILT",20,37,20,Color.WHITE,Paint.Align.LEFT);
            text(c,net.connected ? "● CONNECTED" : "● SEARCHING",
                    w-390,34,13,net.connected?Color.rgb(139,195,74):Color.rgb(255,180,50),Paint.Align.LEFT);
            String status=net.connected ? net.rttMs+" ms  •  "+net.rate+" Hz  •  TX "+net.txCount : "Wi-Fi only • auto discovery";
            text(c,status,w-18,34,13,Color.LTGRAY,Paint.Align.RIGHT);

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
            float rowGap=Math.min(54f,(h*.48f-top)/7f);

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
            drawMappedButton(c,18,px1,h*.60f,pw*.88f,controlH); // GEAR UP
            drawMappedButton(c,10,px2,h*.60f,pw*.88f,controlH); // ENGINE BRAKE (swapped into Gear Down position)
            drawMappedButton(c,19,px1,h*.67f,pw*.88f,controlH); // GEAR DOWN (swapped into Engine Brake position)
            drawMappedButton(c,11,px2,h*.67f,pw*.88f,controlH); // RETARDER+
            drawMappedButton(c,12,px2,h*.74f,pw*.88f,controlH); // RETARDER-

            drawPedal(c,"CLUTCH",px1,h*.84f,pw*.88f,62,clutch);
            drawPedal(c,"RETARDER",px2,h*.84f,pw*.88f,62,retarder);

            String modeText=mode==0?"GYRO WHEEL":mode==1?"GYRO TILT":"VIRTUAL WHEEL";
            text(c,modeText,wheelCx,wheelCy+8,14,Color.LTGRAY,Paint.Align.CENTER);
            text(c,String.format(Locale.US,"%+.0f° / %d°",currentSteeringDegrees(),rangeDeg),
                    wheelCx,wheelCy+36,15,Color.rgb(139,195,74),Paint.Align.CENTER);

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
            controls.put("CAMERA_PAD",r);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(18,23,28));
            c.drawRoundRect(r,16,16,p);

            stroke.setStrokeWidth(2);
            stroke.setColor(Color.rgb(58,68,76));
            c.drawRoundRect(r,16,16,stroke);

            text(c,"CAMERA TOUCHPAD",r.centerX(),r.centerY()-8,14,Color.WHITE,Paint.Align.CENTER);
            text(c,"SWIPE TO LOOK",r.centerX(),r.centerY()+16,10,Color.LTGRAY,Paint.Align.CENTER);
            text(c,"←  LOOK  →",r.centerX(),r.bottom-18,10,Color.rgb(139,195,74),Paint.Align.CENTER);
        }

        void drawMappedButton(Canvas c,int index,float cx,float cy,float bw,float bh) {
            boolean active=buttons[index];
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            controls.put("B"+index,r);
            p.setStyle(Paint.Style.FILL);
            p.setColor(active?Color.rgb(139,195,74):Color.rgb(27,32,37));
            c.drawRoundRect(r,9,9,p);
            text(c,(index+1)+"  "+buttonNames[index],cx,cy+4,9,
                    active?Color.rgb(7,9,11):Color.WHITE,Paint.Align.CENTER);
        }

        void button(Canvas c,String label,float cx,float cy,float bw,float bh,boolean active) {
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            controls.put(label,r);
            p.setStyle(Paint.Style.FILL); p.setColor(active?Color.rgb(139,195,74):Color.rgb(27,32,37));
            c.drawRoundRect(r,9,9,p);
            text(c,label,cx,cy+4,10,active?Color.rgb(7,9,11):Color.WHITE,Paint.Align.CENTER);
        }

        void drawProgressivePedal(Canvas c,String label,float cx,float cy,float bw,float bh,float value) {
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            controls.put("P_"+label,r);

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

            text(c,label,cx,cy+4,10,Color.WHITE,Paint.Align.CENTER);
            text(c,String.format(Locale.US,"%d%%",(int)(v*100f)),cx,cy+22,9,Color.LTGRAY,Paint.Align.CENTER);
        }

        void drawPedal(Canvas c,String label,float cx,float cy,float bw,float bh,float value) {
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            controls.put("P_"+label,r);
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(24,29,34)); c.drawRoundRect(r,10,10,p);
            if(value>0.001f){
                p.setColor(Color.rgb(139,195,74));
                c.drawRoundRect(new RectF(r.left,r.top,r.left+bw*clamp(value,0,1),r.bottom),10,10,p);
            }
            text(c,label,cx,cy+4,10,Color.WHITE,Paint.Align.CENTER);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            final int action=e.getActionMasked();
            final int actionIndex=e.getActionIndex();
            final int pointerId=e.getPointerId(actionIndex);
            final float x=e.getX(actionIndex), y=e.getY(actionIndex);

            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
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
                    if(hit("B"+i,x,y)) { buttons[i]=true; pointerControls.put(pointerId,"B"+i); invalidate(); return true; }
                }
                return true;
            }

            // Multi-touch: update the pedal first. Previously the wheel's MOVE
            // handler returned immediately, which prevented a second finger from
            // continuously moving throttle/brake while steering.
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

            if(action==MotionEvent.ACTION_MOVE &&
               (wheelDragging || activePedalPointer>=0)) {
                return true;
            }

            if(action==MotionEvent.ACTION_MOVE && cameraDragging && cameraPointerId>=0) {
                int idx=e.findPointerIndex(cameraPointerId);
                if(idx>=0) {
                    float nx=e.getX(idx), ny=e.getY(idx);
                    synchronized(cameraSync) {
                        cameraDx += nx-cameraLastX;
                        cameraDy += ny-cameraLastY;
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
                    cameraPointerId=-1;
                    cameraDragging=false;
                }
                if(pointerId==activePedalPointer){ activePedalPointer=-1; setPedal(activePedal,0f); activePedal=null; }
                invalidate(); return true;
            }
            return true;
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
                    else if(s.startsWith("PONG|")){
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
