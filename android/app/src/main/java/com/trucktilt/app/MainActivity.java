package com.trucktilt.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.hardware.*;
import android.net.wifi.WifiManager;
import android.view.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements SensorEventListener {
    static final int PORT = 45454;
    static final int INPUT_INTERVAL_MS = 16; // ~62.5 Hz target

    ControllerView view;
    SensorManager sensors;
    Sensor gyro, rotation;
    float gyroAngle = 0f, gyroBias = 0f, tiltAngle = 0f;
    long lastGyroNs = 0;
    UdpClient net;
    final Handler networkHandler = new Handler(Looper.getMainLooper());
    boolean networkLoopRunning = false;

    final Runnable inputTick = new Runnable() {
        @Override public void run() {
            if (!networkLoopRunning) return;
            net.sendCurrentInput(view);
            networkHandler.postDelayed(this, INPUT_INTERVAL_MS);
        }
    };

    final Runnable pingTick = new Runnable() {
        @Override public void run() {
            if (!networkLoopRunning) return;
            net.sendPing();
            networkHandler.postDelayed(this, 1000);
        }
    };

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
        networkHandler.post(inputTick);
        networkHandler.postDelayed(pingTick, 500);
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
        networkHandler.removeCallbacks(inputTick);
        networkHandler.removeCallbacks(pingTick);
        if (net != null) net.stop();
        super.onDestroy();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (lastGyroNs == 0) lastGyroNs = e.timestamp;
            float dt = (e.timestamp-lastGyroNs)/1_000_000_000f;
            lastGyroNs = e.timestamp;
            if (dt > 0 && dt < .2f) {
                gyroAngle += Math.toDegrees(e.values[2] - gyroBias) * dt;
                gyroAngle = clamp(gyroAngle, -450f, 450f);
            }
        } else if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] r = new float[9];
            SensorManager.getRotationMatrixFromVector(r, e.values);
            float[] o = new float[3];
            SensorManager.getOrientation(r, o);
            // Roll-like landscape tilt. This is clamped for the configured ±45° range.
            tiltAngle = (float)Math.toDegrees(o[2]);
            tiltAngle = clamp(tiltAngle, -45f, 45f);
        }
        view.invalidate();
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

    static float clamp(float v,float a,float b){
        return Math.max(a,Math.min(b,v));
    }

    class ControllerView extends View {
        Paint p = new Paint(3);
        Paint stroke = new Paint(3);

        float steer = 0f;
        float throttle = 0f, brake = 0f, clutch = 0f;

        float wheelTouchStartAngle = 0f;
        float wheelStartAngleDeg = 0f;
        float wheelAngleDeg = 0f;
        boolean wheelDragging = false;

        int mode = 2; // 0 gyro wheel, 1 gyro tilt, 2 virtual wheel

        boolean left,right,hazard,lights,highbeam,horn,airhorn,wiper,
                engineBrake,retarder,cruise,parking,engine,gearUp,gearDown;

        long lastTouchMillis = 0;
        RectF wheelRect = new RectF();
        HashMap<String,RectF> controls = new HashMap<>();

        ControllerView(Context c){
            super(c);
            p.setTypeface(Typeface.create("sans",Typeface.BOLD));
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
        }

        void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align align){
            p.setTextSize(size);
            p.setColor(color);
            p.setTextAlign(align);
            p.setStyle(Paint.Style.FILL);
            c.drawText(s,x,y,p);
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            int w=getWidth(),h=getHeight();
            c.drawColor(Color.rgb(8,10,12));

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(20,24,28));
            c.drawRect(0,0,w,54,p);

            text(c,"TRUCKTILT",22,35,20,Color.WHITE,Paint.Align.LEFT);

            String status=net.connected?"● CONNECTED":"● SEARCHING";
            int sc=net.connected?Color.rgb(139,195,74):Color.rgb(255,180,50);
            text(c,status,w-220,31,14,sc,Paint.Align.LEFT);
            text(c,net.connected?(net.rttMs+" ms  •  "+net.rate+" Hz"):"Wi-Fi only",
                    w-20,31,14,Color.LTGRAY,Paint.Align.RIGHT);

            float y=84;
            button(c,"LEFT",60,y,72,44,left,()->{});
            button(c,"LIGHTS",150,y,82,44,lights,()->{});
            button(c,"HIGH",250,y,72,44,highbeam,()->{});
            button(c,"HAZARD",w-145,y,90,44,hazard,()->{});
            button(c,"RIGHT",w-55,y,72,44,right,()->{});

            float cx=w/2f, cy=h*0.57f, radius=Math.min(150,h*0.30f);
            wheelRect.set(cx-radius,cy-radius,cx+radius,cy+radius);
            drawWheel(c,cx,cy,radius);

            button(c,"BRAKE",85,h-110,105,64,brake>0.01f,()->{});
            button(c,"THROTTLE",w-85,h-110,115,64,throttle>0.01f,()->{});
            button(c,"HORN",230,h-110,90,64,horn,()->{});
            button(c,"WIPER",340,h-110,90,64,wiper,()->{});
            button(c,"RETARDER",w-250,h-110,100,64,retarder,()->{});
            button(c,"PARK",w-350,h-110,90,64,parking,()->{});

            String modeText=mode==0?"GYRO WHEEL":mode==1?"GYRO TILT":"VIRTUAL WHEEL";
            text(c,"MODE: "+modeText,cx,cy+radius+28,14,Color.LTGRAY,Paint.Align.CENTER);
            text(c,"900°",cx,cy+6,24,Color.WHITE,Paint.Align.CENTER);
            text(c,String.format(Locale.US,"%+.0f°",currentSteeringDegrees()),
                    cx,cy+32,15,Color.rgb(139,195,74),Paint.Align.CENTER);
            text(c,"CALIBRATE",cx,54,12,Color.LTGRAY,Paint.Align.CENTER);
        }

        float currentSteeringDegrees(){
            if(mode==0) return gyroAngle;
            if(mode==1) return (tiltAngle/45f)*450f;
            return wheelAngleDeg;
        }

        void drawWheel(Canvas c,float cx,float cy,float r){
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(35,40,45));
            c.drawCircle(cx,cy,r,p);

            stroke.setStrokeWidth(10);
            stroke.setColor(Color.rgb(95,105,112));
            c.drawCircle(cx,cy,r-8,stroke);

            c.save();
            c.rotate(currentSteeringDegrees()/2f,cx,cy);

            p.setColor(Color.rgb(65,72,78));
            c.drawRoundRect(cx-r*.10f,cy-r*.92f,cx+r*.10f,cy-r*.35f,10,10,p);
            c.drawRoundRect(cx-r*.92f,cy-r*.10f,cx-r*.35f,cy+r*.10f,10,10,p);
            c.drawRoundRect(cx+r*.35f,cy-r*.10f,cx+r*.92f,cy+r*.10f,10,10,p);

            p.setColor(Color.rgb(20,24,28));
            c.drawCircle(cx,cy,r*.25f,p);
            c.restore();

            p.setColor(Color.rgb(139,195,74));
            c.drawCircle(cx,cy,5,p);
        }

        interface Action { void run(); }

        void button(Canvas c,String label,float cx,float cy,float bw,float bh,boolean active,Action a){
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            controls.put(label,r);
            p.setStyle(Paint.Style.FILL);
            p.setColor(active?Color.rgb(139,195,74):Color.rgb(29,34,39));
            c.drawRoundRect(r,12,12,p);
            text(c,label,cx,cy+5,11,
                    active?Color.rgb(8,10,12):Color.WHITE,Paint.Align.CENTER);
        }

        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX(), y=e.getY();
            int action=e.getActionMasked();

            if(action==MotionEvent.ACTION_DOWN){
                lastTouchMillis=System.currentTimeMillis();

                if(x>getWidth()/2-70&&x<getWidth()/2+70&&y<58){
                    calibrate();
                    return true;
                }

                if(wheelRect.contains(x,y)){
                    wheelDragging=true;
                    wheelTouchStartAngle=angleFromCenter(x,y);
                    wheelStartAngleDeg=wheelAngleDeg;
                    return true;
                }

                if(y>getHeight()-145 && x<160){
                    brake=1f;
                    return true;
                }

                if(y>getHeight()-145 && x>getWidth()-160){
                    throttle=1f;
                    return true;
                }

                handleButton(x,y,true);
                return true;
            }

            if(action==MotionEvent.ACTION_MOVE && wheelDragging){
                float a=angleFromCenter(x,y);
                float delta=normalizeDelta(a-wheelTouchStartAngle);

                // One 360° finger revolution maps to one 900° virtual wheel turn.
                wheelAngleDeg=clamp(wheelStartAngleDeg + delta*2.5f,-450f,450f);
                steer=wheelAngleDeg/450f;

                invalidate();
                return true;
            }

            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL){
                brake=0f;
                throttle=0f;

                if(wheelDragging){
                    wheelDragging=false;
                    invalidate();
                    return true;
                }

                handleButton(x,y,false);
                return true;
            }

            return true;
        }

        float angleFromCenter(float x,float y){
            float cx=wheelRect.centerX(),cy=wheelRect.centerY();
            return (float)Math.toDegrees(Math.atan2(y-cy,x-cx));
        }

        float normalizeDelta(float d){
            while(d>180)d-=360;
            while(d<-180)d+=360;
            return d;
        }

        void handleButton(float x,float y,boolean down){
            for(Map.Entry<String,RectF> en:controls.entrySet()){
                if(!en.getValue().contains(x,y)) continue;

                String k=en.getKey();
                if(k.equals("LEFT")) left=down;
                else if(k.equals("RIGHT")) right=down;
                else if(k.equals("LIGHTS")) lights=down;
                else if(k.equals("HIGH")) highbeam=down;
                else if(k.equals("HAZARD")) hazard=down;
                else if(k.equals("HORN")) horn=down;
                else if(k.equals("WIPER")) wiper=down;
                else if(k.equals("RETARDER")) retarder=down;
                else if(k.equals("PARK")) parking=down;

                invalidate();
                return;
            }
        }
    }

    class UdpClient {
        DatagramSocket socket;
        volatile boolean connected=false;
        volatile int rttMs=0;
        volatile int rate=0;

        InetAddress host;
        int hostPort=PORT;

        final AtomicBoolean running=new AtomicBoolean(true);
        Thread rx,discovery;
        WifiManager.MulticastLock lock;

        long packetsSent=0;
        long rateWindowStart=System.currentTimeMillis();

        void startDiscovery(){
            try{
                WifiManager wm=(WifiManager)getApplicationContext()
                        .getSystemService(WIFI_SERVICE);

                lock=wm.createMulticastLock("TruckTilt");
                lock.setReferenceCounted(true);
                lock.acquire();

                socket=new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(0);

                rx=new Thread(this::receiveLoop,"TruckTilt-RX");
                rx.start();

                discovery=new Thread(()->{
                    while(running.get() && !connected){
                        try{
                            sendRaw("DISCOVER|TruckTilt|0.4",
                                    InetAddress.getByName("255.255.255.255"),PORT);
                            Thread.sleep(1000);
                        }catch(Exception ignored){}
                    }
                },"TruckTilt-Discovery");
                discovery.start();

            }catch(Exception e){
                connected=false;
            }
        }

        void receiveLoop(){
            byte[] buf=new byte[2048];

            try{
                while(running.get()){
                    DatagramPacket p=new DatagramPacket(buf,buf.length);
                    socket.receive(p);

                    String s=new String(p.getData(),p.getOffset(),p.getLength());

                    if(s.startsWith("HELLO|TruckTilt|")){
                        host=p.getAddress();
                        hostPort=p.getPort();
                        connected=true;
                        sendRaw("CONNECT|"+android.os.Build.MODEL,host,hostPort);
                    }
                    else if(s.startsWith("WELCOME|")){
                        connected=true;
                    }
                    else if(s.startsWith("PONG|")){
                        try{
                            long sent=Long.parseLong(s.substring(5).trim());
                            rttMs=(int)Math.max(0,System.currentTimeMillis()-sent);
                        }catch(Exception ignored){}
                    }

                    view.postInvalidate();
                }
            }catch(Exception ignored){
                connected=false;
            }
        }

        void sendCurrentInput(ControllerView v){
            if(!connected || host==null) return;

            float s;
            if(v.mode==0) s=clamp(gyroAngle/450f,-1f,1f);
            else if(v.mode==1) s=clamp(tiltAngle/45f,-1f,1f);
            else s=clamp(v.wheelAngleDeg/450f,-1f,1f);

            v.steer=s;

            long seq=++packetsSent;
            long ts=System.currentTimeMillis();

            String m=String.format(Locale.US,
                    "INPUT|%d|%d|%.4f|%.4f|%.4f|%.4f|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d",
                    seq,ts,s,v.throttle,v.brake,v.clutch,
                    b(v.left),b(v.right),b(v.lights),b(v.highbeam),b(v.hazard),
                    b(v.horn),b(v.airhorn),b(v.wiper),b(v.engineBrake),b(v.retarder),
                    b(v.parking),b(v.cruise),b(v.gearUp),b(v.gearDown),b(v.engine),
                    0);

            // Keep the packet rate independent of drawing/rendering.
            sendRaw(m,host,hostPort);

            long now=System.currentTimeMillis();
            if(now-rateWindowStart>=1000){
                rate=(int)packetsSent;
                packetsSent=0;
                rateWindowStart=now;
            }
        }

        void sendPing(){
            if(!connected || host==null) return;
            sendRaw("PING|"+System.currentTimeMillis(),host,hostPort);
        }

        int b(boolean x){return x?1:0;}

        void sendRaw(String s,InetAddress a,int p){
            try{
                if(socket==null || socket.isClosed()) return;
                byte[] d=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(d,d.length,a,p));
            }catch(Exception ignored){}
        }

        void stop(){
            running.set(false);
            connected=false;

            if(socket!=null){
                try{socket.close();}catch(Exception ignored){}
            }

            if(lock!=null){
                try{lock.release();}catch(Exception ignored){}
            }
        }
    }
}
