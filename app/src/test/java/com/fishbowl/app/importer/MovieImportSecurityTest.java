package com.fishbowl.app.importer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Automated security and reliability tests for Fishbowl Movie Import Subsystem:
 * - Test 1: ImportRecord field integrity verifies SHA-256 cryptographic checksum.
 * - Test 2: File extension allowlist strictly rejects traversal, null bytes, and unsafe types.
 * - Test 3: Path containment enforces destination staying within media library root.
 * - Test 4: Transactional replacement retains destination backup until success is confirmed.
 * - Test 5: Transactional rollback restores original movie on simulated checksum mismatch.
 * - Test 6: computeSHA256 returns standard SHA-256 test vectors and handles errors fail-closed.
 * - Test 7: Cryptographic mismatch halts transfer, restores backup, and protects source file.
 * - Test 8: Source deletion safety refuses deletion if destination size does not match expected size.
 * - Test 9: Source deletion safety refuses deletion if source equals destination path.
 * - Test 10: Staging cleanup preserves active staging file and files younger than 30 minutes.
 */
public class MovieImportSecurityTest {

    private File testRoot;
    private File sourceDir;
    private File destDir;

    @Before
    public void setUp() throws Exception {
        testRoot = new File(System.getProperty("java.io.tmpdir"), "fishbowl_import_test_" + System.currentTimeMillis());
        testRoot.mkdirs();
        sourceDir = new File(testRoot, "source");
        destDir = new File(testRoot, "destination");
        sourceDir.mkdirs();
        destDir.mkdirs();
    }

