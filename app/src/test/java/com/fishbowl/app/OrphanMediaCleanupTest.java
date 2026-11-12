package com.fishbowl.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests validating orphan media folder auto-cleanup and metadata purging.
 */
public class OrphanMediaCleanupTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testFolderWithVideoAndNfoIsPreserved() throws IOException {
        File moviesDir = tempFolder.newFolder("Movies");
        File movieFolder = new File(moviesDir, "Inception (2010)");
        assertTrue(movieFolder.mkdirs());

        File videoFile = new File(movieFolder, "Inception (2010).mkv");
        writeFile(videoFile, "dummy video bytes");

        File nfoFile = new File(movieFolder, "Inception (2010).nfo");
        writeFile(nfoFile, "<movie><title>Inception</title></movie>");

        File posterFile = new File(movieFolder, "poster.jpg");
        writeFile(posterFile, "dummy image bytes");

        MediaStorageManager.OrphanFolderReport report = MediaStorageManager.cleanMediaSubfolders(moviesDir, false);

        assertEquals(0, report.foldersDeleted);
        assertEquals(0, report.filesDeleted);
        assertTrue(movieFolder.exists());
        assertTrue(videoFile.exists());
        assertTrue(nfoFile.exists());
        assertTrue(posterFile.exists());
    }

    @Test
    public void testFolderWithOnlyNfoAndImagesIsDeleted() throws IOException {
        File moviesDir = tempFolder.newFolder("Movies");
        File movieFolder = new File(moviesDir, "DeletedMovie (2020)");
        assertTrue(movieFolder.mkdirs());

        // Video was deleted in Jellyfin! Only metadata and poster remain
        File nfoFile = new File(movieFolder, "DeletedMovie (2020).nfo");
        writeFile(nfoFile, "<movie><title>Deleted Movie</title></movie>");

        File posterFile = new File(movieFolder, "poster.jpg");
        writeFile(posterFile, "dummy poster data");

        File srtFile = new File(movieFolder, "DeletedMovie (2020).en.srt");
        writeFile(srtFile, "1\n00:00:01,000 --> 00:00:04,000\nHello");

        MediaStorageManager.OrphanFolderReport report = MediaStorageManager.cleanMediaSubfolders(moviesDir, false);

        assertEquals(1, report.foldersDeleted);
        assertEquals(3, report.filesDeleted);
        assertFalse(movieFolder.exists());
        assertFalse(nfoFile.exists());
        assertFalse(posterFile.exists());
        assertFalse(srtFile.exists());
    }

    @Test
    public void testCompletelyEmptyFolderIsDeleted() throws IOException {
        File moviesDir = tempFolder.newFolder("Movies");
        File emptyFolder = new File(moviesDir, "EmptyMovieFolder");
        assertTrue(emptyFolder.mkdirs());

        MediaStorageManager.OrphanFolderReport report = MediaStorageManager.cleanMediaSubfolders(moviesDir, false);

        assertEquals(1, report.foldersDeleted);
        assertEquals(0, report.filesDeleted);
        assertFalse(emptyFolder.exists());
    }

    @Test
    public void testTvShowsNestedSeasonCleanup() throws IOException {
        File tvDir = tempFolder.newFolder("TV");
        File showFolder = new File(tvDir, "Breaking Bad");
        File season1 = new File(showFolder, "Season 01");
        File season2 = new File(showFolder, "Season 02");
        assertTrue(season1.mkdirs());
        assertTrue(season2.mkdirs());

        // Season 1 has a video file
        File ep1 = new File(season1, "Breaking Bad S01E01.mp4");
        writeFile(ep1, "video stream");
        File ep1Nfo = new File(season1, "Breaking Bad S01E01.nfo");
        writeFile(ep1Nfo, "<nfo/>");

        // Season 2 episodes were watched and deleted; only .nfo remains
        File season2Nfo = new File(season2, "season.nfo");
        writeFile(season2Nfo, "<season/>");

        MediaStorageManager.OrphanFolderReport report = MediaStorageManager.cleanMediaSubfolders(tvDir, false);

        assertEquals(1, report.foldersDeleted); // Season 2 deleted
        assertTrue(showFolder.exists());
        assertTrue(season1.exists());
        assertTrue(ep1.exists());
        assertFalse(season2.exists());
    }

    @Test
    public void testTvShowFullyDeletedWhenAllSeasonsAreOrphaned() throws IOException {
        File tvDir = tempFolder.newFolder("TV");
        File showFolder = new File(tvDir, "Finished Show");
        File season1 = new File(showFolder, "Season 01");
        assertTrue(season1.mkdirs());

        File season1Nfo = new File(season1, "season.nfo");
        writeFile(season1Nfo, "<season/>");

        MediaStorageManager.OrphanFolderReport report = MediaStorageManager.cleanMediaSubfolders(tvDir, false);

        // Both Season 01 and the empty Finished Show parent folder should be purged
        assertEquals(2, report.foldersDeleted);
        assertFalse(showFolder.exists());
        assertFalse(season1.exists());
    }

    @Test
    public void testCleanupOrphanMediaFoldersWithMultipleLibraries() throws IOException {
        File root = tempFolder.newFolder("FishbowlMedia");
        File movies = new File(root, "Movies");
        File tv = new File(root, "TV");
        File radarrDownloads = new File(root, "Downloads/radarr");
        assertTrue(movies.mkdirs());
        assertTrue(tv.mkdirs());
        assertTrue(radarrDownloads.mkdirs());

        // 1. Movie with video -> keep
        File goodMovie = new File(movies, "Good (2024)");
        assertTrue(goodMovie.mkdirs());
        writeFile(new File(goodMovie, "Good.mkv"), "video");

        // 2. Movie without video -> delete
        File badMovie = new File(movies, "Bad (2024)");
        assertTrue(badMovie.mkdirs());
        writeFile(new File(badMovie, "Bad.nfo"), "metadata");

        // 3. Download staging orphan -> delete
        File orphanStaging = new File(radarrDownloads, "FinishedTorrentStaging");
        assertTrue(orphanStaging.mkdirs());
        writeFile(new File(orphanStaging, "release.nfo"), "sample");

        MediaStorageManager.OrphanFolderReport report = MediaStorageManager.cleanupOrphanMediaFolders(root);

        assertEquals(2, report.foldersDeleted);
        assertTrue(goodMovie.exists());
        assertFalse(badMovie.exists());
        assertFalse(orphanStaging.exists());
        // Root directories must never be deleted
        assertTrue(movies.exists());
        assertTrue(tv.exists());
        assertTrue(radarrDownloads.exists());
    }

    @Test
    public void testActiveDownloadsExcludedFromCleanup() throws IOException {
        File moviesDir = tempFolder.newFolder("ActiveDownloadsTest");
        File downloadingMovie = new File(moviesDir, "Dune.Part.Two (2024)");
        assertTrue(downloadingMovie.mkdirs());

        // Incomplete download in progress: has .part and metadata
        File partFile = new File(downloadingMovie, "Dune.Part.Two.mkv.part");
        writeFile(partFile, "download in progress...");
        File nfoFile = new File(downloadingMovie, "Dune.Part.Two.nfo");
        writeFile(nfoFile, "<movie/>");

        assertTrue("hasActiveDownloadArtifacts should detect .part file",
                MediaStorageManager.hasActiveDownloadArtifacts(downloadingMovie));

        MediaStorageManager.OrphanFolderReport report = MediaStorageManager.cleanMediaSubfolders(moviesDir, false);

        assertEquals("Folder with active .part file must NOT be deleted/quarantined", 0, report.foldersDeleted);
        assertTrue("Downloading movie directory must remain intact", downloadingMovie.exists());
        assertTrue("Part file must remain intact", partFile.exists());
        assertTrue("Nfo file must remain intact", nfoFile.exists());
    }

    @Test
    public void testPreviewOrphanMediaFoldersDoesNotDelete() throws IOException {
        File root = tempFolder.newFolder("PreviewRoot");
        File movies = new File(root, "Movies");
        assertTrue(movies.mkdirs());

        File orphanMovie = new File(movies, "Orphan (2015)");
        assertTrue(orphanMovie.mkdirs());
        writeFile(new File(orphanMovie, "poster.jpg"), "fake jpg");
        writeFile(new File(orphanMovie, "movie.nfo"), "fake nfo");

        java.util.List<MediaStorageManager.OrphanCandidate> preview = MediaStorageManager.previewOrphanMediaFolders(root);

        assertFalse("Preview list should identify orphan folder", preview.isEmpty());
        boolean found = false;
        for (MediaStorageManager.OrphanCandidate cand : preview) {
            if (cand.folderPath.contains("Orphan (2015)")) {
                found = true;
                assertEquals(2, cand.fileCount);
                break;
            }
        }
        assertTrue("Candidate should match orphan folder", found);
        assertTrue("Folder must NOT be moved or deleted during preview", orphanMovie.exists());
    }

    private static void writeFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }
}
