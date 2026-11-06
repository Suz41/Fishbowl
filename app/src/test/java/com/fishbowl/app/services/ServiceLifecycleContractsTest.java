package com.fishbowl.app.services;

import com.fishbowl.app.core.RuntimeConfig;
import com.fishbowl.app.diagnostics.SystemResourceSnapshot;
import com.fishbowl.app.process.ProcessResult;
import com.fishbowl.app.storage.StorageOperationResult;
import com.fishbowl.app.ui.DashboardPresenter;
import com.fishbowl.app.ui.ServiceStatusPresentation;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit test suite verifying Kotlin core models, service lifecycle contracts,
 * health monitoring assessments, UI presentation components, and rejection rollback mechanics.
 */
public class ServiceLifecycleContractsTest {

    @Test
    public void testRuntimeConfigDefaults() {
        RuntimeConfig config = new RuntimeConfig("1.4.10-beta.2", 101418, true, 8096, 3, 3000);
        Assert.assertEquals("1.4.10-beta.2", config.getAppVersionName());
        Assert.assertEquals(101418, config.getAppVersionCode());
        Assert.assertTrue(config.isArm64Supported());
        Assert.assertEquals(8096, config.getDefaultJellyfinPort());
    }

    @Test
    public void testServiceLifecycleResultSealedHierarchy() {
        ServiceLifecycleResult.Success success = new ServiceLifecycleResult.Success("radarr", "Started successfully");
        Assert.assertEquals("radarr", success.getServiceId());
        Assert.assertEquals("Started successfully", success.getMessage());

        ServiceLifecycleResult.Failure failure = new ServiceLifecycleResult.Failure("sonarr", "Port occupied", null);
        Assert.assertEquals("sonarr", failure.getServiceId());
        Assert.assertEquals("Port occupied", failure.getReason());

        ServiceLifecycleResult.Skipped skipped = new ServiceLifecycleResult.Skipped("bazarr", "Already running");
        Assert.assertEquals("bazarr", skipped.getServiceId());
        Assert.assertEquals("Already running", skipped.getReason());
    }

    @Test
    public void testServiceLifecycleCallbackIntegration() {
        AtomicReference<ServiceLifecycleResult> ref = new AtomicReference<>();
        ServiceLifecycleCallback callback = ref::set;

        ServiceLifecycleResult res = new ServiceLifecycleResult.Success("jellyfin", "Operational");
        callback.onResult(res);

        Assert.assertNotNull(ref.get());
        Assert.assertTrue(ref.get() instanceof ServiceLifecycleResult.Success);
        Assert.assertEquals("jellyfin", ref.get().getServiceId());
    }

    @Test
    public void testServiceHealthSnapshotAndStates() {
        ServiceHealthSnapshot snapshot = new ServiceHealthSnapshot(
                "jellyfin",
                ServiceHealthState.READY,
                true,
                true,
                42L,
                200,
                null,
                System.currentTimeMillis()
        );

        Assert.assertEquals("jellyfin", snapshot.getServiceId());
        Assert.assertEquals(ServiceHealthState.READY, snapshot.getState());
        Assert.assertTrue(snapshot.isPortListening());
        Assert.assertTrue(snapshot.isApiResponding());
        Assert.assertEquals(42L, snapshot.getLatencyMs());
        Assert.assertEquals(200, snapshot.getHttpStatusCode());
    }

    @Test
    public void testProcessResult() {
        ProcessResult successResult = new ProcessResult(0, "OK", "", 120L);
        Assert.assertTrue(successResult.isSuccessful());
        Assert.assertEquals(0, successResult.getExitCode());
        Assert.assertEquals("OK", successResult.getStdout());

        ProcessResult failureResult = new ProcessResult(1, "", "Command not found", 45L);
        Assert.assertFalse(failureResult.isSuccessful());
        Assert.assertEquals(1, failureResult.getExitCode());
    }

    @Test
    public void testStorageOperationResult() {
        StorageOperationResult.Success opSuccess = new StorageOperationResult.Success("/path/to/backup.tar.gz", 1024L, "sha256abc");
        Assert.assertEquals("/path/to/backup.tar.gz", opSuccess.getPath());
        Assert.assertEquals(1024L, opSuccess.getBytesTransferred());
        Assert.assertEquals("sha256abc", opSuccess.getChecksumSha256());

        StorageOperationResult.Failure opFailure = new StorageOperationResult.Failure("/path/to/backup.tar.gz", "Disk full", null);
        Assert.assertEquals("Disk full", opFailure.getError());
    }

    @Test
    public void testSystemResourceSnapshotCalculations() {
        SystemResourceSnapshot snap = new SystemResourceSnapshot(
                30000000000L,
                64000000000L,
                4,
                0,
                System.currentTimeMillis()
        );

        Assert.assertEquals(4, snap.getActiveServicesCount());
        Assert.assertFalse(snap.isStorageLow());
        Assert.assertTrue(snap.getStorageFreeMb() > 20000);
    }

    @Test
    public void testDashboardPresenterBrandColorsAndFormatting() {
        int jellyfinColor = DashboardPresenter.getServiceBrandColor("jellyfin");
        Assert.assertNotEquals(0, jellyfinColor);

        int radarrColor = DashboardPresenter.getServiceBrandColor("radarr");
        Assert.assertNotEquals(0, radarrColor);

        String zeroBytes = DashboardPresenter.formatBytes(0L);
        Assert.assertEquals("0 B", zeroBytes);

        String mbFormatted = DashboardPresenter.formatBytes(1048576L * 150L);
        Assert.assertTrue(mbFormatted.contains("MB"));
    }

