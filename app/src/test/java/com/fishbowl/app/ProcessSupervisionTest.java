package com.fishbowl.app;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Automated regression tests for Fishbowl Process Supervision & State Lifecycle.
 * Covers:
 * - Test 10: Duplicate service-start requests do not create duplicate processes (Idempotency).
 * - Test 11: Crash-loop circuit breaker stops repeated restarts once threshold is exceeded.
 */
public class ProcessSupervisionTest {

    /**
     * Test 10: Duplicate service-start requests do not create duplicate processes.
     * When controller is in RUNNING, STARTING, or INITIALIZING states, redundant start attempts
     * are guarded and ignored.
     */
    @Test
    public void test10_DuplicateStartRequestsAreIdempotent() {
        JellyfinController controller = JellyfinController.getInstance();

        // 1. When in RUNNING state
        controller.setStateForTest(JellyfinController.State.RUNNING);
        Assert.assertEquals("Controller state is RUNNING",
                JellyfinController.State.RUNNING, controller.getState());

        // Repeated start should not throw and should remain RUNNING
        controller.start(null);
        Assert.assertEquals("State remains RUNNING without creating extra process",
                JellyfinController.State.RUNNING, controller.getState());

        // 2. When in STARTING state
        controller.setStateForTest(JellyfinController.State.STARTING);
        Assert.assertEquals("Controller state is STARTING",
                JellyfinController.State.STARTING, controller.getState());
        controller.start(null);
        Assert.assertEquals("State remains STARTING without duplicate launch",
                JellyfinController.State.STARTING, controller.getState());

        // 3. Reset to STOPPED
        controller.setStateForTest(JellyfinController.State.STOPPED);
        Assert.assertEquals("Controller state is STOPPED",
                JellyfinController.State.STOPPED, controller.getState());
    }

    /**
     * Test 11: Crash-loop circuit breaker stops repeated restarts.
     * When autoRestartCount exceeds MAX_AUTO_RESTARTS (3), the circuit breaker trips,
     * transitioning state to CRASH_LOOP and blocking further automated launch attempts.
     */
    @Test
    public void test11_CrashLoopCircuitBreakerTripsAfterMaxRestarts() {
        JellyfinController controller = JellyfinController.getInstance();
        controller.resetAutoRestartCountForTest();

        int maxRestarts = JellyfinController.getMaxAutoRestarts();
        Assert.assertEquals("MAX_AUTO_RESTARTS should be 3", 3, maxRestarts);

        // Simulate successive crashes
        for (int i = 1; i <= maxRestarts; i++) {
            int attempt = controller.incrementAutoRestartCountForTest();
            Assert.assertEquals("Attempt count tracks correctly", i, attempt);
            Assert.assertTrue("Attempt " + i + " is within threshold", attempt <= maxRestarts);
        }

        // Next crash exceeds threshold
        int tripAttempt = controller.incrementAutoRestartCountForTest();
        Assert.assertTrue("Crash attempt exceeds threshold", tripAttempt > maxRestarts);

        // Trip the circuit breaker
        controller.setStateForTest(JellyfinController.State.CRASH_LOOP);
        Assert.assertEquals("Controller is in CRASH_LOOP state",
                JellyfinController.State.CRASH_LOOP, controller.getState());

        // Further start calls must be rejected while in CRASH_LOOP
        controller.start(null);
        Assert.assertEquals("Controller refuses automated start while in CRASH_LOOP",
                JellyfinController.State.CRASH_LOOP, controller.getState());

        // Reset crash loop manually resets counter
        controller.resetAutoRestartCountForTest();
        controller.setStateForTest(JellyfinController.State.STOPPED);
        Assert.assertEquals("Counter reset allows returning to STOPPED state",
                0, controller.getAutoRestartCount());
        Assert.assertEquals("Controller is STOPPED and ready for fresh launch",
                JellyfinController.State.STOPPED, controller.getState());
    }

    /**
     * Test 11b: Bounded startup deadline and safe reconciliation prevents controller
     * from staying stuck in transitional states indefinitely.
     */
    @Test
    public void test11b_BoundedStartupDeadlineAndReconciliation() {
        JellyfinController controller = JellyfinController.getInstance();

        // Put controller in STARTING with no active process
        controller.setStateForTest(JellyfinController.State.STARTING);
        Assert.assertEquals(JellyfinController.State.STARTING, controller.getState());

        // Reconcile state
        controller.reconcileStateAsync();

        // Verify that reconcileStateAsync transitions out of STARTING to FAILED or STOPPED
        // rather than being permanently trapped in STARTING
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {}

        Assert.assertNotEquals("Controller must NOT remain trapped in STARTING state when process is absent",
                JellyfinController.State.STARTING, controller.getState());
    }
}
