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
 * TruckTilt v0.7.2
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
                gyroAngle += Math.toDegrees(e.values[2] - gyroBias) * dt;
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
        boolean wheelDragging = false;
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
            int w = getWidth(), h = getHeight();
            c.drawColor(Color.rgb(7,9,11));

            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(18,22,26));
            c.drawRect(0,0,w,58,p);
            text(c,"TRUCKTILT",20,37,20,Color.WHITE,Paint.Align.LEFT);
            text(c,net.connected ? "● CONNECTED" : "● SEARCHING",
                    w-390,34,13,net.connected?Color.rgb(139,195,74):Color.rgb(255,180,50),Paint.Align.LEFT);
            String status = net.connected
                    ? net.rttMs+" ms  •  "+net.rate+" Hz  •  TX "+net.txCount
                    : "Wi-Fi only • auto discovery";
            text(c,status,w-18,34,13,Color.LTGRAY,Paint.Align.RIGHT);

            // Central controls
            float cx=w/2f, cy=h*.53f, radius=Math.min(175,h*.29f);
            wheelRect.set(cx-radius,cy-radius,cx+radius,cy+radius);
            drawWheel(c,cx,cy,radius);

            button(c,"MODE",cx-65,72,105,32,false);
            button(c,rangeDeg+"°",cx+65,72,105,32,false);
            button(c,"CALIBRATE",cx,cy+radius+24,130,30,false);

            // Four banks around the wheel. Every entry has a dedicated HID button.
            float leftX=72, rightX=w-72;
            float top=75, gap=49;
            for(int i=0;i<16;i++) {
                int col=i/8, row=i%8;
                float x=col==0?leftX:rightX;
                float y=top+row*gap;
                drawMappedButton(c,i,x,y,132,38);
            }
            // second pair of columns gives compact labels for the same banks
            // without changing the physical button count.
            for(int i=16;i<32;i++) {
                int j=i-16;
                int col=j/8, row=j%8;
                float x=col==0?235:w-235;
                float y=top+row*gap;
                drawMappedButton(c,i,x,y,118,38);
            }

            // Pedals / axes along the bottom.
            drawPedal(c,"CLUTCH",120,h-62,150,48,clutch);
            drawPedal(c,"BRAKE",cx-115,h-62,150,48,brake);
            drawPedal(c,"THROTTLE",cx+115,h-62,150,48,throttle);
            drawPedal(c,"RETARDER",w-120,h-62,150,48,retarder);

            String modeText = mode==0?"GYRO WHEEL":mode==1?"GYRO TILT":"VIRTUAL WHEEL";
            text(c,modeText,cx,cy+7,14,Color.LTGRAY,Paint.Align.CENTER);
            text(c,String.format(Locale.US,"%+.0f° / %d°",currentSteeringDegrees(),rangeDeg),
                    cx,cy+34,15,Color.rgb(139,195,74),Paint.Align.CENTER);

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
                    wheelPointerId=pointerId; wheelDragging=true;
                    wheelTouchStartAngle=angleFromCenter(x,y); wheelStartAngleDeg=wheelAngleDeg;
                    return true;
                }

                String pedal=pedalAt(x,y);
                if(pedal!=null && activePedalPointer==-1){
                    activePedalPointer=pointerId; activePedal=pedal; setPedal(pedal,1f);
                    invalidate(); return true;
                }

                for(int i=0;i<32;i++) {
                    if(hit("B"+i,x,y)) { buttons[i]=true; pointerControls.put(pointerId,"B"+i); invalidate(); return true; }
                }
                return true;
            }

            if(action==MotionEvent.ACTION_MOVE && wheelDragging && wheelPointerId>=0) {
                int idx=e.findPointerIndex(wheelPointerId);
                if(idx>=0) {
                    float a=angleFromCenter(e.getX(idx),e.getY(idx));
                    float delta=normalizeDelta(a-wheelTouchStartAngle);
                    wheelAngleDeg=clamp(wheelStartAngleDeg + delta*(rangeDeg/360f),-rangeDeg/2f,rangeDeg/2f);
                    steer=wheelAngleDeg/(rangeDeg/2f); invalidate();
                }
                return true;
            }

            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP || action==MotionEvent.ACTION_CANCEL) {
                String pc=pointerControls.remove(pointerId);
                if(pc!=null && pc.startsWith("B")) {
                    int i=Integer.parseInt(pc.substring(1)); buttons[i]=false;
                }
                if(pointerId==wheelPointerId){ wheelPointerId=-1; wheelDragging=false; }
                if(pointerId==activePedalPointer){ activePedalPointer=-1; setPedal(activePedal,0f); activePedal=null; }
                invalidate(); return true;
            }
            return true;
        }

        boolean hit(String key,float x,float y){ RectF r=controls.get(key); return r!=null&&r.contains(x,y); }
        String pedalAt(float x,float y){
            for(String s:new String[]{"CLUTCH","BRAKE","THROTTLE","RETARDER"}) if(hit("P_"+s,x,y)) return s;
            return null;
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
