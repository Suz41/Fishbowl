package com.fishbowl.app;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Automated regression tests for Media Storage Manager duplicate cleanup and staging safety.
 * Covers:
 * - Test 6: In-progress / partial downloads (.part, .aria2, .tmp, .crdownload, archives) protected from deletion.
 * - Test 7: SHA-256 hash mismatch preserves source file.
 * - Test 8: Approved staging boundaries enforcement (refusal to delete outside approved roots).
 * - Test 9: Identical canonical path protection (same file is never deleted).
 */
public class MediaStorageCleanupTest {

    private File testRoot;
    private File mockMediaRoot;
    private File stagingRadarr;
    private File stagingSonarr;
    private File stagingTorrents;
    private File libraryMovies;
    private File libraryTv;
    private File externalDir;

    @Before
    public void setUp() throws Exception {
        testRoot = new File(System.getProperty("java.io.tmpdir"), "fishbowl_storage_test_" + System.currentTimeMillis());
        testRoot.mkdirs();

        mockMediaRoot = new File(testRoot, "FishbowlMedia");
        mockMediaRoot.mkdirs();

        stagingRadarr = new File(mockMediaRoot, MediaStorageManager.SUB_DOWNLOADS_RADARR);
        stagingSonarr = new File(mockMediaRoot, MediaStorageManager.SUB_DOWNLOADS_SONARR);
        stagingTorrents = new File(mockMediaRoot, MediaStorageManager.SUB_DOWNLOADS_TORRENTS);
        libraryMovies = new File(mockMediaRoot, MediaStorageManager.SUB_MOVIES);
        libraryTv = new File(mockMediaRoot, MediaStorageManager.SUB_TV);
        externalDir = new File(testRoot, "system_or_external_folder");

        stagingRadarr.mkdirs();
        stagingSonarr.mkdirs();
        stagingTorrents.mkdirs();
        libraryMovies.mkdirs();
        libraryTv.mkdirs();
        externalDir.mkdirs();
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
     * Test 6: Interrupted / partial imports (.part, .aria2, .tmp, .crdownload, archives)
     * are detected as active download artifacts and never flagged as cleanable.
     */
    @Test
    public void test6_InterruptedAndPartialDownloadsNeverPrematurelyCleaned() throws Exception {
        File downloadFolder = new File(stagingRadarr, "InFlightMovie (2024)");
        downloadFolder.mkdirs();

        File partFile = new File(downloadFolder, "movie.mkv.part");
        File aria2File = new File(downloadFolder, "movie.mkv.aria2");
        File tmpFile = new File(downloadFolder, "sample.tmp");
        File crdownloadFile = new File(downloadFolder, "video.crdownload");
        File rarArchive = new File(downloadFolder, "movie.part01.rar");

        writeFile(partFile, "Partial stream bytes 0..1024");
        writeFile(aria2File, "Aria2 state metadata");
        writeFile(tmpFile, "Temporary buffer data");
        writeFile(crdownloadFile, "Chrome incomplete download");
        writeFile(rarArchive, "Rar archive part 1");

        // The directory must be recognized as containing active media files
        Assert.assertTrue("Directory with .part files is recognized as containing active downloads",
                MediaStorageManager.dirContainsMediaFiles(downloadFolder));

        // Create individual folder for each in-progress format
        File singlePartFolder = new File(stagingSonarr, "SinglePartSeries");
        singlePartFolder.mkdirs();
        writeFile(new File(singlePartFolder, "episode.mkv.part"), "data");
        Assert.assertTrue("Single .part file must protect folder from deletion",
                MediaStorageManager.dirContainsMediaFiles(singlePartFolder));

        File singleArchiveFolder = new File(stagingTorrents, "SingleArchive");
        singleArchiveFolder.mkdirs();
        writeFile(new File(singleArchiveFolder, "release.zip"), "data");
        Assert.assertTrue("Single archive .zip must protect folder from deletion",
                MediaStorageManager.dirContainsMediaFiles(singleArchiveFolder));
    }

    /**
     * Test 7: Hash mismatch preserves source file.
     * Content verification between source and destination must fail if byte content differs,
     * ensuring source download is preserved.
     */
    @Test
    public void test7_HashMismatchPreservesSourceFile() throws Exception {
        File stagingFile = new File(stagingRadarr, "Blockbuster.2024.1080p.mkv");
        File libraryFile = new File(libraryMovies, "Blockbuster (2024)/Blockbuster.2024.mkv");

        writeFile(stagingFile, "Original Download Content SHA256_AAA");
        writeFile(libraryFile, "Corrupted Or Different Destination Content SHA256_BBB");

        // Verify content match returns false on mismatch
        boolean match = MediaStorageManager.verifyFileContentMatch(stagingFile, libraryFile);
        Assert.assertFalse("Mismatched content must return false", match);

        // Verify staging file was NOT deleted or mutated
        Assert.assertTrue("Source staging file must remain intact", stagingFile.exists());
        Assert.assertEquals("Source content must be preserved",
                "Original Download Content SHA256_AAA",
                BackupRestoreManager.readFileToString(stagingFile));
    }

    /**
     * Test 8: Approved staging boundary enforcement.
     * Refusal to clean or delete files located outside approved download staging roots.
     */
    @Test
    public void test8_ApprovedStagingBoundaryEnforcement() throws Exception {
        File validStagingRadarr = new File(stagingRadarr, "movie.mkv");
        File validStagingSonarr = new File(stagingSonarr, "show.mkv");
        File validStagingTorrents = new File(stagingTorrents, "torrent_data.iso");

        File invalidLibraryMovie = new File(libraryMovies, "Avatar (2009)/Avatar.mkv");
        File invalidLibraryTv = new File(libraryTv, "Breaking Bad/Season 01/S01E01.mkv");
        File invalidExternalFile = new File(externalDir, "system_file.conf");

        writeFile(validStagingRadarr, "data");
        writeFile(validStagingSonarr, "data");
        writeFile(validStagingTorrents, "data");
        writeFile(invalidLibraryMovie, "data");
        writeFile(invalidLibraryTv, "data");
        writeFile(invalidExternalFile, "data");

        // Valid staging directories must be approved
        Assert.assertTrue("Downloads/radarr is an approved staging path",
                MediaStorageManager.isPathInStagingRoots(validStagingRadarr, mockMediaRoot));
        Assert.assertTrue("Downloads/sonarr is an approved staging path",
                MediaStorageManager.isPathInStagingRoots(validStagingSonarr, mockMediaRoot));
        Assert.assertTrue("Downloads/torrents is an approved staging path",
                MediaStorageManager.isPathInStagingRoots(validStagingTorrents, mockMediaRoot));

        // Library and external paths MUST be rejected
        Assert.assertFalse("Movies/ library folder is NOT an approved staging path",
                MediaStorageManager.isPathInStagingRoots(invalidLibraryMovie, mockMediaRoot));
        Assert.assertFalse("TV/ library folder is NOT an approved staging path",
                MediaStorageManager.isPathInStagingRoots(invalidLibraryTv, mockMediaRoot));
        Assert.assertFalse("External system directory is NOT an approved staging path",
                MediaStorageManager.isPathInStagingRoots(invalidExternalFile, mockMediaRoot));
    }

    /**
     * Test 9: Identical canonical path protection.
     * When source and destination point to the identical canonical file (e.g. symlink or same file),
     * verifyFileContentMatch returns false and refuses deletion.
     */
    @Test
    public void test9_IdenticalCanonicalPathProtection() throws Exception {
        File targetFile = new File(stagingRadarr, "SingleOriginalFile.mkv");
        writeFile(targetFile, "Exact single file byte stream");

        // Passing the same file as both source and destination must return false
        boolean match = MediaStorageManager.verifyFileContentMatch(targetFile, targetFile);
        Assert.assertFalse("Same canonical file must be rejected to prevent self-deletion", match);

        // Verify the file still exists
        Assert.assertTrue("File is preserved and not deleted", targetFile.exists());
    }

    private void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
