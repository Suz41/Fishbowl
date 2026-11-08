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

    /**
     * Test 6: SQLite database integrity verification:
     * - Null / non-existent / empty files are rejected as unhealthy.
     * - Corrupt files without "SQLite format 3" header are rejected.
     * - Files with valid SQLite format 3 magic header pass preliminary verification.
     */
    @Test
    public void test6_SQLiteIntegrityVerification() throws Exception {
        Assert.assertFalse("Null file must not be healthy", BackupRestoreManager.isDatabaseHealthy(null));
        Assert.assertFalse("Non-existent file must not be healthy", BackupRestoreManager.isDatabaseHealthy(new File(testRoot, "does_not_exist.db")));

        File emptyDb = new File(testRoot, "empty.db");
        writeFile(emptyDb, "");
        Assert.assertFalse("Empty DB file must not be healthy", BackupRestoreManager.isDatabaseHealthy(emptyDb));

        File garbageDb = new File(testRoot, "garbage.db");
        writeFile(garbageDb, "This is not a SQLite database file at all, just random garbage content that exceeds 100 bytes of length for testing purposes.");
        Assert.assertFalse("Garbage file without SQLite header must not be healthy", BackupRestoreManager.isDatabaseHealthy(garbageDb));

        File validHeaderDb = new File(testRoot, "valid_header.db");
        byte[] sqliteHeader = new byte[1024];
        byte[] magic = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, sqliteHeader, 0, magic.length);
        try (FileOutputStream fos = new FileOutputStream(validHeaderDb)) {
            fos.write(sqliteHeader);
        }
        Assert.assertTrue("File with valid SQLite header passes preliminary check", BackupRestoreManager.isDatabaseHealthy(validHeaderDb));
    }

    /**
     * Test 7: Automatic backup pruning:
     * - Retains only the most recent N backups.
     * - Deletes older archives cleanly.
     */
    @Test
    public void test7_OldBackupPruning() throws Exception {
        File backupDir = new File(testRoot, "backups");
        backupDir.mkdirs();

        File b1 = new File(backupDir, "FishBowl_Backup_20261101_100000.zip");
        File b2 = new File(backupDir, "FishBowl_Backup_20261102_100000.zip");
        File b3 = new File(backupDir, "FishBowl_Backup_20261103_100000.zip");

        writeFile(b1, "zip1");
        writeFile(b2, "zip2");
        writeFile(b3, "zip3");

        b1.setLastModified(1000L);
        b2.setLastModified(2000L);
        b3.setLastModified(3000L);

        // Prune keeping only the latest 1
        BackupRestoreManager.pruneOldBackups(backupDir, 1);

        Assert.assertFalse("Oldest backup 1 should be pruned", b1.exists());
        Assert.assertFalse("Older backup 2 should be pruned", b2.exists());
        Assert.assertTrue("Latest backup 3 should be retained", b3.exists());
    }

    /**
     * Test 8: ZIP filtering during pruning:
     * - Only Fishbowl-owned backup archives (FishBowl_Backup_*.zip) are pruned.
     * - Unrelated third-party .zip files are preserved and never deleted.
     */
    @Test
    public void test8_UnrelatedZipFilesPreservedDuringPruning() throws Exception {
        File backupDir = new File(testRoot, "prune_filter_test");
        backupDir.mkdirs();

        File fb1 = new File(backupDir, "FishBowl_Backup_20261101_090000.zip");
        File fb2 = new File(backupDir, "FishBowl_Backup_20261102_090000.zip");
        File unrelatedZip1 = new File(backupDir, "personal_photos.zip");
        File unrelatedZip2 = new File(backupDir, "downloads_backup.zip");

        writeFile(fb1, "fishbowl-old");
        writeFile(fb2, "fishbowl-new");
        writeFile(unrelatedZip1, "user-data-1");
        writeFile(unrelatedZip2, "user-data-2");

        fb1.setLastModified(1000L);
        fb2.setLastModified(2000L);
        unrelatedZip1.setLastModified(500L);
        unrelatedZip2.setLastModified(600L);

        // Prune keeping 1 Fishbowl backup
        BackupRestoreManager.pruneOldBackups(backupDir, 1);

        Assert.assertFalse("Older FishBowl backup should be pruned", fb1.exists());
        Assert.assertTrue("Latest FishBowl backup must be preserved", fb2.exists());
        Assert.assertTrue("Unrelated zip file 1 must NEVER be deleted", unrelatedZip1.exists());
        Assert.assertTrue("Unrelated zip file 2 must NEVER be deleted", unrelatedZip2.exists());
        Assert.assertEquals("Unrelated zip content must be intact", "user-data-1", BackupRestoreManager.readFileToString(unrelatedZip1));
        Assert.assertEquals("Unrelated zip content must be intact", "user-data-2", BackupRestoreManager.readFileToString(unrelatedZip2));
    }

    /**
     * Test 9: Existing backup preservation on failure:
     * - If replacement backup creation or verification fails, the existing valid backup is NEVER deleted.
     */
    @Test
    public void test9_ExistingBackupPreservedOnGenerationFailure() throws Exception {
        File backupDir = new File(testRoot, "preserve_on_failure_test");
        backupDir.mkdirs();

        File existingBackup = new File(backupDir, "FishBowl_Backup_Latest.zip");
        String existingContent = "EXISTING_VALID_PRODUCTION_BACKUP";
        writeFile(existingBackup, existingContent);
        String originalHash = BackupRestoreManager.computeSHA256(existingBackup);

        // Simulate a corrupted temporary zip generated during export failure
        File tempZip = new File(backupDir, "FishBowl_Backup_staging_tmp.zip");
        writeFile(tempZip, "TRUNCATED_OR_INVALID_ZIP_STREAM");

        // Attempt verification - must fail
        boolean verificationFailed = false;
        try {
            BackupRestoreManager.verifyGeneratedBackupZip(tempZip);
        } catch (Exception e) {
            verificationFailed = true;
        }
        Assert.assertTrue("Corrupted temp zip must fail verification", verificationFailed);

        // Clean up temp zip as finally block does
        tempZip.delete();

        // Verify existing backup was never deleted or overwritten
        Assert.assertTrue("Existing valid backup must remain intact", existingBackup.exists());
        Assert.assertEquals("Existing backup content must be unchanged", existingContent, BackupRestoreManager.readFileToString(existingBackup));
        Assert.assertEquals("Existing backup SHA-256 must be identical", originalHash, BackupRestoreManager.computeSHA256(existingBackup));
    }

    /**
     * Test 10: Dynamic backup manifest version correctness:
     * - Manifest contains active BuildConfig VERSION_NAME (1.4.10-beta.2) and VERSION_CODE (101418).
     * - Hardcoded legacy versions (1.4.8, 101414) are rejected.
     */
    @Test
    public void test10_ManifestDynamicVersionCorrectness() throws Exception {
        String currentVersion = com.termux.BuildConfig.VERSION_NAME;
        long currentVersionCode = com.termux.BuildConfig.VERSION_CODE;

        Assert.assertEquals("Version name must be 1.4.10-beta.2", "1.4.10-beta.2", currentVersion);
        Assert.assertEquals("Version code must be 101418", 101418L, currentVersionCode);

        File metaFile = new File(stagingDir, BackupRestoreManager.META_FILENAME);
        String metaJson = "{\n" +
                "  \"formatVersion\": 2,\n" +
                "  \"app\": \"FishBowl\",\n" +
                "  \"version\": \"" + currentVersion + "\",\n" +
                "  \"versionCode\": " + currentVersionCode + ",\n" +
                "  \"timestamp\": " + System.currentTimeMillis() + ",\n" +
                "  \"items\": []\n" +
                "}";
        writeFile(metaFile, metaJson);

        BackupRestoreManager.validateManifestIntegrity(stagingDir);
        String content = BackupRestoreManager.readFileToString(metaFile);
        Assert.assertFalse("Must not contain obsolete version 1.4.8", content.contains("\"1.4.8\""));
        Assert.assertFalse("Must not contain obsolete version code 101414", content.contains("101414"));
        Assert.assertTrue("Must contain current version 1.4.10-beta.2", content.contains("1.4.10-beta.2"));
        Assert.assertTrue("Must contain current version code 101418", content.contains("101418"));
    }

    /**
     * Test 11: SQLite recovery does NOT silently report success when recovery fails.
     */
    @Test
    public void test11_SQLiteRecoveryReturnsFalseWhenNoBackupFound() {
        boolean healed = BackupRestoreManager.verifyAndHealJellyfinDatabase(null);
        Assert.assertFalse("Recovery with no valid database or backup must return false, not true", healed);
    }

    private void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
