package com.trucktilt.app;

/**
 * Gravity-referenced correction for continuous gyro-integrated steering.
 * The gyroscope retains revolution count; gravity supplies a modulo-360 physical
 * orientation reference and only applies a bounded correction when trustworthy.
 */
public final class SteeringDriftObserver {
    static final float MIN_GRAVITY_NORM = 8.0f;
    static final float MAX_GRAVITY_NORM = 11.5f;
    static final float MIN_SCREEN_PLANE_PROJECTION = 3.0f;
    static final float MAX_STEERING_AXIS_COMPONENT_DELTA = 2.0f;
    static final float MAX_LINEAR_ACCELERATION = 2.5f;
    static final float MAX_UNAMBIGUOUS_ERROR_DEG = 135.0f;
    static final float PROPORTIONAL_GAIN_PER_SEC = 1.4f;
    static final float MAX_CORRECTION_RATE_DEG_PER_SEC = 12.0f;
    static final long MAX_CORRECTION_DT_NS = 100_000_000L;

    private float[] referenceGravity;
    private float angleAtReferenceDeg;
    private long lastReferenceTimestampNs;

    public void reset() {
        referenceGravity = null;
        angleAtReferenceDeg = 0f;
        lastReferenceTimestampNs = 0L;
    }

    /** Captures the current physical pose while preserving its continuous angle. */
    public boolean calibrate(float[] gravity, float continuousAngleAtReferenceDeg,
                             long timestampNs) {
        if (!isGravityUsable(gravity)
                || Math.hypot(gravity[0], gravity[1]) < MIN_SCREEN_PLANE_PROJECTION) {
            reset();
            return false;
        }
        referenceGravity = gravity.clone();
        angleAtReferenceDeg = continuousAngleAtReferenceDeg;
        lastReferenceTimestampNs = timestampNs;
        return true;
    }

    /** Returns the corrected continuous angle; never resets it toward zero. */
    public float correct(float continuousGyroAngleDeg, float[] gravity,
                         float[] linearAcceleration, long timestampNs) {
        if (referenceGravity == null || !isGravityUsable(gravity)
                || !isLinearAccelerationUsable(linearAcceleration)
                || timestampNs <= lastReferenceTimestampNs) {
            return continuousGyroAngleDeg;
        }

        float currentProjection = (float) Math.hypot(gravity[0], gravity[1]);
        if (currentProjection < MIN_SCREEN_PLANE_PROJECTION
                || Math.abs(gravity[2] - referenceGravity[2])
                    > MAX_STEERING_AXIS_COMPONENT_DELTA) {
            lastReferenceTimestampNs = timestampNs;
            return continuousGyroAngleDeg;
        }

        // Signed angle of gravity projected into the screen plane. Android's
        // device Z axis is normal to the screen; this sign matches -gyroZ.
        float cross = referenceGravity[0] * gravity[1]
                - referenceGravity[1] * gravity[0];
        float dot = referenceGravity[0] * gravity[0]
                + referenceGravity[1] * gravity[1];
        float referenceAngleDeg = (float) Math.toDegrees(Math.atan2(cross, dot));
        float expectedAngleDeg = angleAtReferenceDeg + referenceAngleDeg;
        float errorDeg = wrapDegrees(expectedAngleDeg - continuousGyroAngleDeg);

        long elapsedNs = timestampNs - lastReferenceTimestampNs;
        lastReferenceTimestampNs = timestampNs;
        if (Math.abs(errorDeg) > MAX_UNAMBIGUOUS_ERROR_DEG) {
            return continuousGyroAngleDeg;
        }

        float dt = Math.min(elapsedNs, MAX_CORRECTION_DT_NS) / 1_000_000_000f;
        float correction = errorDeg * PROPORTIONAL_GAIN_PER_SEC * dt;
        float maxStep = MAX_CORRECTION_RATE_DEG_PER_SEC * dt;
        correction = clamp(correction, -maxStep, maxStep);
        return continuousGyroAngleDeg + correction;
    }

    public boolean hasReference() {
        return referenceGravity != null;
    }

    static float wrapDegrees(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static boolean isGravityUsable(float[] gravity) {
        if (gravity == null || gravity.length < 3) return false;
        float norm = (float) Math.sqrt(gravity[0] * gravity[0]
                + gravity[1] * gravity[1] + gravity[2] * gravity[2]);
        return norm >= MIN_GRAVITY_NORM && norm <= MAX_GRAVITY_NORM;
    }

    private static boolean isLinearAccelerationUsable(float[] acceleration) {
        if (acceleration == null || acceleration.length < 3) return false;
        float norm = (float) Math.sqrt(acceleration[0] * acceleration[0]
                + acceleration[1] * acceleration[1] + acceleration[2] * acceleration[2]);
        return norm <= MAX_LINEAR_ACCELERATION;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