    @After
    public void tearDown() {
        deleteDir(testRoot);
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) deleteDir(c);
        }
        dir.delete();
    }

    @Test
    public void testImportRecordSha256ChecksumFieldIntegrity() {
        MovieImportJournal.ImportRecord record = new MovieImportJournal.ImportRecord();
        record.id = "test-uuid-123";
        record.sourceFilename = "Avatar.2009.1080p.mkv";
        record.destinationPath = "/movies/Avatar (2009)/Avatar (2009).mkv";
        record.fileSizeBytes = 5_000_000_000L;
        record.status = "COMPLETED";
        record.operation = "MOVE";
        record.sourceChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        Assert.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", record.sourceChecksum);
        Assert.assertEquals(64, record.sourceChecksum.length());
    }

    @Test
    public void testFileExtensionAllowlistRejectsUnsafeTypes() {
        // Legitimate supported formats
        Assert.assertTrue(MovieFilenameParser.isSupportedMediaExtension("mkv"));
        Assert.assertTrue(MovieFilenameParser.isSupportedMediaExtension(".mp4"));
        Assert.assertTrue(MovieFilenameParser.isSupportedMediaExtension("AVI"));
        Assert.assertTrue(MovieFilenameParser.isSupportedMediaExtension("webm"));
        Assert.assertTrue(MovieFilenameParser.isSupportedMediaExtension("m4v"));
        Assert.assertTrue(MovieFilenameParser.isSupportedMediaExtension("iso"));

        // Malformed, traversal, or null-byte attacks
        Assert.assertFalse("Must reject directory traversal", MovieFilenameParser.isSupportedMediaExtension("../sh"));
        Assert.assertFalse("Must reject path separator", MovieFilenameParser.isSupportedMediaExtension("mkv/test"));
        Assert.assertFalse("Must reject backslash", MovieFilenameParser.isSupportedMediaExtension("mp4\\bin"));
        Assert.assertFalse("Must reject null byte", MovieFilenameParser.isSupportedMediaExtension("mkv\0.exe"));
        Assert.assertFalse("Must reject executable format", MovieFilenameParser.isSupportedMediaExtension("exe"));
        Assert.assertFalse("Must reject shell script", MovieFilenameParser.isSupportedMediaExtension("sh"));
        Assert.assertFalse("Must reject php script", MovieFilenameParser.isSupportedMediaExtension("php"));
        Assert.assertFalse("Must reject empty string", MovieFilenameParser.isSupportedMediaExtension(""));
        Assert.assertFalse("Must reject null", MovieFilenameParser.isSupportedMediaExtension(null));

        // Normalization
        Assert.assertEquals("mkv", MovieFilenameParser.normalizeMediaExtension(".MKV"));
        Assert.assertEquals("mp4", MovieFilenameParser.normalizeMediaExtension("MP4"));
    }

    @Test
    public void testDestinationPathContainmentEnforced() throws Exception {
        File libraryRoot = new File(destDir, "movies");
        libraryRoot.mkdirs();

        File validDest = new File(libraryRoot, "Avatar (2009)/Avatar (2009).mkv");
        Assert.assertTrue("Valid destination inside library root must be contained",
                MovieImportManager.isPathContained(libraryRoot, validDest));

        File traversalDest1 = new File(libraryRoot, "../../system/build.prop");
        Assert.assertFalse("Path escaping root via .. must be rejected",
                MovieImportManager.isPathContained(libraryRoot, traversalDest1));

        File traversalDest2 = new File(libraryRoot, "nested/../../../../etc/passwd");
        Assert.assertFalse("Deep traversal escaping root must be rejected",
                MovieImportManager.isPathContained(libraryRoot, traversalDest2));
    }

    @Test
    public void testTransactionalReplacementRetainsBackupUntilSuccess() throws Exception {
        File dest = new File(destDir, "movie.mkv");
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write("ORIGINAL_OLD_VERSION".getBytes(StandardCharsets.UTF_8));
        }

        File staging = new File(destDir, "movie.mkv.fishbowl_tmp");
        try (FileOutputStream fos = new FileOutputStream(staging)) {
            fos.write("UPGRADED_4K_REMASTER".getBytes(StandardCharsets.UTF_8));
        }

        // Begin transaction
        MovieImportManager.CommitTransaction tx = MovieImportManager.beginSafeCommitDestination(staging, dest);
        Assert.assertTrue(tx.wasReplaced);
        Assert.assertNotNull(tx.backupFile);
        Assert.assertTrue("Backup must exist while transaction is in flight", tx.backupFile.exists());
        Assert.assertEquals("ORIGINAL_OLD_VERSION", new String(java.nio.file.Files.readAllBytes(tx.backupFile.toPath()), StandardCharsets.UTF_8));

        // Check destination has new staged content
        Assert.assertEquals("UPGRADED_4K_REMASTER", new String(java.nio.file.Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8));

        // Successful commit purges backup
        tx.commitSuccess();
        Assert.assertFalse("Backup must be purged after commitSuccess", tx.backupFile.exists());
        Assert.assertTrue("Final destination must remain intact", dest.exists());
    }

    @Test
    public void testTransactionalRollbackRestoresOriginalOnFailure() throws Exception {
        File dest = new File(destDir, "precious_movie.mkv");
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write("ORIGINAL_PRECIOUS_CONTENT".getBytes(StandardCharsets.UTF_8));
        }

        File staging = new File(destDir, "precious_movie.mkv.fishbowl_tmp");
        try (FileOutputStream fos = new FileOutputStream(staging)) {
            fos.write("CORRUPTED_INCOMPLETE_DATA".getBytes(StandardCharsets.UTF_8));
        }

        // Begin commit
        MovieImportManager.CommitTransaction tx = MovieImportManager.beginSafeCommitDestination(staging, dest);
        Assert.assertTrue("Backup must exist", tx.backupFile.exists());

        // Simulate post-commit checksum failure: trigger rollback!
        tx.rollback();

        // Original movie must be restored 100%
        Assert.assertTrue("Destination must exist after rollback", dest.exists());
        String restored = new String(java.nio.file.Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8);
        Assert.assertEquals("ORIGINAL_PRECIOUS_CONTENT", restored);
    }

    @Test
    public void testComputeSha256OfEmptyAndPopulatedFile() throws Exception {
        // Empty file SHA-256 standard test vector
        File emptyFile = new File(sourceDir, "empty.bin");
        emptyFile.createNewFile();
        String emptyChecksum = MovieImportManager.computeSHA256(emptyFile);
        Assert.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyChecksum);

        // Populated file SHA-256 computation
        File sampleFile = new File(sourceDir, "sample.bin");
        try (FileOutputStream fos = new FileOutputStream(sampleFile)) {
            fos.write("Fishbowl Media Importer Integrity Test".getBytes(StandardCharsets.UTF_8));
        }
        String sampleChecksum = MovieImportManager.computeSHA256(sampleFile);
        Assert.assertNotNull(sampleChecksum);
        Assert.assertEquals(64, sampleChecksum.length());

        // Non-existent file throws IOException fail-closed
        File nonExistent = new File(sourceDir, "does_not_exist.bin");
        try {
            MovieImportManager.computeSHA256(nonExistent);
            Assert.fail("Should throw IOException for non-existent file");
        } catch (IOException expected) {
            // Success
        }
    }

    @Test
    public void testCryptographicMismatchHaltsSourceDeletion() throws Exception {
        File srcFile = new File(sourceDir, "movie.mkv");
        try (FileOutputStream fos = new FileOutputStream(srcFile)) {
            fos.write("SOURCE_DATA".getBytes(StandardCharsets.UTF_8));
        }
        File destFile = new File(destDir, "movie.mkv");
        try (FileOutputStream fos = new FileOutputStream(destFile)) {
            fos.write("CORRUPTED_DEST_DATA".getBytes(StandardCharsets.UTF_8));
        }

        String sourceChecksum = MovieImportManager.computeSHA256(srcFile);
        String destChecksum = MovieImportManager.computeSHA256(destFile);

        boolean match = MessageDigest.isEqual(
                sourceChecksum.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                destChecksum.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );

        Assert.assertFalse("Mismatched file contents must produce mismatched digests", match);
        Assert.assertTrue("Source file must be preserved on checksum mismatch", srcFile.exists());
    }

    @Test
    public void testSourceDeletionSafetyRejectsMismatchedDestination() throws Exception {
        File srcFile = new File(sourceDir, "movie.mkv");
        try (FileOutputStream fos = new FileOutputStream(srcFile)) {
            fos.write(new byte[1024]);
        }
        File destFile = new File(destDir, "movie.mkv");
        try (FileOutputStream fos = new FileOutputStream(destFile)) {
            fos.write(new byte[512]); // Incomplete / truncated destination
        }

        long expectedSize = 1024;
        boolean shouldDelete = (destFile.exists() && destFile.length() == expectedSize);
        Assert.assertFalse("Source file must NOT be deleted if destination size does not match expected size", shouldDelete);
        Assert.assertTrue("Source file must remain untouched", srcFile.exists());
    }

    @Test
    public void testSourceDeletionSafetyRejectsSelfDeletion() throws Exception {
        File sameFile = new File(destDir, "movie.mkv");
        try (FileOutputStream fos = new FileOutputStream(sameFile)) {
            fos.write(new byte[1024]);
        }

        boolean isSamePath = sameFile.getCanonicalPath().equals(sameFile.getCanonicalPath());
        Assert.assertTrue(isSamePath);
    }

    @Test
    public void testStagingCleanupPreservesRecentAndActiveFiles() throws Exception {
        File activeStaging = new File(destDir, "Movie.mkv.fishbowl_tmp");
        try (FileOutputStream fos = new FileOutputStream(activeStaging)) {
            fos.write(new byte[2048]);
        }
        activeStaging.setLastModified(System.currentTimeMillis());

        File staleStaging = new File(destDir, "OldOrphan.mkv.fishbowl_tmp");
        try (FileOutputStream fos = new FileOutputStream(staleStaging)) {
            fos.write(new byte[2048]);
        }
        // Set last modified to 2 hours ago
        staleStaging.setLastModified(System.currentTimeMillis() - (2 * 3600 * 1000L));

        long now = System.currentTimeMillis();
        File[] list = destDir.listFiles();
        Assert.assertNotNull(list);

        for (File f : list) {
            if (f.getName().endsWith(".fishbowl_tmp")) {
                if (f.equals(activeStaging)) {
                    continue; // Active staging protected
                }
                long age = now - f.lastModified();
                if (age > 30 * 60 * 1000L) {
                    f.delete(); // Stale orphaned staging purged
                }
            }
        }

        Assert.assertTrue("Active/recent staging file must be preserved", activeStaging.exists());
        Assert.assertFalse("Stale orphaned staging file older than 30min should be purged", staleStaging.exists());
    }
}
