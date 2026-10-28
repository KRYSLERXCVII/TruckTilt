package com.trucktilt.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
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
 * TruckTilt APK test build — fused game-rotation-vector steering experiment.
 * The Windows host protocol and virtual-wheel return behavior are unchanged.
 */
public class MainActivity extends Activity implements SensorEventListener {
    static final int PORT = 45454;
    static final int INPUT_INTERVAL_MS = 16;
    static final String DONATE_URL = "https://buymeacoffee.com/kryslerxcvii";

    ControllerView view;
    SensorManager sensors;
    Sensor gyro, accel, gameRotationVector;

    float gyroAngle = 0f, tiltAngle = 0f;
    long lastGyroNs = 0L;

    // Preferred steering source: Android's fused rotation vector, which uses
    // magnetometer heading correction when available. Magnetic interference can
    // affect it, so the raw-gyro path below remains a fallback.
    private final float[] gameQuatCurrent = new float[4]; // x, y, z, w
    private final float[] gameQuatReference = new float[4]; // calibrated pose
    private boolean gameRotationVectorSeen = false;
    private boolean gameQuatReferenceValid = false;
    private boolean fusedRelativeInitialized = false;
    private boolean fusedRebasePending = false;
    private float fusedLastRelativeDeg = 0f;
    private float fusedAccumulatedDeg = 0f;

    // Fallback gyro steering state. Gravity is retained for tilt and gyro-bias
    // learning only; it is not used to infer or recenter yaw.
    float[] currentGravity = null;
    float rawGyroIntegratedDeg = 0f;
    float zeroCorrectionOffset = 0f; // retained for fallback compatibility; no gravity recovery
    float gyroBiasDegPerSec = 0f;
    float filteredGyroRateDegPerSec = 0f;
    boolean gyroRateFilterInitialized = false;
    long stationarySinceNs = 0L;
    // A short output-offset fade makes recalibration smooth instead of snapping.
    float calibrationFadeOffsetDeg = 0f;
    long calibrationFadeStartNs = 0L;
    static final long CALIBRATION_FADE_NS = 250_000_000L;

    // Independent camera-only gyro tracking. These values never feed into or
    // modify the existing steering integration/calibration path above.
    private boolean cameraGyroWasActive = false;
    private volatile boolean cameraGyroEnabled = true;
    private float cameraGyroYawDeg = 0f;
    private float cameraGyroPitchDeg = 0f;
    private float cameraGyroBiasXDegPerSec = 0f;
    private float cameraGyroBiasYDegPerSec = 0f;
    private float cameraGyroFilteredXDegPerSec = 0f;
    private float cameraGyroFilteredYDegPerSec = 0f;
    private boolean cameraGyroFilterInitialized = false;
    private long cameraGyroStationarySinceNs = 0L;
    private float cameraGyroLastMouseX = 0f;
    private float cameraGyroLastMouseY = 0f;
    private static final float CAMERA_GYRO_MOUSE_PER_DEG = 5.5f;
    private static final float CAMERA_GYRO_MAX_YAW_DEG = 45.0f;
    private static final float CAMERA_GYRO_MAX_PITCH_DEG = 30.0f;

