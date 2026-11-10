package com.fishbowl.app;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests validating crash-safe atomic write semantics, data integrity,
 * exception tolerance, crash recovery scenarios, and concurrent execution in
 * {@link MediaStorageManager#safeAtomicWrite(File, byte[])} and
 * {@link MediaStorageManager#recoverAtomicFile(File)}.
 */
public class MediaStorageAtomicWriteTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File testDir;

    @Before
    public void setUp() throws Exception {
        testDir = tempFolder.newFolder("atomic_test_dir");
    }

    @After
    public void tearDown() {
        // Clean up handled by TemporaryFolder rule
    }

    private byte[] readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = 0;
            while (read < buf.length) {
                int r = fis.read(buf, read, buf.length - read);
                if (r < 0) break;
                read += r;
            }
            return buf;
        }
    }

    private void writeFileRaw(File file, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
    }

    @Test
    public void testNewFileCreation() throws Exception {
        File target = new File(testDir, "new_file.txt");
        assertFalse(target.exists());

        byte[] payload = "Hello Fishbowl Atomic Write".getBytes(StandardCharsets.UTF_8);
        boolean success = MediaStorageManager.safeAtomicWrite(target, payload);

        assertTrue("safeAtomicWrite should return true on new file creation", success);
        assertTrue("Target file must exist after write", target.exists());
        assertEquals("Target file size must match payload size", payload.length, target.length());
        assertArrayEquals("Target file content must match payload", payload, readFile(target));
    }

    @Test
    public void testExistingFileReplacement() throws Exception {
        File target = new File(testDir, "replace_file.json");
        byte[] original = "{\"version\": 1, \"status\": \"initial\"}".getBytes(StandardCharsets.UTF_8);
        assertTrue(MediaStorageManager.safeAtomicWrite(target, original));
        assertArrayEquals(original, readFile(target));

        byte[] updated = "{\"version\": 2, \"status\": \"updated_cleanly\"}".getBytes(StandardCharsets.UTF_8);
        boolean success = MediaStorageManager.safeAtomicWrite(target, updated);

        assertTrue("safeAtomicWrite should succeed on replacement", success);
        assertTrue("Target file must still exist", target.exists());
        assertArrayEquals("Target file must have updated content", updated, readFile(target));
    }

    @Test
    public void testEmptyInput() throws Exception {
        File target = new File(testDir, "empty.bin");
        byte[] payload = new byte[0];

        boolean success = MediaStorageManager.safeAtomicWrite(target, payload);

        assertTrue("Empty byte array should be written successfully", success);
        assertTrue(target.exists());
        assertEquals(0L, target.length());
    }

    @Test
    public void testLargeFileWrite() throws Exception {
        File target = new File(testDir, "large_media_manifest.bin");
        byte[] payload = new byte[512 * 1024]; // 512 KB
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 127);
        }

        boolean success = MediaStorageManager.safeAtomicWrite(target, payload);

        assertTrue("Large 512KB payload should write successfully", success);
        assertEquals(payload.length, target.length());
        assertArrayEquals("Large payload must be preserved byte-for-byte", payload, readFile(target));
    }

    @Test
    public void testInvalidDestinationNullArgs() {
        assertFalse("safeAtomicWrite with null target must return false",
                MediaStorageManager.safeAtomicWrite(null, new byte[]{1, 2, 3}));

        File target = new File(testDir, "valid_target.txt");
        assertFalse("safeAtomicWrite with null data must return false",
                MediaStorageManager.safeAtomicWrite(target, null));
        assertFalse("Null data should not create target file", target.exists());
    }

    @Test
    public void testAutoCreateParentDirectories() throws Exception {
        File nestedDir = new File(testDir, "nested" + File.separator + "sub" + File.separator + "folder");
        File target = new File(nestedDir, "nested_config.xml");

        assertFalse("Parent hierarchy must not exist initially", nestedDir.exists());

        byte[] payload = "<config>Fishbowl</config>".getBytes(StandardCharsets.UTF_8);
        boolean success = MediaStorageManager.safeAtomicWrite(target, payload);

        assertTrue("Writing to non-existent parent directory should auto-create parent and succeed", success);
        assertTrue("Nested directory must exist", nestedDir.isDirectory());
        assertTrue("Target file must exist", target.exists());
        assertArrayEquals(payload, readFile(target));
    }

    @Test
    public void testTargetPreservedWhenParentInvalid() throws Exception {
        File validTarget = new File(testDir, "critical_db.sqlite");
        byte[] initialData = "DATABASE_INTEGRITY_CHECK_OK".getBytes(StandardCharsets.UTF_8);
        assertTrue(MediaStorageManager.safeAtomicWrite(validTarget, initialData));

        File notADir = new File(testDir, "plain_file.txt");
        assertTrue(notADir.createNewFile());
        File invalidTarget = new File(notADir, "cannot_exist.txt");

        boolean result = MediaStorageManager.safeAtomicWrite(invalidTarget, "newdata".getBytes(StandardCharsets.UTF_8));
        assertFalse("Writing inside a non-directory file must return false", result);

        assertArrayEquals("Original target data must not be corrupted or altered", initialData, readFile(validTarget));
    }

    /**
     * Crash Scenario A:
     * App crashes after destination was renamed to backup, but before new temporary file is promoted.
     * Destination is missing on disk, but backup exists with last valid data.
     */
    @Test
    public void testCrashRecoveryWhenDestinationMissingAndBackupExists() throws Exception {
        File destFile = new File(testDir, "user_watch_state.db");
        File backupFile = new File(testDir, "user_watch_state.db.bak");

        byte[] originalValidData = "LAST_VALID_WATCH_STATE_EPISODE_4".getBytes(StandardCharsets.UTF_8);
        writeFileRaw(backupFile, originalValidData);
        assertFalse(destFile.exists());
        assertTrue(backupFile.exists());

        boolean recovered = MediaStorageManager.recoverAtomicFile(destFile);

        assertTrue("recoverAtomicFile should return true", recovered);
        assertTrue("Destination file must be restored from backup", destFile.exists());
        assertFalse("Backup file must be consumed/cleaned up", backupFile.exists());
        assertArrayEquals("Restored content must match original valid data", originalValidData, readFile(destFile));
    }

    /**
     * Crash Scenario B:
     * App crashes after temporary file was promoted to destination, but before backup file was deleted.
     * Both destination (with complete new content) and backup (with old content) exist on disk.
     */
    @Test
    public void testCrashRecoveryWhenBothDestinationAndBackupExist() throws Exception {
        File destFile = new File(testDir, "server_config.json");
        File backupFile = new File(testDir, "server_config.json.bak");

        byte[] newValidData = "{\"server\": \"Fishbowl\", \"version\": 2}".getBytes(StandardCharsets.UTF_8);
        byte[] oldBackupData = "{\"server\": \"Fishbowl\", \"version\": 1}".getBytes(StandardCharsets.UTF_8);

        writeFileRaw(destFile, newValidData);
        writeFileRaw(backupFile, oldBackupData);

        boolean recovered = MediaStorageManager.recoverAtomicFile(destFile);

        assertTrue("recoverAtomicFile should return true when destination exists", recovered);
        assertTrue("Destination file must remain intact", destFile.exists());
        assertFalse("Obsolete backup file must be pruned", backupFile.exists());
        assertArrayEquals("Destination must retain the new complete content", newValidData, readFile(destFile));
    }

    /**
     * Crash Scenario C:
     * Pre-existing backup file is older than 1 hour.
     * Verify that stale cleanup NEVER deletes valid recovery backups.
     */
    @Test
    public void testStaleCleanupNeverDeletesRecoveryBackups() throws Exception {
        File destFile = new File(testDir, "critical_manifest.json");
        File backupFile = new File(testDir, "critical_manifest.json.bak");
        File staleTmpFile = new File(testDir, "critical_manifest.json.tmp");

        byte[] validBackup = "PERSISTED_BACKUP_CONTENT".getBytes(StandardCharsets.UTF_8);
        writeFileRaw(backupFile, validBackup);
        writeFileRaw(staleTmpFile, "partial_garbage".getBytes(StandardCharsets.UTF_8));

        // Age files by 3 hours
        long threeHoursAgo = System.currentTimeMillis() - (3 * 3600 * 1000);
        backupFile.setLastModified(threeHoursAgo);
        staleTmpFile.setLastModified(threeHoursAgo);

        // Run recovery
        MediaStorageManager.recoverAtomicFile(destFile);

        // Destination must be restored from backup despite age
        assertTrue("Destination must be restored from backup", destFile.exists());
        assertArrayEquals(validBackup, readFile(destFile));
        assertFalse("Stale tmp file must be deleted", staleTmpFile.exists());
    }

    @Test
    public void testRecoveryIsIdempotent() throws Exception {
        File destFile = new File(testDir, "idempotent_test.txt");
        File backupFile = new File(testDir, "idempotent_test.txt.bak");

        byte[] data = "STABLE_DATA".getBytes(StandardCharsets.UTF_8);
        writeFileRaw(backupFile, data);

        // Execute recovery multiple times consecutively
        for (int i = 0; i < 5; i++) {
            boolean result = MediaStorageManager.recoverAtomicFile(destFile);
            assertTrue("Each idempotent recovery call must return true", result);
            assertTrue("File must exist after recovery run " + i, destFile.exists());
            assertArrayEquals(data, readFile(destFile));
            assertFalse(backupFile.exists());
        }
    }

    @Test
    public void testConcurrentWritesToDistinctFiles() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.execute(() -> {
                try {
                    File f = new File(testDir, "concurrent_" + index + ".txt");
                    byte[] data = ("Thread payload #" + index).getBytes(StandardCharsets.UTF_8);
                    if (MediaStorageManager.safeAtomicWrite(f, data)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue("All concurrent writes should finish within 10 seconds", latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals("All parallel thread writes must succeed", threads, successCount.get());

        for (int i = 0; i < threads; i++) {
            File f = new File(testDir, "concurrent_" + i + ".txt");
            assertTrue("File for thread " + i + " must exist", f.exists());
            byte[] expected = ("Thread payload #" + i).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(expected, readFile(f));
        }
    }

    @Test
    public void testConcurrentWritesToSameFileSerialized() throws Exception {
        int threads = 8;
        File sharedTarget = new File(testDir, "shared_counter.txt");
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.execute(() -> {
                try {
                    byte[] data = ("Winner Thread #" + index).getBytes(StandardCharsets.UTF_8);
                    if (MediaStorageManager.safeAtomicWrite(sharedTarget, data)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threads, successCount.get());
        assertTrue(sharedTarget.exists());
        String finalContent = new String(readFile(sharedTarget), StandardCharsets.UTF_8);
        assertTrue("Final content must be a complete uncorrupted winner string",
                finalContent.startsWith("Winner Thread #"));
    }
}