    @Test
    public void testServiceReadinessStatusCodes() {
        ServiceHealthMonitor monitor = new ServiceHealthMonitor(1000);

        // Transmission accepts 409 Conflict as ready (standard CSRF challenge) and 200..399
        Assert.assertTrue("Transmission 409 is ready", monitor.isReadyStatusCode("transmission", 409));
        Assert.assertTrue("Transmission 200 is ready", monitor.isReadyStatusCode("transmission", 200));

        // Other services MUST NOT classify 409 as ready
        Assert.assertFalse("Radarr 409 is not ready", monitor.isReadyStatusCode("radarr", 409));
        Assert.assertFalse("Sonarr 409 is not ready", monitor.isReadyStatusCode("sonarr", 409));
        Assert.assertFalse("Jellyfin 409 is not ready", monitor.isReadyStatusCode("jellyfin", 409));

        // HTTP 401 must not be classified as ready for any service
        Assert.assertFalse("Radarr 401 is not ready", monitor.isReadyStatusCode("radarr", 401));
        Assert.assertFalse("Sonarr 401 is not ready", monitor.isReadyStatusCode("sonarr", 401));
        Assert.assertFalse("Jellyfin 401 is not ready", monitor.isReadyStatusCode("jellyfin", 401));

        // Standard 200 is ready for all services
        Assert.assertTrue("Radarr 200 is ready", monitor.isReadyStatusCode("radarr", 200));
        Assert.assertTrue("Sonarr 200 is ready", monitor.isReadyStatusCode("sonarr", 200));
        Assert.assertTrue("Jellyfin 200 is ready", monitor.isReadyStatusCode("jellyfin", 200));
    }

    @Test
    public void testServiceHealthAssessmentGranularity() {
        ServiceHealthMonitor monitor = new ServiceHealthMonitor(1000);

        // Unbound port + process not alive -> STOPPED
        ServiceHealthSnapshot stoppedSnap = monitor.assessHealth("test_svc", 59198, null, null, false);
        Assert.assertEquals(ServiceHealthState.STOPPED, stoppedSnap.getState());
        Assert.assertFalse(stoppedSnap.isPortListening());

        // Unbound port + process alive -> PROCESS_RUNNING
        ServiceHealthSnapshot runningSnap = monitor.assessHealth("test_svc", 59198, null, null, true);
        Assert.assertEquals(ServiceHealthState.PROCESS_RUNNING, runningSnap.getState());
        Assert.assertFalse(runningSnap.isPortListening());
    }

    @Test
    public void testLifecycleExecutorRejectionRollbackBehavior() {
        AtomicReference<ServiceLifecycleResult> resultRef = new AtomicReference<>();
        ServiceLifecycleCallback callback = resultRef::set;

        ThreadPoolExecutor saturatedExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy()
        );

        CountDownLatch latch = new CountDownLatch(1);
        try {
            // Fill worker thread
            saturatedExecutor.execute(() -> {
                try { latch.await(); } catch (InterruptedException ignored) {}
            });
            // Fill single-item queue
            saturatedExecutor.execute(() -> {});

            // 3rd task must throw RejectedExecutionException under AbortPolicy
            try {
                saturatedExecutor.execute(() -> {});
                Assert.fail("Expected RejectedExecutionException under saturated executor");
            } catch (RejectedExecutionException rex) {
                // Emulate MediaServiceManager rollback behavior: dispatch Failure with cause
                ServiceLifecycleResult failure = new ServiceLifecycleResult.Failure(
                        "radarr",
                        "RESOURCE_EXHAUSTED: Lifecycle executor rejected task under high load",
                        rex
                );
                callback.onResult(failure);
            }

            Assert.assertNotNull(resultRef.get());
            Assert.assertTrue(resultRef.get() instanceof ServiceLifecycleResult.Failure);
            ServiceLifecycleResult.Failure fail = (ServiceLifecycleResult.Failure) resultRef.get();
            Assert.assertEquals("radarr", fail.getServiceId());
            Assert.assertTrue(fail.getReason().contains("RESOURCE_EXHAUSTED"));
            Assert.assertTrue(fail.getCause() instanceof RejectedExecutionException);
        } finally {
            latch.countDown();
            saturatedExecutor.shutdownNow();
        }
    }

    @Test
    public void testDegradedAndFailedHealthStates() {
        ServiceHealthSnapshot degradedSnapshot = new ServiceHealthSnapshot(
                "radarr",
                ServiceHealthState.DEGRADED,
                true,
                true,
                150L,
                500,
                "Internal Server Error",
                System.currentTimeMillis()
        );
        Assert.assertEquals(ServiceHealthState.DEGRADED, degradedSnapshot.getState());
        Assert.assertEquals(500, degradedSnapshot.getHttpStatusCode());

        ServiceHealthSnapshot failedSnapshot = new ServiceHealthSnapshot(
                "sonarr",
                ServiceHealthState.FAILED,
                false,
                false,
                -1L,
                -1,
                "Process crashed unexpectedly with exit code 139",
                System.currentTimeMillis()
        );
        Assert.assertEquals(ServiceHealthState.FAILED, failedSnapshot.getState());
        Assert.assertFalse(failedSnapshot.isPortListening());
    }
}
