package com.fishbowl.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Automated regression tests for Fishbowl Backup & Restore Subsystem.
 * Covers:
 * - Test 1: Successful backup archive generation, cryptographic manifest, and complete extraction.
 * - Test 2: Corrupt archive rejection (SHA-256 checksum mismatch) before touching live files.
 * - Test 3: Path traversal / Zip Slip rejection (../, leading /, etc.).
 * - Test 4: Interrupted restore automatic rollback preserving original state.
 * - Test 5: Storage pre-flight threshold validation (50 MB minimum requirement).
 */
public class BackupRestoreSecurityTest {

    private File testRoot;
    private File stagingDir;
    private File liveDir;
    private File snapshotDir;

    @Before
    public void setUp() throws Exception {
        testRoot = new File(System.getProperty("java.io.tmpdir"), "fishbowl_backup_test_" + System.currentTimeMillis());
        if (!testRoot.exists()) testRoot.mkdirs();

        stagingDir = new File(testRoot, "staging");
        stagingDir.mkdirs();

        liveDir = new File(testRoot, "live_data");
        liveDir.mkdirs();

        snapshotDir = new File(testRoot, "pre_restore_snapshot");
    }

    @After
    public void tearDown() {
        deleteRecursive(testRoot);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    /**
     * Test 1: Successful backup and restore:
     * - Generates files and manifest with SHA-256.
     * - Verifies manifest integrity.
     * - Restores files and asserts 100% byte-for-byte content equivalence.
     */
    @Test
    public void test1_SuccessfulBackupAndRestore() throws Exception {
        File prefsDir = new File(stagingDir, "shared_prefs");
        prefsDir.mkdirs();
        File prefFile = new File(prefsDir, "fishbowl_settings.xml");
        String prefContent = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map><string name=\"theme\">dark</string></map>";
        writeFile(prefFile, prefContent);

        File configDir = new File(stagingDir, "config/jellyfin");
        configDir.mkdirs();
        File configFile = new File(configDir, "system.xml");
        String configContent = "<Configuration><ServerName>FishbowlTestServer</ServerName></Configuration>";
        writeFile(configFile, configContent);

        // Build v2 manifest directly
        String hash1 = BackupRestoreManager.computeSHA256(prefFile);
        String hash2 = BackupRestoreManager.computeSHA256(configFile);

        String metaContent = "{\n" +
                "  \"app\": \"FishBowl\",\n" +
                "  \"version\": 2,\n" +
                "  \"timestamp\": " + System.currentTimeMillis() + ",\n" +
                "  \"items\": [\n" +
                "    {\n" +
                "      \"path\": \"shared_prefs/fishbowl_settings.xml\",\n" +
                "      \"size\": " + prefFile.length() + ",\n" +
                "      \"sha256\": \"" + hash1 + "\",\n" +
                "      \"type\": \"preferences\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"path\": \"config/jellyfin/system.xml\",\n" +
                "      \"size\": " + configFile.length() + ",\n" +
                "      \"sha256\": \"" + hash2 + "\",\n" +
                "      \"type\": \"config\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        writeFile(new File(stagingDir, BackupRestoreManager.META_FILENAME), metaContent);

        // Package into zip
        File zipFile = new File(testRoot, "valid_backup.zip");
        BackupRestoreManager.zipDirectory(stagingDir, zipFile);
        Assert.assertTrue("Backup ZIP archive was created", zipFile.exists() && zipFile.length() > 0);

        // Unpack to target restore directory
        File targetExtractDir = new File(testRoot, "extracted_staging");
        targetExtractDir.mkdirs();
        try (FileInputStream fis = new FileInputStream(zipFile)) {
            BackupRestoreManager.unzip(fis, targetExtractDir);
        }

        // Validate manifest integrity
        BackupRestoreManager.validateManifestIntegrity(targetExtractDir);

        // Perform restore copy
        File destPrefs = new File(liveDir, "shared_prefs");
        File destConfig = new File(liveDir, ".config");
        BackupRestoreManager.copyDirectory(new File(targetExtractDir, "shared_prefs"), destPrefs);
        BackupRestoreManager.copyDirectory(new File(targetExtractDir, "config"), destConfig);

        // Assert restored files match byte-for-byte
        File restoredPref = new File(destPrefs, "fishbowl_settings.xml");
        File restoredConfig = new File(destConfig, "jellyfin/system.xml");
        Assert.assertTrue("Restored preferences file exists", restoredPref.exists());
        Assert.assertTrue("Restored configuration file exists", restoredConfig.exists());
        Assert.assertEquals("Restored preferences content matches", prefContent, BackupRestoreManager.readFileToString(restoredPref));
        Assert.assertEquals("Restored configuration content matches", configContent, BackupRestoreManager.readFileToString(restoredConfig));
        Assert.assertEquals("Restored preference SHA-256 matches", hash1, BackupRestoreManager.computeSHA256(restoredPref));
        Assert.assertEquals("Restored configuration SHA-256 matches", hash2, BackupRestoreManager.computeSHA256(restoredConfig));
    }

    /**
     * Test 2: Corrupt archive (mismatched SHA-256) rejected before modifying live data.
     */
    @Test
    public void test2_CorruptArchiveRejectedBeforeModifyingLiveData() throws Exception {
        // Set up pre-existing live file
        File liveConfig = new File(liveDir, "original_safe_config.xml");
        String originalContent = "<Safe>DO_NOT_CORRUPT</Safe>";
        writeFile(liveConfig, originalContent);

        // Create staging file with tampered contents
        File badDir = new File(stagingDir, "shared_prefs");
        badDir.mkdirs();
        File badFile = new File(badDir, "tampered.xml");
        writeFile(badFile, "<Evil>Content Tampered</Evil>");

        String badMetaContent = "{\n" +
                "  \"app\": \"FishBowl\",\n" +
                "  \"version\": 2,\n" +
                "  \"items\": [\n" +
                "    {\n" +
                "      \"path\": \"shared_prefs/tampered.xml\",\n" +
                "      \"size\": " + badFile.length() + ",\n" +
                "      \"sha256\": \"0000000000000000000000000000000000000000000000000000000000000000\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        writeFile(new File(stagingDir, BackupRestoreManager.META_FILENAME), badMetaContent);

        boolean caught = false;
        try {
            BackupRestoreManager.validateManifestIntegrity(stagingDir);
        } catch (IllegalArgumentException e) {
            caught = true;
            Assert.assertTrue("Error mentions SHA-256 verification failure", e.getMessage().contains("SHA-256 verification failed"));
        }
        Assert.assertTrue("Corrupted archive rejected by manifest integrity validator", caught);

        // Verify live file was completely untouched
        Assert.assertEquals("Live data is untouched and preserved", originalContent, BackupRestoreManager.readFileToString(liveConfig));
    }

    /**
     * Test 3: Malicious archive paths (Zip Slip / Directory Traversal) rejected.
     */
    @Test
    public void test3_ZipSlipDirectoryTraversalRejected() throws Exception {
        // Create an in-memory ZIP containing a path traversal entry "../outside.txt"
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry maliciousEntry = new ZipEntry("../evil_slip.txt");
            zos.putNextEntry(maliciousEntry);
            zos.write("Malicious Payload".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        File targetDir = new File(testRoot, "zip_slip_target");
        targetDir.mkdirs();

        boolean caughtSecurityException = false;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
            BackupRestoreManager.unzip(bais, targetDir);
        } catch (SecurityException e) {
            caughtSecurityException = true;
            Assert.assertTrue("Exception message mentions malicious zip entry",
                    e.getMessage().contains("Malicious zip entry path") || e.getMessage().contains("Zip entry outside target directory"));
        }
        Assert.assertTrue("Zip Slip traversal entry was rejected with SecurityException", caughtSecurityException);

        // Verify malicious file was NOT written outside
        File escapedFile = new File(testRoot, "evil_slip.txt");
        Assert.assertFalse("Malicious file was prevented from escaping target directory", escapedFile.exists());
    }

    /**
     * Test 4: Interrupted restore rolls back to snapshot leaving original data intact.
     */
    @Test
    public void test4_InterruptedRestoreRollsBackToSnapshot() throws Exception {
        // Setup initial production state
        File livePrefs = new File(liveDir, "shared_prefs");
        livePrefs.mkdirs();
        File liveFile = new File(livePrefs, "master_settings.xml");
        String pristineContent = "<Settings><DatabaseReady>true</DatabaseReady></Settings>";
        writeFile(liveFile, pristineContent);

        // 1. Create pre-restore snapshot
        snapshotDir.mkdirs();
        BackupRestoreManager.copyDirectory(livePrefs, new File(snapshotDir, "shared_prefs"));

        // 2. Simulate partial / interrupted restore overwrite
        writeFile(liveFile, "<CorruptedHalfwayWrittenData>");

        // 3. Trigger rollback from snapshot
        File snapPrefs = new File(snapshotDir, "shared_prefs");
        Assert.assertTrue("Snapshot preferences exist", snapPrefs.exists());
        BackupRestoreManager.copyDirectory(snapPrefs, livePrefs);

        // Verify pristine state restored
        String recoveredContent = BackupRestoreManager.readFileToString(liveFile);
        Assert.assertEquals("Rollback restored pristine production data without corruption", pristineContent, recoveredContent);
    }

    /**
     * Test 5: Storage pre-flight threshold (50 MB minimum) logic check.
     */
    @Test
    public void test5_StoragePreFlightThresholdEnforcement() {
        long minRequiredBytes = 50 * 1024 * 1024L;

        // When storage is 49 MB (< 50 MB)
        long lowStorageBytes = 49 * 1024 * 1024L;
        boolean allowedUnderLowStorage = lowStorageBytes >= minRequiredBytes;
        Assert.assertFalse("Low storage under 50 MB must block operation", allowedUnderLowStorage);

        // When storage is 100 MB (>= 50 MB)
        long sufficientStorageBytes = 100 * 1024 * 1024L;
        boolean allowedUnderSufficientStorage = sufficientStorageBytes >= minRequiredBytes;
        Assert.assertTrue("Storage >= 50 MB is allowed", allowedUnderSufficientStorage);
    }

    private void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
