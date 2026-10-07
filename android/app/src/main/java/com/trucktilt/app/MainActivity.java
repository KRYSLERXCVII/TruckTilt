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
    ControllerView view;
    SensorManager sensors;
    Sensor gyro, rotation;
    float gyroAngle = 0f, gyroBias = 0f, tiltAngle = 0f;
    long lastGyroNs = 0;
    boolean gyroReady = false;
    UdpClient net;

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

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (lastGyroNs == 0) lastGyroNs = e.timestamp;
            float dt = (e.timestamp-lastGyroNs)/1_000_000_000f;
            lastGyroNs = e.timestamp;
            if (dt > 0 && dt < .2f) {
                // Z-axis integration is used for wheel mode; calibration removes drift.
                gyroAngle += Math.toDegrees(e.values[2] - gyroBias) * dt;
                gyroAngle = clamp(gyroAngle, -450f, 450f);
            }
        } else if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] r = new float[9];
            SensorManager.getRotationMatrixFromVector(r, e.values);
            float[] o = new float[3];
            SensorManager.getOrientation(r, o);
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
        view.steer = 0f;
        view.invalidate();
    }

    static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}

    class ControllerView extends View {
        Paint p = new Paint(3);
        Paint stroke = new Paint(3);
        float steer = 0f, throttle = 0f, brake = 0f, clutch = 0f;
        float wheelTouchStartAngle = 0f, wheelStartSteer = 0f;
        boolean wheelDragging = false;
        int mode = 2; // 0 gyro wheel, 1 gyro tilt, 2 virtual wheel
        boolean left,right,hazard,lights,highbeam,horn,airhorn,wiper,engineBrake,retarder,cruise,parking,engine,gearUp,gearDown;
        long seq=0, lastFrame=0;
        RectF wheelRect = new RectF();
        HashMap<String,RectF> controls = new HashMap<>();

        ControllerView(Context c){super(c); p.setTypeface(Typeface.create("sans",Typeface.BOLD)); stroke.setStyle(Paint.Style.STROKE); setFocusable(true);}

        void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align align){
            p.setTextSize(size); p.setColor(color); p.setTextAlign(align); p.setStyle(Paint.Style.FILL); c.drawText(s,x,y,p);
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            int w=getWidth(),h=getHeight();
            c.drawColor(Color.rgb(8,10,12));
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(20,24,28)); c.drawRect(0,0,w,54,p);
            text(c,"TRUCKTILT",22,35,20,Color.WHITE,Paint.Align.LEFT);
            String status=net.connected?"● CONNECTED":"● SEARCHING";
            int sc=net.connected?Color.rgb(139,195,74):Color.rgb(255,180,50);
            text(c,status,w-220,31,14,sc,Paint.Align.LEFT);
            text(c,net.connected?(net.rttMs+" ms  •  "+net.rate+" Hz"):"Wi-Fi only",w-20,31,14,Color.LTGRAY,Paint.Align.RIGHT);

            // Upper switches
            float y=84;
            button(c,"LEFT",60,y,72,44,left,()->{left=!left;});
            button(c,"LIGHTS",150,y,82,44,lights,()->{lights=!lights;});
            button(c,"HIGH",250,y,72,44,highbeam,()->{highbeam=!highbeam;});
            button(c,"HAZARD",w-145,y,90,44,hazard,()->{hazard=!hazard;});
            button(c,"RIGHT",w-55,y,72,44,right,()->{right=!right;});

            // wheel
            float cx=w/2f, cy=h*0.57f, radius=Math.min(150,h*0.30f);
            wheelRect.set(cx-radius,cy-radius,cx+radius,cy+radius);
            drawWheel(c,cx,cy,radius);

            // Pedals
            button(c,"BRAKE",85,h-110,105,64,brake>0.01f,()->{});
            button(c,"THROTTLE",w-85,h-110,115,64,throttle>0.01f,()->{});
            button(c,"HORN",230,h-110,90,64,horn,()->{horn=!horn;});
            button(c,"WIPER",340,h-110,90,64,wiper,()->{wiper=!wiper;});
            button(c,"RETARDER",w-250,h-110,100,64,retarder,()->{retarder=!retarder;});
            button(c,"PARK",w-350,h-110,90,64,parking,()->{parking=!parking;});

            text(c,"MODE: "+(mode==0?"GYRO WHEEL":mode==1?"GYRO TILT":"VIRTUAL WHEEL"),cx,cy+radius+28,14,Color.LTGRAY,Paint.Align.CENTER);
            text(c,"900°",cx,cy+6,24,Color.WHITE,Paint.Align.CENTER);
            text(c,String.format(Locale.US,"%+.0f°",steer*450f),cx,cy+32,15,Color.rgb(139,195,74),Paint.Align.CENTER);
            text(c,"CALIBRATE",cx,54,12,Color.LTGRAY,Paint.Align.CENTER);
            sendInput();
        }

        void drawWheel(Canvas c,float cx,float cy,float r){
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(35,40,45)); c.drawCircle(cx,cy,r,p);
            stroke.setStrokeWidth(10); stroke.setColor(Color.rgb(95,105,112)); c.drawCircle(cx,cy,r-8,stroke);
            c.save(); c.rotate(steer*450f/2f,cx,cy);
            p.setColor(Color.rgb(65,72,78)); c.drawRoundRect(cx-r*.10f,cy-r*.92f,cx+r*.10f,cy-r*.35f,10,10,p);
            c.drawRoundRect(cx-r*.92f,cy-r*.10f,cx-r*.35f,cy+r*.10f,10,10,p);
            c.drawRoundRect(cx+r*.35f,cy-r*.10f,cx+r*.92f,cy+r*.10f,10,10,p);
            p.setColor(Color.rgb(20,24,28)); c.drawCircle(cx,cy,r*.25f,p);
            c.restore();
            p.setColor(Color.rgb(139,195,74)); c.drawCircle(cx,cy,5,p);
        }

        interface Action { void run(); }

        void button(Canvas c,String label,float cx,float cy,float bw,float bh,boolean active,Action a){
            RectF r=new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);
            controls.put(label,r);
            p.setStyle(Paint.Style.FILL); p.setColor(active?Color.rgb(139,195,74):Color.rgb(29,34,39)); c.drawRoundRect(r,12,12,p);
            text(c,label,cx,cy+5,11,active?Color.rgb(8,10,12):Color.WHITE,Paint.Align.CENTER);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            float x=e.getX(),y=e.getY();
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                if(x>getWidth()/2-70&&x<getWidth()/2+70&&y<58){calibrate();return true;}
                if(wheelRect.contains(x,y)){
                    wheelDragging=true;
                    wheelTouchStartAngle=angleFromCenter(x,y);
                    wheelStartSteer=steer;
                    return true;
                }
                if(y>getHeight()-145 && x<160){brake=1;return true;}
                if(y>getHeight()-145 && x>getWidth()-160){throttle=1;return true;}
                handleButton(x,y,true);
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE && wheelDragging){
                float a=angleFromCenter(x,y);
                float delta=normalizeDelta(a-wheelTouchStartAngle);
                steer=clamp(wheelStartSteer+delta/900f, -1, 1);
                invalidate(); return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP || e.getAction()==MotionEvent.ACTION_CANCEL){
                brake=0; throttle=0;
                if(wheelDragging){wheelDragging=false; invalidate(); return true;}
                handleButton(x,y,false);
                return true;
            }
            return true;
        }

        float angleFromCenter(float x,float y){
            float cx=wheelRect.centerX(),cy=wheelRect.centerY();
            return (float)Math.toDegrees(Math.atan2(y-cy,x-cx));
        }
        float normalizeDelta(float d){while(d>180)d-=360;while(d<-180)d+=360;return d;}

        void handleButton(float x,float y,boolean down){
            for(Map.Entry<String,RectF> en:controls.entrySet()) if(en.getValue().contains(x,y)){
                String k=en.getKey();
                if(k.equals("LEFT"))left=down;
                else if(k.equals("RIGHT"))right=down;
                else if(k.equals("LIGHTS"))lights=down;
                else if(k.equals("HIGH"))highbeam=down;
                else if(k.equals("HAZARD"))hazard=down;
                else if(k.equals("HORN"))horn=down;
                else if(k.equals("WIPER"))wiper=down;
                else if(k.equals("RETARDER"))retarder=down;
                else if(k.equals("PARK"))parking=down;
                invalidate(); return;
            }
        }

        void sendInput(){
            float s;
            if(mode==0) s=gyroAngle/450f;
            else if(mode==1) s=tiltAngle/45f;
            else s=steer;
            steer=clamp(s,-1,1);
            net.sendInput(++seq,System.currentTimeMillis(),steer,throttle,brake,clutch,
                left,right,hazard,lights,highbeam,horn,airhorn,wiper,engineBrake,retarder,cruise,parking,engine,gearUp,gearDown);
            long now=System.currentTimeMillis();
            if(now-lastFrame>0) net.rate=(int)Math.min(120,Math.max(0,1000f/(now-lastFrame)));
            lastFrame=now;
        }
    }

    class UdpClient {
        DatagramSocket socket;
        volatile boolean connected=false;
        volatile int rttMs=0,rate=60;
        InetAddress host; int hostPort=PORT;
        AtomicBoolean running=new AtomicBoolean(true);
        Thread rx,tx;
        WifiManager.MulticastLock lock;

        void startDiscovery(){
            try{
                WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                lock=wm.createMulticastLock("TruckTilt"); lock.setReferenceCounted(true); lock.acquire();
                socket=new DatagramSocket(); socket.setBroadcast(true);
                rx=new Thread(()->receiveLoop()); rx.start();
                tx=new Thread(()->{
                    while(running.get()&&!connected){
                        try{sendRaw("DISCOVER|TruckTilt|0.2",InetAddress.getByName("255.255.255.255"),PORT);Thread.sleep(1000);}catch(Exception ignored){}
                    }
                });tx.start();
            }catch(Exception e){}
        }

        void receiveLoop(){
            byte[] buf=new byte[2048];
            try{
                while(running.get()){
                    DatagramPacket p=new DatagramPacket(buf,buf.length); socket.receive(p);
                    String s=new String(p.getData(),p.getOffset(),p.getLength());
                    if(s.startsWith("HELLO|")){
                        host=p.getAddress(); connected=true; hostPort=p.getPort();
                        sendRaw("CONNECT|"+android.os.Build.MODEL,host,hostPort);
                    }else if(s.startsWith("PONG|")){
                        try{long sent=Long.parseLong(s.split("\\|")[1]);rttMs=(int)Math.max(0,System.currentTimeMillis()-sent);}catch(Exception ignored){}
                    }
                    view.postInvalidate();
                }
            }catch(Exception ignored){}
        }

        void sendInput(long seq,long ts,float steer,float throttle,float brake,float clutch,
                       boolean left,boolean right,boolean hazard,boolean lights,boolean high,boolean horn,boolean air,
                       boolean wiper,boolean engBrake,boolean retard,boolean cruise,boolean park,boolean engine,boolean gu,boolean gd){
            if(!connected||host==null)return;
            String m=String.format(Locale.US,"INPUT|%d|%d|%.4f|%.4f|%.4f|%.4f|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d",
                seq,ts,steer,throttle,brake,clutch,b(left),b(right),b(hazard),b(lights),b(high),b(horn),b(air),b(wiper),b(engBrake),b(retard),b(cruise),b(park),b(engine),b(gu),b(gd));
            sendRaw(m,host,hostPort);
        }
        int b(boolean x){return x?1:0;}

        void sendRaw(String s,InetAddress a,int p){
            try{byte[] d=s.getBytes();socket.send(new DatagramPacket(d,d.length,a,p));}catch(Exception ignored){}
        }
    }
}
