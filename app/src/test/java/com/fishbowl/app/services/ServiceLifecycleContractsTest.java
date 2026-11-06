package com.fishbowl.app.services;

import com.fishbowl.app.core.RuntimeConfig;
import com.fishbowl.app.diagnostics.SystemResourceSnapshot;
import com.fishbowl.app.process.ProcessResult;
import com.fishbowl.app.storage.StorageOperationResult;
import com.fishbowl.app.ui.DashboardPresenter;
import com.fishbowl.app.ui.ServiceStatusPresentation;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit test suite verifying Kotlin core models, service lifecycle contracts,
 * health monitoring assessments, and UI presentation components.
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
    public void testServiceHealthSnapshotAndStates() {
        ServiceHealthSnapshot snapshot = new ServiceHealthSnapshot(
                "jellyfin",
                ServiceHealthState.READY,
                true,
                true,
                42L,
                null,
                System.currentTimeMillis()
        );

        Assert.assertEquals("jellyfin", snapshot.getServiceId());
        Assert.assertEquals(ServiceHealthState.READY, snapshot.getState());
        Assert.assertTrue(snapshot.isPortListening());
        Assert.assertTrue(snapshot.isApiResponding());
        Assert.assertEquals(42L, snapshot.getLatencyMs());
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
                8000000000L,
                4000000000L,
                150000000L,
                30000000000L,
                64000000000L,
                false,
                4,
                System.currentTimeMillis()
        );

        Assert.assertEquals(50.0f, snap.getRamUsagePercent(), 0.1f);
        Assert.assertEquals(4, snap.getActiveServicesCount());
        Assert.assertFalse(snap.isLowMemory());
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
    public void testServiceHealthMonitorWithUnboundPort() {
        ServiceHealthMonitor monitor = new ServiceHealthMonitor(1000);
        // Port 59199 is typically unbound in test environment
        boolean listening = monitor.isPortListening(59199, 100);
        Assert.assertFalse("Unbound port should return false", listening);

        ServiceHealthSnapshot snap = monitor.assessHealth("dummy", 59199, null, null);
        Assert.assertEquals(ServiceHealthState.STOPPED, snap.getState());
        Assert.assertFalse(snap.isPortListening());
    }
}