    UdpClient net;
    volatile int batteryPercent = -1;
    final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                batteryPercent = Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
                if (view != null) view.postInvalidate();
            }
        }
    };
    volatile boolean hapticsEnabled;
    volatile int telemetryGear = Integer.MIN_VALUE;
    volatile float telemetryRpm = 0f, telemetryRpmMax = 0f, telemetrySpeedKmh = 0f;
    volatile int telemetryGearCount = 0;
    Vibrator vibrator;
    boolean offRoadRumbleActive = false;
    int adaptiveRumbleIntensity = -1;

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
        cameraGyroEnabled = getSharedPreferences("trucktilt_settings", MODE_PRIVATE)
                .getBoolean("camera_gyro_enabled", true);
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager)getSystemService(VIBRATOR_MANAGER_SERVICE);
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator)getSystemService(VIBRATOR_SERVICE);
        }

        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gameRotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        view = new ControllerView(this);
        net = new UdpClient();
        setContentView(view);
        Intent batteryIntent = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) batteryReceiver.onReceive(this, batteryIntent);
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
        if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        if (gameRotationVector != null) {
            sensors.registerListener(this, gameRotationVector, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override protected void onPause() {
        // Rebase fused orientation on resume so phone movement while the app is
        // backgrounded cannot become a sudden steering input.
        if (gameRotationVectorSeen) fusedRebasePending = true;
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
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            currentGravity = e.values.clone();
            
            float norm = (float)Math.sqrt(e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2]);
            if (norm > 0) {
                float pitch = (float)Math.toDegrees(Math.asin(clamp(e.values[1]/norm, -1f, 1f)));
                tiltAngle = clamp(pitch, -45f, 45f);
            }
        }
        else if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            handleGameRotationVector(e.values, e.timestamp);
        }
        else if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (lastGyroNs == 0L) {
                lastGyroNs = e.timestamp;
                return;
            }
            float dt = (e.timestamp - lastGyroNs) / 1_000_000_000f;
            lastGyroNs = e.timestamp;

            // Ignore only invalid/long gaps; do not quantize the steering angle.
            if (dt > 0f && dt < 0.1f) {
                // Camera gyro remains independent. This raw gyro path supplies
                // fallback steering and bias learning; fused steering is handled
                // separately by TYPE_ROTATION_VECTOR when available.
                updateCameraGyro(e.values[0], e.values[1], dt, e.timestamp);

                float measuredRateDegPerSec = -(float)Math.toDegrees(e.values[2]);
                float correctedRate = measuredRateDegPerSec - gyroBiasDegPerSec;

                // Light time-based rate filtering reduces sensor jitter without
                // smoothing away small steering movements or adding much latency.
                float filterAlpha = dt / (0.035f + dt);
                if (!gyroRateFilterInitialized) {
                    filteredGyroRateDegPerSec = correctedRate;
                    gyroRateFilterInitialized = true;
                } else {
                    filteredGyroRateDegPerSec +=
                            (correctedRate - filteredGyroRateDegPerSec) * filterAlpha;
                }

                // Learn residual gyro bias only after the corrected rate has been
                // very close to zero for a short period. No angle/gravity-based
                // correction is applied, so steering cannot be pulled toward zero.
                float accelNorm = 0f;
                if (currentGravity != null) {
                    accelNorm = (float)Math.sqrt(
                            currentGravity[0] * currentGravity[0] +
                            currentGravity[1] * currentGravity[1] +
                            currentGravity[2] * currentGravity[2]);
                }
                boolean gravityMagnitudePlausible = accelNorm >= 8.0f && accelNorm <= 11.5f;
                boolean nearlyStill = Math.abs(correctedRate) < 0.18f && gravityMagnitudePlausible;
                if (nearlyStill) {
                    if (stationarySinceNs == 0L) stationarySinceNs = e.timestamp;
                    if (e.timestamp - stationarySinceNs >= 700_000_000L) {
                        // Very slow adaptation (about a 20-second time constant).
                        float biasAlpha = dt / (20.0f + dt);
                        gyroBiasDegPerSec +=
                                (measuredRateDegPerSec - gyroBiasDegPerSec) * biasAlpha;
                    }
                } else {
                    stationarySinceNs = 0L;
                }

                rawGyroIntegratedDeg += filteredGyroRateDegPerSec * dt;

                // Do not use gravity alignment to correct yaw: the accelerometer
                // cannot observe rotation around the gravity axis. Keep this raw
                // gyro integration only as a fallback for devices without the
                // fused rotation vector sensor.
                if (gameRotationVector == null || !gameRotationVectorSeen) {
                    // Smoothly fade the calibration offset over 250 ms. This preserves
                    // continuity at the button press while making the new neutral zero.
                    float fade = 1f;
                    if (calibrationFadeStartNs != 0L) {
                        float elapsed = (e.timestamp - calibrationFadeStartNs) /
                                (float) CALIBRATION_FADE_NS;
                        if (elapsed >= 1f) {
                            calibrationFadeOffsetDeg = 0f;
                            calibrationFadeStartNs = 0L;
                        } else {
                            float t = clamp(elapsed, 0f, 1f);
                            fade = t * t * (3f - 2f * t); // smoothstep
                        }
                    }
                    float outputOffset = calibrationFadeStartNs == 0L
                            ? calibrationFadeOffsetDeg : calibrationFadeOffsetDeg * (1f - fade);
                    float half = view == null ? 450f : view.rangeDeg / 2f;
                    gyroAngle = clamp(rawGyroIntegratedDeg - zeroCorrectionOffset + outputOffset,
                            -half, half);
                }
            }
        }
        if (view != null) view.invalidate();
    }

    /**
     * Tracks relative rotation from the calibrated pose using Android's fused
     * rotation vector. Relative Z-axis twist is unwrapped over time so
     * supported steering ranges above 180 degrees remain continuous.
     */
    private void handleGameRotationVector(float[] values, long timestampNs) {
        if (values == null || values.length < 3) return;

        float x = values[0], y = values[1], z = values[2];
        float w = values.length >= 4
                ? values[3]
                : (float)Math.sqrt(Math.max(0f, 1f - x*x - y*y - z*z));
        float norm = (float)Math.sqrt(x*x + y*y + z*z + w*w);
        if (!(norm > 0f) || Float.isNaN(norm) || Float.isInfinite(norm)) return;
        x /= norm; y /= norm; z /= norm; w /= norm;

        float previousSteering = gyroAngle;
        gameQuatCurrent[0] = x;
        gameQuatCurrent[1] = y;
        gameQuatCurrent[2] = z;
        gameQuatCurrent[3] = w;

        // First valid sample, or first sample after resuming from background:
        // use the current pose as the reference instead of interpreting any
        // background phone movement as a steering command.
        if (!gameRotationVectorSeen || !gameQuatReferenceValid || fusedRebasePending) {
            gameQuatReference[0] = x;
            gameQuatReference[1] = y;
            gameQuatReference[2] = z;
            gameQuatReference[3] = w;
            gameQuatReferenceValid = true;
            gameRotationVectorSeen = true;
            fusedRelativeInitialized = true;
            fusedLastRelativeDeg = 0f;
            fusedAccumulatedDeg = 0f;
            fusedRebasePending = false;

            // If the raw-gyro fallback was active before the first fused sample,
            // fade its current output smoothly toward the new reference instead
            // of abruptly changing the displayed steering angle.
            if (Math.abs(previousSteering) > 0.01f) {
                calibrationFadeOffsetDeg = previousSteering;
                calibrationFadeStartNs = android.os.SystemClock.elapsedRealtimeNanos();
            } else {
                calibrationFadeOffsetDeg = 0f;
                calibrationFadeStartNs = 0L;
            }
            if (view != null) view.invalidate();
            return;
        }

        // qRelative = inverse(qReference) * qCurrent. The Z/W pair gives the
        // relative twist about the calibrated device Z axis (the existing
        // steering axis); unwrap each sample to support 540/720/900/1080 ranges.
        float rz = gameQuatReference[3] * z
                - gameQuatReference[0] * y
                + gameQuatReference[1] * x
                - gameQuatReference[2] * w;
        float rw = gameQuatReference[3] * w
                + gameQuatReference[0] * x
                + gameQuatReference[1] * y
                + gameQuatReference[2] * z;

        // Match the existing steering sign convention (raw gyro Z is negated).
        float relativeDeg = normalizeDelta(-(float)Math.toDegrees(2f * Math.atan2(rz, rw)));
        if (!fusedRelativeInitialized) {
            fusedLastRelativeDeg = relativeDeg;
            fusedAccumulatedDeg = relativeDeg;
            fusedRelativeInitialized = true;
        } else {
            // Predict the continuous angle from the sample-to-sample change.
            // Then re-anchor it to the nearest 360-degree equivalent of the
            // calibrated relative orientation. This prevents small sensor noise
            // from accumulating indefinitely and lets the output return toward
            // calibrated zero when the phone returns to its reference pose.
            float delta = normalizeDelta(relativeDeg - fusedLastRelativeDeg);
            float predicted = fusedAccumulatedDeg + delta;
            float anchored = relativeDeg + 360f * Math.round((predicted - relativeDeg) / 360f);
            fusedAccumulatedDeg = anchored;
            fusedLastRelativeDeg = relativeDeg;
        }

        // Apply the existing smooth calibration fade, if one is in progress.
        float outputOffset = 0f;
        if (calibrationFadeStartNs != 0L) {
            float elapsed = (timestampNs - calibrationFadeStartNs) / (float) CALIBRATION_FADE_NS;
            if (elapsed >= 1f) {
                calibrationFadeOffsetDeg = 0f;
                calibrationFadeStartNs = 0L;
            } else {
                float t = clamp(elapsed, 0f, 1f);
                float fade = t * t * (3f - 2f * t);
                outputOffset = calibrationFadeOffsetDeg * (1f - fade);
            }
        }
        float half = view == null ? 450f : view.rangeDeg / 2f;
        gyroAngle = clamp(fusedAccumulatedDeg + outputOffset, -half, half);
        if (view != null) view.invalidate();
    }

    /**
     * Converts phone gyro rotation into relative mouse deltas for the interior
     * camera. Entering virtual-wheel mode establishes a fresh neutral reference;
     * rotating back to that orientation emits reverse deltas and restores the view.
     * This method intentionally does not read or write any steering state.
     */
    private void updateCameraGyro(float rateXRadPerSec, float rateYRadPerSec,
                                  float dt, long timestampNs) {
        boolean active = view != null && view.mode == 2 && cameraGyroEnabled;
        if (!active) {
            cameraGyroWasActive = false;
            cameraGyroFilterInitialized = false;
            cameraGyroStationarySinceNs = 0L;
            return;
        }

        if (!cameraGyroWasActive) {
            cameraGyroWasActive = true;
            cameraGyroYawDeg = 0f;
            cameraGyroPitchDeg = 0f;
            cameraGyroLastMouseX = 0f;
            cameraGyroLastMouseY = 0f;
            cameraGyroBiasXDegPerSec = 0f;
            cameraGyroBiasYDegPerSec = 0f;
            cameraGyroFilteredXDegPerSec = 0f;
            cameraGyroFilteredYDegPerSec = 0f;
            cameraGyroFilterInitialized = false;
            cameraGyroStationarySinceNs = 0L;
            return;
        }

        if (!(dt > 0f && dt < 0.1f)) return;

        // In landscape orientation, device X is the phone's vertical rotation
        // axis (camera yaw) and device Y is its horizontal rotation axis (pitch).
        float measuredX = -(float)Math.toDegrees(rateXRadPerSec);
        float measuredY =  (float)Math.toDegrees(rateYRadPerSec);
        float correctedX = measuredX - cameraGyroBiasXDegPerSec;
        float correctedY = measuredY - cameraGyroBiasYDegPerSec;

        // Estimate camera-only gyro bias while the phone is still. This state is
        // independent of the steering bias and cannot alter steering behaviour.
        if (Math.abs(correctedX) < 0.35f && Math.abs(correctedY) < 0.35f) {
            if (cameraGyroStationarySinceNs == 0L) cameraGyroStationarySinceNs = timestampNs;
            if (timestampNs - cameraGyroStationarySinceNs >= 600_000_000L) {
                float biasAlpha = dt / (8.0f + dt);
                cameraGyroBiasXDegPerSec += measuredX * biasAlpha;
                cameraGyroBiasYDegPerSec += measuredY * biasAlpha;
            }
        } else {
            cameraGyroStationarySinceNs = 0L;
        }

        correctedX = measuredX - cameraGyroBiasXDegPerSec;
        correctedY = measuredY - cameraGyroBiasYDegPerSec;
        if (Math.abs(correctedX) < 0.10f) correctedX = 0f;
        if (Math.abs(correctedY) < 0.10f) correctedY = 0f;

        float alpha = dt / (0.025f + dt);
        if (!cameraGyroFilterInitialized) {
            cameraGyroFilteredXDegPerSec = correctedX;
            cameraGyroFilteredYDegPerSec = correctedY;
            cameraGyroFilterInitialized = true;
        } else {
            cameraGyroFilteredXDegPerSec +=
                    (correctedX - cameraGyroFilteredXDegPerSec) * alpha;
            cameraGyroFilteredYDegPerSec +=
                    (correctedY - cameraGyroFilteredYDegPerSec) * alpha;
        }

        cameraGyroYawDeg = clamp(
                cameraGyroYawDeg + cameraGyroFilteredXDegPerSec * dt,
                -CAMERA_GYRO_MAX_YAW_DEG, CAMERA_GYRO_MAX_YAW_DEG);
        cameraGyroPitchDeg = clamp(
                cameraGyroPitchDeg + cameraGyroFilteredYDegPerSec * dt,
                -CAMERA_GYRO_MAX_PITCH_DEG, CAMERA_GYRO_MAX_PITCH_DEG);

        float targetMouseX = cameraGyroYawDeg * CAMERA_GYRO_MOUSE_PER_DEG;
        float targetMouseY = cameraGyroPitchDeg * CAMERA_GYRO_MOUSE_PER_DEG;
        float dx = targetMouseX - cameraGyroLastMouseX;
        float dy = targetMouseY - cameraGyroLastMouseY;
        cameraGyroLastMouseX = targetMouseX;
        cameraGyroLastMouseY = targetMouseY;

        if (view != null && (Math.abs(dx) > 0.001f || Math.abs(dy) > 0.001f))
            view.addCameraDelta(dx, dy);
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    void setCameraGyroEnabled(boolean enabled) {
        cameraGyroEnabled = enabled;
        getSharedPreferences("trucktilt_settings", MODE_PRIVATE)
                .edit().putBoolean("camera_gyro_enabled", enabled).apply();
        if (!enabled) {
            // Stop camera tracking immediately; steering state is untouched.
            cameraGyroWasActive = false;
            cameraGyroFilterInitialized = false;
            cameraGyroStationarySinceNs = 0L;
        }
        if (view != null) view.invalidate();
    }

    void setHapticsEnabled(boolean enabled) {
        hapticsEnabled = enabled;
        getSharedPreferences("trucktilt_settings", MODE_PRIVATE)
                .edit().putBoolean("haptics_enabled", enabled).apply();
        if (!enabled) stopOffRoadRumble();
        if (view != null) view.invalidate();
    }


    // Telemetry-only HUD parsing; does not touch steering or sensor calculations.
    void handleTelemetryHud(String message) {
        try {
            String[] p = message.trim().split("\\|");
            if (p.length < 5) return;
            telemetryGear = Integer.parseInt(p[1]);
            telemetryRpm = Float.parseFloat(p[2]);
            telemetryRpmMax = Float.parseFloat(p[3]);
            telemetryGearCount = Integer.parseInt(p[4]);
            if (p.length >= 6) telemetrySpeedKmh = Math.max(0f, Float.parseFloat(p[5]));
            if (view != null) view.postInvalidate();
        } catch (Exception ignored) { }
    }

    void handleHapticMessage(String message) {
        if (!hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return;
        final String type = message.trim().toUpperCase(Locale.US);
        runOnUiThread(() -> {
            if (!hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return;
            try {
                if (type.startsWith("RUMBLE|")) {
                    int intensity = 0;
                    try { intensity = Integer.parseInt(type.substring(7).trim()); }
                    catch (Exception ignored) { }
                    startAdaptiveRumble(Math.max(1, Math.min(100, intensity)));
                    return;
                }

                switch (type) {
                    case "SHIFT":
                    case "SHIFT_HINT":
                        vibrateWaveform(new long[]{0, 22, 35, 34}, new int[]{0, 95, 0, 75}, -1);
                        break;
                    case "SHIFT_UP":
                        vibrateWaveform(new long[]{0, 28, 32, 42}, new int[]{0, 105, 0, 80}, -1);
                        break;
                    case "SHIFT_DOWN":
                        vibrateWaveform(new long[]{0, 42, 32, 28}, new int[]{0, 90, 0, 70}, -1);
                        break;
                    case "ENGINE_START":
                        vibrateWaveform(new long[]{0, 45, 25, 65, 35, 105, 55}, new int[]{0, 40, 60, 105, 75, 55, 0}, -1);
                        break;
                    case "ENGINE_STOP":
                        vibrateWaveform(new long[]{0, 85, 40, 65, 60, 35}, new int[]{0, 90, 70, 48, 25, 0}, -1);
                        break;
                    case "RUMBLE_STOP":
                        stopAdaptiveRumble();
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
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

    void startAdaptiveRumble(int intensity) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (adaptiveRumbleIntensity == intensity && offRoadRumbleActive) return;
        adaptiveRumbleIntensity = intensity;
        offRoadRumbleActive = true;

        int a1 = Math.max(8, Math.min(180, 35 + intensity * 2));
        int a2 = Math.max(6, Math.min(150, 20 + intensity));
        int a3 = Math.max(8, Math.min(165, 28 + (int)(intensity * 1.45f)));
        vibrateWaveform(new long[]{0, 150, 75, 115, 65}, new int[]{0, a1, a2, a3, 0}, 0);
    }

    void stopAdaptiveRumble() {
        adaptiveRumbleIntensity = -1;
        offRoadRumbleActive = false;
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Throwable ignored) {}
        }
    }

    void startOffRoadRumble() { startAdaptiveRumble(55); }

    void stopOffRoadRumble() { stopAdaptiveRumble(); }

    int getBatteryPercent() {
        return batteryPercent;
    }

    void openDonate() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)));
        } catch (Exception ignored) {
        }
    }

    void calibrate() {
        // Rebase steering to zero without an instantaneous jump in the displayed
        // wheel. The offset fades out smoothly while new gyro motion is integrated.
        calibrationFadeOffsetDeg = gyroAngle;
        calibrationFadeStartNs = android.os.SystemClock.elapsedRealtimeNanos();
        rawGyroIntegratedDeg = 0f;
        zeroCorrectionOffset = 0f;
        filteredGyroRateDegPerSec = 0f;
        gyroRateFilterInitialized = false;
        lastGyroNs = 0L;
        stationarySinceNs = 0L;

        if (gameRotationVectorSeen) {
            System.arraycopy(gameQuatCurrent, 0, gameQuatReference, 0, 4);
            gameQuatReferenceValid = true;
            fusedRelativeInitialized = true;
            fusedLastRelativeDeg = 0f;
            fusedAccumulatedDeg = 0f;
        } else {
            gameQuatReferenceValid = false;
            fusedRelativeInitialized = false;
            fusedLastRelativeDeg = 0f;
            fusedAccumulatedDeg = 0f;
        }

        if (view != null) {
            view.wheelAngleDeg = 0f;
            view.steer = 0f;
            view.invalidate();
        }
    }

    static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    static float normalizeDelta(float d) {
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    class ControllerView extends View {
        final Paint p = new Paint(3);
        final Paint stroke = new Paint(3);
        final HashMap<String,RectF> controls = new HashMap<>();
        final HashMap<Integer,String> pointerControls = new HashMap<>();
        final boolean[] buttons = new boolean[33];

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
        int mode = 0;
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
                "LEFT MIRROR", "RIGHT MIRROR", "ENTER", "BACK", "INTERIOR CAM"
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

            float controlH = Math.max(44f, Math.min(58f, h * 0.050f));
            float smallControlH = Math.max(42f, Math.min(54f, h * 0.046f));

            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(18,22,26));
            c.drawRect(0,0,w,58,p);
            text(c,"TRUCKTILT",20,37,20,Color.WHITE,Paint.Align.LEFT);
            int battery = getBatteryPercent();
            if (battery >= 0) {
                int batteryColor = battery <= 15 ? Color.rgb(255,100,100)
                        : battery <= 30 ? Color.rgb(255,190,70)
                        : Color.WHITE;
                text(c, "BAT " + battery + "%", w - 305, 36, 13, batteryColor, Paint.Align.RIGHT);
            }
            text(c,"by KRYSLERXCVII  •  v0.16.3",136,37,15,Color.rgb(205,212,220),Paint.Align.LEFT);

            donateButton(c,"DONATE",w-82,29,132,38);

            text(c,net.connected ? "● CONNECTED" : "● SEARCHING",
                    w-168,22,11,net.connected?Color.rgb(139,195,74):Color.rgb(255,180,50),Paint.Align.RIGHT);
            String status=net.connected ? net.rttMs+" ms  •  "+net.rate+" Hz  •  TX "+net.txCount : "Wi-Fi only • auto discovery";
            text(c,status,w-168,42,10,Color.LTGRAY,Paint.Align.RIGHT);

            // Telemetry overlays only: compact speed and RPM panels at bottom-left.
            if (telemetryGear != Integer.MIN_VALUE) {
                float hudBottom = h - 18f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(220, 18, 23, 28));
                c.drawRoundRect(new RectF(14f, h - 76f, 116f, h - 12f), 10f, 10f, p);
                c.drawRoundRect(new RectF(124f, h - 76f, 248f, h - 12f), 10f, 10f, p);
                text(c, String.format(Locale.US, "%.0f", telemetrySpeedKmh), 65f, h - 40f,
                        23f, Color.WHITE, Paint.Align.CENTER);
                text(c, "KM/H", 65f, hudBottom - 1f, 10f, Color.rgb(170, 180, 188), Paint.Align.CENTER);
                text(c, String.format(Locale.US, "%.0f", telemetryRpm), 186f, h - 43f,
                        20f, Color.WHITE, Paint.Align.CENTER);
                text(c, "RPM", 186f, h - 31f, 9f, Color.rgb(170, 180, 188), Paint.Align.CENTER);
                float rpmRatio = telemetryRpmMax > 100f
                        ? clamp(telemetryRpm / telemetryRpmMax, 0f, 1f) : 0f;
                p.setColor(Color.rgb(40, 46, 50));
                c.drawRoundRect(new RectF(136f, h - 24f, 236f, h - 19f), 3f, 3f, p);
                p.setColor(rpmRatio >= 0.86f ? Color.rgb(255, 190, 55)
                        : rpmRatio >= 0.70f ? Color.rgb(100, 190, 255) : Color.rgb(139, 195, 74));
                c.drawRoundRect(new RectF(136f, h - 24f,
                        136f + 100f * rpmRatio, h - 19f), 3f, 3f, p);
            }

            // Shift prompt based on telemetry only.
            if (telemetryGear != Integer.MIN_VALUE) {
                float rpmRatio = telemetryRpmMax > 100f
                        ? clamp(telemetryRpm / telemetryRpmMax, 0f, 1f) : 0f;
                // Use a visible text status at all times while telemetry is active.
                // Shift prompts use wider RPM bands so they are easier to notice.
                // Do not suppress shift-up hints when the host omits gear count.
                // Use wider thresholds so prompts are visible during play.
                boolean hasRpmRange = telemetryRpmMax > 100f;
                // RPM is the primary shift hint. Do not suppress SHIFT UP
                // because game telemetry reports a different gear-count convention.
                boolean shiftUp = hasRpmRange && telemetryGear > 0
                        && rpmRatio >= 0.65f;
                boolean shiftDown = hasRpmRange && telemetryGear > 1
                        && rpmRatio <= 0.30f;
                String gearLabel = telemetryGear < 0 ? "R" : telemetryGear == 0 ? "N" : String.valueOf(telemetryGear);
                int gearColor = shiftUp ? Color.rgb(255,190,55)
                        : shiftDown ? Color.rgb(100,190,255) : Color.WHITE;
                text(c, "GEAR " + gearLabel, w * 0.50f, 18, 11, gearColor, Paint.Align.CENTER);
                String prompt = shiftUp ? "▲ SHIFT UP!"
                        : shiftDown ? "▼ SHIFT DOWN!" : "RPM / HOLD GEAR";
                int promptColor = shiftUp ? Color.rgb(255,190,55)
                        : shiftDown ? Color.rgb(100,190,255) : Color.rgb(170,180,188);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.rgb(24,29,34));
                c.drawRoundRect(new RectF(w*0.50f-65,25,w*0.50f+65,49),7,7,p);
                text(c, prompt, w * 0.50f, 41, 11, promptColor, Paint.Align.CENTER);
                // Small RPM bar remains below the text status.
                p.setColor(Color.rgb(40,46,50));
                c.drawRoundRect(new RectF(w*0.50f-48,51,w*0.50f+48,56),3,3,p);
                p.setColor(rpmRatio >= 0.78f ? Color.rgb(255,190,55)
                        : rpmRatio >= 0.60f ? Color.rgb(100,190,255) : Color.rgb(139,195,74));
                c.drawRoundRect(new RectF(w*0.50f-48,51,w*0.50f-48+96*rpmRatio,56),3,3,p);
            }

            float leftWidth=w*.34f;
            float centerLeft=w*.36f, centerRight=w*.72f;
            float pedalLeft=w*.75f, pedalRight=w*.99f;

            float wheelCx=leftWidth*.50f;
            float wheelCy=h*.56f;
            float radius=Math.min(leftWidth*.39f,h*.34f);
            wheelRect.set(wheelCx-radius,wheelCy-radius,wheelCx+radius,wheelCy+radius);

            button(c,"MODE",wheelCx-88,78,105,38,false);
            button(c,rangeDeg+"°",wheelCx+20,78,88,38,false);
            button(c,"CALIBRATE",wheelCx+111,78,115,38,false);
            button(c,"CUSTOMIZE",wheelCx+215,78,135,38,customizing);
            button(c,hapticsEnabled ? "HAPTICS ON" : "HAPTICS OFF",wheelCx+215,124,135,34,hapticsEnabled);
            String cameraGyroLabel = cameraGyroEnabled ? "CAM GYRO ON" : "CAM GYRO OFF";
            button(c,cameraGyroLabel,wheelCx+215,164,135,34,cameraGyroEnabled);
            if(customizing)
                button(c,"RESET LAYOUT",wheelCx+215,204,135,34,false);

            float quickY=wheelCy-radius-34f;
            float quickW=Math.min(112f, Math.max(92f, w * 0.052f));
            float quickGap=Math.max(16f, w * 0.010f);

            drawMappedButton(c,0,wheelCx-quickW-quickGap,quickY,quickW,controlH); // LEFT
            drawMappedButton(c,5,wheelCx,quickY,quickW,controlH);                 // HORN
            drawMappedButton(c,1,wheelCx+quickW+quickGap,quickY,quickW,controlH); // RIGHT
            drawMappedButton(c,6,wheelCx,wheelCy-radius-82,quickW,smallControlH);      // AIR HORN

            drawWheel(c,wheelCx,wheelCy,radius);

            float colW=(centerRight-centerLeft)/4f;
            float buttonW=Math.min(170f, colW-8f);
            float centerButtonH = controlH;
            float top=86f;
            float rowGap=Math.min(62f,(h*.48f-top)/7f);

            int[] lighting={3,2,4,24};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,lighting[col],x,top+rowGap*.5f,buttonW,centerButtonH);
            }

            int[] cruiseRow={17,13,14,15};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,cruiseRow[col],x,top+rowGap*1.5f,buttonW,centerButtonH);
            }

            int[] wiperRow={16,7,8,9};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,wiperRow[col],x,top+rowGap*2.5f,buttonW,centerButtonH);
            }

            int[] viewRow={28,29,23,20};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,viewRow[col],x,top+rowGap*3.5f,buttonW,centerButtonH);
            }

            int[] utilityRow={21,22,25,26};
            for(int col=0;col<4;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,utilityRow[col],x,top+rowGap*4.5f,buttonW,centerButtonH);
            }

            int[] utilityRow2={27,30,31};
            for(int col=0;col<3;col++) {
                float x=centerLeft+colW*(col+.5f);
                drawMappedButton(c,utilityRow2[col],x,top+rowGap*5.5f,buttonW,centerButtonH);
            }
            // Interior camera: editable like other HUD controls; default is below TRAILER.
            RectF interiorCamRect = new RectF(centerLeft+colW*3.5f-buttonW/2f,
                    top+rowGap*5.5f-centerButtonH/2f,
                    centerLeft+colW*3.5f+buttonW/2f,
                    top+rowGap*5.5f+centerButtonH/2f);
            interiorCamRect = customRect("INTERIOR_CAM", interiorCamRect);
            controls.put("INTERIOR_CAM", new RectF(interiorCamRect));
            button(c, "INTERIOR CAM", interiorCamRect.centerX(), interiorCamRect.centerY(), interiorCamRect.width(), interiorCamRect.height(), false);

            float cameraLeft=centerLeft+8f, cameraRight=centerRight-8f;
            float cameraTop=top+rowGap*7f+14f, cameraBottom=h*.94f;
            if(cameraBottom>cameraTop+70f)
                drawCameraPad(c,cameraLeft,cameraTop,cameraRight,cameraBottom);

            float pw=Math.min(190,(pedalRight-pedalLeft-24)/2f);
            float px1=pedalLeft+(pedalRight-pedalLeft)*.27f;
            float px2=pedalLeft+(pedalRight-pedalLeft)*.73f;

            text(c,"PEDALS",(pedalLeft+pedalRight)/2f,78,14,Color.LTGRAY,Paint.Align.CENTER);
            float py1=h*.37f;
            drawProgressivePedal(c,"THROTTLE",px1,py1,pw,270,throttle);
            drawProgressivePedal(c,"BRAKE",px2,py1,pw,270,brake);

            drawMappedButton(c,18,px1,h*.59f,pw*.88f,controlH); // GEAR UP
            drawMappedButton(c,10,px2,h*.59f,pw*.88f,controlH); // ENGINE BRAKE
            drawMappedButton(c,19,px1,h*.68f,pw*.88f,controlH); // GEAR DOWN
            drawMappedButton(c,11,px2,h*.77f,pw*.88f,controlH); // RETARDER+
            drawMappedButton(c,12,px2,h*.86f,pw*.88f,controlH); // RETARDER-

            drawPedal(c,"CLUTCH",px1,h*.84f,pw*.88f,62,clutch);
            drawPedal(c,"RETARDER",px2,h*.93f,pw*.88f,62,retarder); // Moved below RETARDER- to prevent overlap

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
            c.save(); c.rotate(currentSteeringDegrees(),cx,cy);
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
                p.setColor(Color.rgb(24,29,34));
                c.drawRoundRect(new RectF(r.left,r.top,r.right,fillTop),12,12,p);
                p.setColor(Color.rgb(139,195,74));
                c.drawRect(r.left,fillTop,r.right,r.bottom,p);
            }

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
                String cameraGyroLabel = cameraGyroEnabled ? "CAM GYRO ON" : "CAM GYRO OFF";
                if(hit(cameraGyroLabel,x,y)){
                    setCameraGyroEnabled(!cameraGyroEnabled);
                    return true;
                }
                if(!customizing && hit("INTERIOR CAM",x,y)){
                    // Independent 33rd DirectInput wheel button; CAMERA (index 20) is untouched.
                    buttons[32]=true;
                    pointerControls.put(pointerId,"B32");
                    if(hapticsEnabled) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    invalidate();
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
                            float minW=40f, minH=32f;
                            float right=clamp(editStartRect.right+dx,
                                    editStartRect.left+minW,getWidth()-8f);
                            float bottom=clamp(editStartRect.bottom+dy,
                                    editStartRect.top+minH,getHeight()-8f);
                            r.set(editStartRect.left,editStartRect.top,right,bottom);
                        } else {
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

                    float maxAngle=rangeDeg/2f;
                    wheelAngleDeg=clamp(
                            wheelAngleDeg + delta,
                            -maxAngle,
                            maxAngle
                    );
                    steer=clamp(wheelAngleDeg/maxAngle,-1f,1f);
                    invalidate();
                }
            }

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
            return key != null && (key.startsWith("B") || key.startsWith("P_") || "CAMERA_PAD".equals(key) || "INTERIOR_CAM".equals(key));
        }

        String findEditableKey(float x,float y) {
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
            RectF interior=controls.get("INTERIOR_CAM");
            if(interior!=null && interior.contains(x,y)) return "INTERIOR_CAM";
            return null;
        }

        boolean isResizeHandle(RectF r,float x,float y) {
            return r!=null && x>=r.right-44f && y>=r.bottom-44f;
        }

        RectF customRect(String key,RectF def) {
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

        // Virtual-wheel self-centering at a steady angular speed.
        // Unlike exponential decay, the wheel does not return quickly at large
        // angles and then progressively slow down; degrees-per-second stays
        // constant, independent of frame rate and selected steering range.
        static final float WHEEL_RETURN_SPEED_DEG_PER_SEC = 240f;
        static final float WHEEL_RETURN_EASE_ZONE_DEG = 8f;
        static final float WHEEL_RETURN_SETTLE_TIME_SEC = 0.07f;
        static final float WHEEL_RETURN_STOP_DEG = 0.12f;

        void startWheelReturn(){
            if(Math.abs(wheelAngleDeg) <= WHEEL_RETURN_STOP_DEG){
                wheelAngleDeg=0f;
                steer=0f;
                wheelReturning=false;
                invalidate();
                return;
            }

            wheelReturning=true;
            postOnAnimation(new Runnable(){
                long lastFrameNs = 0L;

                @Override public void run(){
                    if(!wheelReturning || wheelDragging) return;

                    long nowNs = android.os.SystemClock.elapsedRealtimeNanos();
                    if(lastFrameNs == 0L){
                        lastFrameNs = nowNs;
                        postOnAnimation(this);
                        return;
                    }

                    float dt = (nowNs - lastFrameNs) / 1_000_000_000f;
                    lastFrameNs = nowNs;
                    // Clamp long frame gaps to prevent jumps after a pause.
                    dt = clamp(dt, 0f, 0.05f);
                    float absAngle = Math.abs(wheelAngleDeg);
                    if(absAngle <= WHEEL_RETURN_STOP_DEG){
                        wheelAngleDeg=0f;
                        steer=0f;
                        wheelReturning=false;
                        invalidate();
                        return;
                    }

                    if(absAngle > WHEEL_RETURN_EASE_ZONE_DEG){
                        // Keep a steady rotation rate through most of the return.
                        float step = Math.min(absAngle, WHEEL_RETURN_SPEED_DEG_PER_SEC * dt);
                        wheelAngleDeg -= Math.signum(wheelAngleDeg) * step;
                    } else {
                        // Ease only through the final few degrees to avoid a visible
                        // last-frame snap while keeping the main return speed uniform.
                        float settle = (float)(1.0 - Math.exp(-dt / WHEEL_RETURN_SETTLE_TIME_SEC));
                        wheelAngleDeg *= (1f - settle);
                    }

                    if(Math.abs(wheelAngleDeg) <= WHEEL_RETURN_STOP_DEG){
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
            return clamp((r.bottom-y)/r.height(),0f,1f);
        }

        void addCameraDelta(float dx, float dy){
            synchronized(cameraSync){
                cameraDx += dx;
                cameraDy += dy;
            }
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

        static float brakeResponse(float v) {
            v = clamp(v, 0f, 1f);
            // More progressive brake: finer control in the first half,
            // reaching full braking only at full pedal travel.
            return (float)Math.pow(v, 2.20);
        }

        static float throttleResponse(float v) {
            v = clamp(v, 0f, 1f);
            // More progressive throttle: reduces early acceleration buildup.
            return (float)Math.pow(v, 1.80);
        }

        void setPedal(String s,float v){
            v = clamp(v,0f,1f);
            if("CLUTCH".equals(s)) clutch=v;
            else if("BRAKE".equals(s)) brake=brakeResponse(v);
            else if("THROTTLE".equals(s)) throttle=throttleResponse(v);
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
                    else if(s.startsWith("HUD|")){
                        handleTelemetryHud(s);
                    } else if(s.startsWith("HAPTIC|")){
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
