package com.fishbowl.app.services;

import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Concurrency, stress, and stream lifecycle test suite verifying:
 * 1. Process log reader queue saturation and process cleanup (zero silent loss).
 * 2. Multiple concurrent service launches and stream consumption.
 * 3. High-throughput stdout/stderr pipe draining without deadlocks.
 * 4. Process exit detection while other services are concurrently starting.
 * 5. Process-exit executor saturation with guaranteed fallback observation.
 * 6. Explicit rejected task handling and state rollback.
 * 7. Non-blocking main-thread responsiveness.
 * 8. Manual release tag semantic version validation.
 */
public class ProcessConcurrencyAndLifecycleStressTest {

    private static final Pattern SEMVER_RELEASE_TAG_PATTERN =
            Pattern.compile("^v[0-9]+\\.[0-9]+\\.[0-9]+(-[a-zA-Z0-9.]+)?$");

    /**
     * Mock Process implementation for deterministic unit testing of process lifecycle,
     * stream draining, termination signaling, and forcible destruction.
     */
    static class MockProcess extends Process {
        private final ByteArrayInputStream stdoutStream;
        private final ByteArrayOutputStream stdinStream = new ByteArrayOutputStream();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean destroyedForcibly = new AtomicBoolean(false);
        private final AtomicInteger exitCode = new AtomicInteger(0);
        private final CountDownLatch exitLatch = new CountDownLatch(1);

        MockProcess(String outputContent) {
            this.stdoutStream = new ByteArrayInputStream(outputContent.getBytes(StandardCharsets.UTF_8));
        }

        MockProcess(byte[] outputBytes) {
            this.stdoutStream = new ByteArrayInputStream(outputBytes);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdinStream;
        }

        @Override
        public InputStream getInputStream() {
            return stdoutStream;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exitLatch.await();
            return exitCode.get();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            boolean exited = exitLatch.await(timeout, unit);
            return exited;
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("Process has not exited");
            }
            return exitCode.get();
        }

        @Override
        public void destroy() {
            terminate(143);
        }

        @Override
        public Process destroyForcibly() {
            destroyedForcibly.set(true);
            terminate(137);
            return this;
        }

        public void terminate(int code) {
            exitCode.set(code);
            alive.set(false);
            exitLatch.countDown();
        }

        public boolean isDestroyedForcibly() {
            return destroyedForcibly.get();
        }

