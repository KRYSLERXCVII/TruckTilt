package com.trucktilt.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SteeringDriftObserverTest {
    private static float[] gravity(float steeringAngleDeg) {
        double angle = Math.toRadians(steeringAngleDeg);
        return new float[]{
                (float) (-9.81 * Math.sin(angle)),
                (float) (9.81 * Math.cos(angle)),
                0f
        };
    }

    @Test public void preservesContinuousAngleAcross900DegreeRange() {
        SteeringDriftObserver observer = new SteeringDriftObserver();
        assertTrue(observer.calibrate(gravity(0f), 0f, 1L));
        float positive = 450f;
        float negative = -450f;
        for (int i = 1; i <= 100; i++) {
            long timestamp = 1L + i * 20_000_000L;
            positive = observer.correct(positive, gravity(90f),
                    new float[]{0f, 0f, 0f}, timestamp);
        }
        assertEquals(450f, positive, 0.001f);

        observer.reset();
        assertTrue(observer.calibrate(gravity(0f), 0f, 1L));
        for (int i = 1; i <= 100; i++) {
            long timestamp = 1L + i * 20_000_000L;
            negative = observer.correct(negative, gravity(-90f),
                    new float[]{0f, 0f, 0f}, timestamp);
        }
        assertEquals(-450f, negative, 0.001f);
    }

    @Test public void correctsDriftAtNonZeroHeldSteeringAngleWithoutRecentering() {
        SteeringDriftObserver observer = new SteeringDriftObserver();
        assertTrue(observer.calibrate(gravity(0f), 0f, 1L));
        float angle = 130f;
        long timestamp = 1L;
        for (int i = 0; i < 300; i++) {
            timestamp += 20_000_000L;
            angle = observer.correct(angle, gravity(100f),
                    new float[]{0f, 0f, 0f}, timestamp);
        }
        assertEquals(100f, angle, 0.2f);
        assertTrue("A held 100-degree position must not be pulled to zero", angle > 90f);
    }

    @Test public void rejectsLinearAccelerationAndScreenNormalTilt() {
        SteeringDriftObserver observer = new SteeringDriftObserver();
        assertTrue(observer.calibrate(gravity(0f), 0f, 1L));
        float angle = 130f;
        float unchanged = observer.correct(angle, gravity(100f),
                new float[]{3f, 0f, 0f}, 20_000_001L);
        assertEquals(angle, unchanged, 0.001f);

        unchanged = observer.correct(angle, new float[]{-9.81f, 0f, 3f},
                new float[]{0f, 0f, 0f}, 40_000_001L);
        assertEquals(angle, unchanged, 0.001f);
    }

    @Test public void fallsBackToGyroWhenNoReferenceExists() {
        SteeringDriftObserver observer = new SteeringDriftObserver();
        assertEquals(123f, observer.correct(123f, gravity(0f),
                new float[]{0f, 0f, 0f}, 100L), 0f);
    }

    @Test public void boundsCorrectionRate() {
        SteeringDriftObserver observer = new SteeringDriftObserver();
        assertTrue(observer.calibrate(gravity(0f), 0f, 200L));
        float corrected = observer.correct(120f, gravity(0f),
                new float[]{0f, 0f, 0f}, 220_000_200L);
        assertTrue(Math.abs(corrected - 120f) <= 1.201f);
    }

    @Test public void rebasesAfterPauseWithoutChangingSteeringAngle() {
        SteeringDriftObserver observer = new SteeringDriftObserver();
        assertTrue(observer.calibrate(gravity(0f), 0f, 1L));
        assertTrue(observer.calibrate(gravity(45f), 225f, 300_000_000L));
        float corrected = observer.correct(225f, gravity(45f),
                new float[]{0f, 0f, 0f}, 320_000_000L);
        assertEquals(225f, corrected, 0.001f);
    }
}