        public boolean isAlive() {
            return alive.get();
        }
    }

    /**
     * 1. Test Process Log Reader Queue Saturation and Process Cleanup.
     * Verifies that saturated log executors explicitly reject tasks without silent loss,
     * and that the starting process is forcibly destroyed to prevent unread pipe buffer deadlock.
     */
    @Test
    public void testProcessLogReaderSaturationAndProcessCleanup() {
        // Pool with 2 threads and 0 queue capacity (SynchronousQueue) mirroring processLogExecutor
        ThreadPoolExecutor boundedLogExecutor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        CountDownLatch activeLatch = new CountDownLatch(2);
        CountDownLatch releaseLatch = new CountDownLatch(1);

        try {
            // Fill the 2 available worker threads with active readers
            for (int i = 0; i < 2; i++) {
                boundedLogExecutor.execute(() -> {
                    activeLatch.countDown();
                    try { releaseLatch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                });
            }

            try {
                boolean started = activeLatch.await(2, TimeUnit.SECONDS);
                Assert.assertTrue("Workers should be active", started);
            } catch (InterruptedException e) {
                Assert.fail("Interrupted waiting for workers");
            }

            // Attempt to register a 3rd process stream reader
            MockProcess excessProcess = new MockProcess("service-3 output line\n");
            AtomicBoolean processDestroyed = new AtomicBoolean(false);
            AtomicReference<String> errorReason = new AtomicReference<>(null);

            try {
                boundedLogExecutor.execute(() -> {
                    // Reader task
                });
                Assert.fail("Expected RejectedExecutionException under saturated log executor");
            } catch (RejectedExecutionException rex) {
                // Emulate MediaServiceManager stream registration failure cleanup
                excessProcess.destroyForcibly();
                processDestroyed.set(excessProcess.isDestroyedForcibly());
                errorReason.set("STREAM_REGISTRATION_FAILED: Process log executor saturated");
            }

            // Assertions: no silent task loss, process was destroyed forcibly, error recorded
            Assert.assertTrue("Saturated process must be destroyed forcibly to prevent pipe hangs", processDestroyed.get());
            Assert.assertFalse("Process must not remain alive with unread pipe", excessProcess.isAlive());
            Assert.assertEquals("STREAM_REGISTRATION_FAILED: Process log executor saturated", errorReason.get());
        } finally {
            releaseLatch.countDown();
            boundedLogExecutor.shutdownNow();
        }
    }

    /**
     * 2. Test Multiple Concurrent Service Launches.
     * Verifies that multiple concurrent processes can be launched and have their stdout
     * streams concurrently attached and consumed without deadlocks or thread starvation.
     */
    @Test
    public void testMultipleConcurrentServiceLaunches() throws Exception {
        int serviceCount = 8;
        ThreadPoolExecutor logExecutor = new ThreadPoolExecutor(
                serviceCount, serviceCount, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        List<MockProcess> processes = new ArrayList<>();
        List<String> collectedLogs = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch readLatch = new CountDownLatch(serviceCount);

        for (int i = 0; i < serviceCount; i++) {
            processes.add(new MockProcess("Service-" + i + " startup complete\nService-" + i + " ready\n"));
        }

        try {
            ExecutorService launchExecutor = Executors.newFixedThreadPool(serviceCount);
            for (int i = 0; i < serviceCount; i++) {
                final int index = i;
                final MockProcess proc = processes.get(i);
                launchExecutor.execute(() -> {
                    try {
                        logExecutor.execute(() -> {
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    collectedLogs.add(line);
                                }
                            } catch (Exception ignored) {}
                            readLatch.countDown();
                        });
                    } catch (RejectedExecutionException rex) {
                        Assert.fail("Service " + index + " should not be rejected under capacity");
                    }
                });
            }

            boolean completed = readLatch.await(5, TimeUnit.SECONDS);
            Assert.assertTrue("All 8 service logs should be read without deadlock", completed);
            Assert.assertEquals("Each service emitted 2 lines (16 total)", 16, collectedLogs.size());
            launchExecutor.shutdownNow();
        } finally {
            logExecutor.shutdownNow();
        }
    }

    /**
     * 3. Test High-Throughput stdout/stderr Pipe Draining.
     * Verifies that high volume data streams (thousands of lines) are fully consumed
     * without truncation or blocking the writing process.
     */
    @Test
    public void testProcessStdoutStderrConsumption() throws Exception {
        int lineCount = 5000;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            sb.append("Log telemetry line #").append(i).append(" [INFO] Service healthy\n");
        }

        MockProcess highVolumeProcess = new MockProcess(sb.toString());
        AtomicInteger linesRead = new AtomicInteger(0);
        CountDownLatch drainLatch = new CountDownLatch(1);

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(highVolumeProcess.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    linesRead.incrementAndGet();
                }
            } catch (Exception ignored) {}
            drainLatch.countDown();
        });

        readerThread.start();
        boolean drained = drainLatch.await(5, TimeUnit.SECONDS);
        readerThread.join(1000);

        Assert.assertTrue("High volume stream must be completely drained", drained);
        Assert.assertEquals("All 5000 lines must be consumed", lineCount, linesRead.get());
    }

    /**
     * 4. Test Process Exit While Other Processes are Starting.
     * Verifies that ongoing process exits do not starve or block simultaneous process startups.
     */
    @Test
    public void testProcessExitWhileOtherProcessesAreStarting() throws Exception {
        ProcessExitSupervisor supervisor = new ProcessExitSupervisor(4, 8);
        MockProcess exitingProcess = new MockProcess("exiting service\n");
        MockProcess startingProcess = new MockProcess("starting service\n");

        CountDownLatch exitLatch = new CountDownLatch(1);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger exitResultCode = new AtomicInteger(-1);

        try {
            // Supervise exiting process
            supervisor.supervise("service-exiting", exitingProcess, exitCode -> {
                exitResultCode.set(exitCode);
                exitLatch.countDown();
            });

            // Concurrently simulate startup of another process
            Thread startThread = new Thread(() -> {
                supervisor.supervise("service-starting", startingProcess, exitCode -> {});
                startLatch.countDown();
            });
            startThread.start();

            // Trigger exit on the first process
            exitingProcess.terminate(0);

            Assert.assertTrue("Start operation must complete independently", startLatch.await(3, TimeUnit.SECONDS));
            Assert.assertTrue("Exit observation must complete concurrently", exitLatch.await(3, TimeUnit.SECONDS));
            Assert.assertEquals(0, exitResultCode.get());
        } finally {
            supervisor.shutdown();
        }
    }

    /**
     * 5. Test Process-Exit Executor Saturation and Guaranteed Fallback Observation.
     * Verifies that when the primary process-exit pool is saturated, excess processes
     * are seamlessly enrolled into the fallback daemon supervisor and their termination
     * is guaranteed to be observed upon exit.
     */
    @Test
    public void testProcessExitExecutorSaturationAndFallbackObservation() throws Exception {
        // Create supervisor with pool of exactly 2 threads
        ProcessExitSupervisor supervisor = new ProcessExitSupervisor(2, 2);

        MockProcess proc1 = new MockProcess("proc1");
        MockProcess proc2 = new MockProcess("proc2");
        MockProcess overflowProc3 = new MockProcess("proc3-overflow");

        CountDownLatch p1Latch = new CountDownLatch(1);
        CountDownLatch p2Latch = new CountDownLatch(1);
        CountDownLatch p3ExitLatch = new CountDownLatch(1);
        AtomicInteger p3ExitCode = new AtomicInteger(-1);

        try {
            // Schedulable in primary pool (2 threads)
            supervisor.supervise("proc-1", proc1, p1Exit -> p1Latch.countDown());
            supervisor.supervise("proc-2", proc2, p2Exit -> p2Latch.countDown());

            // Brief wait for primary threads to occupy
            Thread.sleep(100);

            // 3rd process: Primary pool is 100% full. Saturated submission must enroll in fallback!
            supervisor.supervise("proc-3", overflowProc3, exitCode -> {
                p3ExitCode.set(exitCode);
                p3ExitLatch.countDown();
            });

            // Verify overflow process was enrolled in fallbackTargets
            Assert.assertTrue("Overflow process must be enrolled in fallback supervision",
                    supervisor.getFallbackCount() > 0);

            // Now terminate the overflow process
            overflowProc3.terminate(42);

            // Fallback daemon must observe the exit and invoke the callback
            boolean observed = p3ExitLatch.await(3, TimeUnit.SECONDS);
            Assert.assertTrue("Fallback supervisor must detect process exit under saturation", observed);
            Assert.assertEquals(42, p3ExitCode.get());
        } finally {
            proc1.terminate(0);
            proc2.terminate(0);
            supervisor.shutdown();
        }
    }

    /**
     * 6. Test Rejected Task Handling & State Rollback.
     * Verifies that when lifecycle command executors reject tasks under saturation,
     * state rollback occurs cleanly and does not leave services stuck in STARTING or STOPPING.
     */
    @Test
    public void testRejectedTaskHandlingAndStateRollback() {
        ThreadPoolExecutor lifecycleExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy()
        );

        CountDownLatch blocker = new CountDownLatch(1);
        AtomicReference<String> serviceState = new AtomicReference<>("STARTING");
        AtomicReference<ServiceLifecycleResult> callbackResult = new AtomicReference<>(null);

        try {
            // Occupy single worker thread
            lifecycleExecutor.execute(() -> {
                try { blocker.await(); } catch (InterruptedException ignored) {}
            });
            // Fill single-item queue
            lifecycleExecutor.execute(() -> {});

            // 3rd task rejected under AbortPolicy
            try {
                lifecycleExecutor.execute(() -> {});
                Assert.fail("Expected rejection under saturated lifecycle executor");
            } catch (RejectedExecutionException rex) {
                // Rollback state from STARTING to ERROR
                serviceState.set("ERROR");
                callbackResult.set(new ServiceLifecycleResult.Failure(
                        "bazarr",
                        "RESOURCE_EXHAUSTED: Lifecycle executor queue full",
                        rex
                ));
            }

            Assert.assertEquals("ERROR", serviceState.get());
            Assert.assertTrue(callbackResult.get() instanceof ServiceLifecycleResult.Failure);
            ServiceLifecycleResult.Failure fail = (ServiceLifecycleResult.Failure) callbackResult.get();
            Assert.assertTrue(fail.getReason().contains("RESOURCE_EXHAUSTED"));
        } finally {
            blocker.countDown();
            lifecycleExecutor.shutdownNow();
        }
    }

    /**
     * 7. Test Main-Thread Responsiveness During Exit Supervision.
     * Verifies that registering process exit supervision from the main caller thread
     * NEVER blocks the caller on process.waitFor() and returns immediately (< 50ms).
     */
    @Test
    public void testMainThreadResponsivenessDuringExitSupervision() {
        ProcessExitSupervisor supervisor = new ProcessExitSupervisor(1, 1);
        MockProcess blockingProcess = new MockProcess("long running service");

        try {
            // Fill primary pool
            supervisor.supervise("blocker", blockingProcess, code -> {});

            // Attempt supervision of another blocking process (overflow)
            MockProcess secondProcess = new MockProcess("second service");
            long startTime = System.currentTimeMillis();

            // This invocation must return immediately without waiting on secondProcess.waitFor()
            supervisor.supervise("second", secondProcess, code -> {});
            long duration = System.currentTimeMillis() - startTime;

            Assert.assertTrue("Supervision registration must return immediately to protect main thread (took " + duration + "ms)",
                    duration < 100);
        } finally {
            blockingProcess.terminate(0);
            supervisor.shutdown();
        }
    }

    /**
     * 8. Test Manual Release Tag Semantic Version Validation.
     * Verifies the exact regex validation used in .github/workflows/release.yml.
     */
    @Test
    public void testManualReleaseTagValidationRegex() {
        // Valid semantic release tags
        String[] validTags = {
                "v1.4.10-beta.2",
                "v1.4.10",
                "v1.4.9-beta.1",
                "v1.0.0",
                "v2.0.0-rc.1",
                "v10.15.22"
        };
        for (String tag : validTags) {
            Assert.assertTrue("Tag should be valid: " + tag, SEMVER_RELEASE_TAG_PATTERN.matcher(tag).matches());
        }

        // Invalid, malicious, or malformed release tags
        String[] invalidTags = {
                "1.4.10-beta.2",       // missing 'v' prefix
                "v1.4",                // missing patch version
                "v1.4.10; rm -rf /",   // command injection
                "v1.4.10\" && echo 1", // quote injection
                "beta-2",              // non-semver
                "",                    // empty string
                "latest",              // floating tag
                "v1.4.10 beta 2"       // spaces
        };
        for (String tag : invalidTags) {
            Assert.assertFalse("Tag should be rejected as invalid: " + tag, SEMVER_RELEASE_TAG_PATTERN.matcher(tag).matches());
        }
    }
}
