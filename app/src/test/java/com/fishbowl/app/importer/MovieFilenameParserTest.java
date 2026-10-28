package com.fishbowl.app.importer;

import org.junit.Test;

import static org.junit.Assert.*;

public class MovieFilenameParserTest {

    @Test
    public void testStandardSceneRelease() {
        MovieFilenameParser.ParsedMovie p = MovieFilenameParser.parse("Interstellar.2014.1080p.BluRay.x265.mkv");
        assertEquals(MovieFilenameParser.MediaType.MOVIE, p.mediaType);
        assertEquals("Interstellar", p.title);
        assertEquals(2014, p.year);
        assertEquals("1080P", p.resolution);
        assertEquals("x265", p.codec);
        assertEquals("mkv", p.extension);
        assertEquals("Interstellar (2014)", p.proposedFolderName);
        assertEquals("Interstellar (2014).mkv", p.proposedFileName);
        assertEquals("Interstellar (2014)/Interstellar (2014).mkv", p.proposedRelativePath);
    }

    @Test
    public void testMovieWithNumberInTitleAndYear() {
        MovieFilenameParser.ParsedMovie p = MovieFilenameParser.parse("1917.2019.1080p.mkv");
        assertEquals(MovieFilenameParser.MediaType.MOVIE, p.mediaType);
        assertEquals("1917", p.title);
        assertEquals(2019, p.year);
        assertEquals("1917 (2019)", p.proposedFolderName);
        assertEquals("1917 (2019).mkv", p.proposedFileName);
    }

    @Test
    public void testSpaceOdyssey() {
        MovieFilenameParser.ParsedMovie p = MovieFilenameParser.parse("2001.A.Space.Odyssey.1968.720p.BluRay.x264.mkv");
        assertEquals(MovieFilenameParser.MediaType.MOVIE, p.mediaType);
        assertTrue(p.title.contains("2001 A Space Odyssey"));
        assertEquals(1968, p.year);
        assertEquals("mkv", p.extension);
    }

    @Test
    public void testBladeRunner2049() {
        MovieFilenameParser.ParsedMovie p = MovieFilenameParser.parse("Blade.Runner.2049.2017.Final.Cut.2160p.mkv");
        assertEquals(MovieFilenameParser.MediaType.MOVIE, p.mediaType);
        assertTrue(p.title.contains("Blade Runner 2049"));
        assertEquals(2017, p.year);
        assertEquals("2160P", p.resolution);
    }

    @Test
    public void testParenthesesAndBrackets() {
        MovieFilenameParser.ParsedMovie p = MovieFilenameParser.parse("Spider-Man (2002) [1080p].mp4");
        assertEquals(MovieFilenameParser.MediaType.MOVIE, p.mediaType);
        assertEquals("Spider Man", p.title);
        assertEquals(2002, p.year);
        assertEquals("mp4", p.extension);
    }

    @Test
    public void testMovieWithoutYear() {
        MovieFilenameParser.ParsedMovie p = MovieFilenameParser.parse("Inception.1080p.mkv");
        assertEquals(MovieFilenameParser.MediaType.MOVIE, p.mediaType);
        assertEquals("Inception", p.title);
        assertEquals(0, p.year);
        assertEquals("Inception", p.proposedFolderName);
        assertEquals("Inception.mkv", p.proposedFileName);
    }

    @Test
    public void testSanitizeIllegalFilesystemChars() {
        String sanitized = MovieFilenameParser.sanitizeForFilesystem("Alien: Covenant / Special Edition?");
        assertFalse(sanitized.contains(":"));
        assertFalse(sanitized.contains("/"));
        assertFalse(sanitized.contains("?"));
    }

    @Test
    public void testTvShowStandardSE() {
        String filename = "The.Great.Indian.Kapil.Show.S05E05.1080p.NF.WEB-DL.Hindi.DDP5.1.ESub.x264-HDHub4u.Ag.mkv";
        MovieFilenameParser.ParsedMovie p = MovieFilenameParser.parse(filename);
        assertEquals(MovieFilenameParser.MediaType.TV_SHOW, p.mediaType);
        assertEquals("The Great Indian Kapil Show", p.title);
        assertEquals(5, p.seasonNumber);
        assertEquals(5, p.episodeNumber);
        assertEquals("The Great Indian Kapil Show/Season 05", p.proposedFolderName);
        assertEquals("The Great Indian Kapil Show - S05E05.mkv", p.proposedFileName);
        assertEquals("The Great Indian Kapil Show/Season 05/The Great Indian Kapil Show - S05E05.mkv", p.proposedRelativePath);
    }

    @Test
    public void testTvShowWithYearAndMultiEpisode() {
        String filename = "Stranger.Things.2016.S01E01-E02.1080p.mkv";
        MovieFilenameParser.ParsedMovie p = MovieFilenameParser.parse(filename);
        assertEquals(MovieFilenameParser.MediaType.TV_SHOW, p.mediaType);
        assertEquals("Stranger Things", p.title);
        assertEquals(2016, p.year);
        assertEquals(1, p.seasonNumber);
        assertEquals(1, p.episodeNumber);
        assertEquals(2, p.endEpisodeNumber);
        assertEquals("Stranger Things (2016)/Season 01", p.proposedFolderName);
        assertEquals("Stranger Things - S01E01-E02.mkv", p.proposedFileName);
        assertEquals("Stranger Things (2016)/Season 01/Stranger Things - S01E01-E02.mkv", p.proposedRelativePath);
    }

    @Test
    public void testWebsitePrefixAndIndexCleaning() {
        // Test site prefix cleaning
        String filename1 = "www.1TamilMV.solar_-_Jailer_2_2026_Tamil_HQ_PreDVD_-_1080p_-_x264_-_HQ_Clean_-_AAC_-_6.5GB.mkv";
        MovieFilenameParser.ParsedMovie p1 = MovieFilenameParser.parse(filename1);
        assertEquals("Jailer 2", p1.title);
        assertEquals(2026, p1.year);
        assertEquals("Jailer 2 (2026).mkv", p1.proposedFileName);

        // Test track/index prefix cleaning
        String filename2 = "01. Inception.2010.1080p.mkv";
        MovieFilenameParser.ParsedMovie p2 = MovieFilenameParser.parse(filename2);
        assertEquals("Inception", p2.title);
        assertEquals(2010, p2.year);

        String filename3 = "1 - Gladiator (2000).mp4";
        MovieFilenameParser.ParsedMovie p3 = MovieFilenameParser.parse(filename3);
        assertEquals("Gladiator", p3.title);
        assertEquals(2000, p3.year);

        String filename4 = "[01] Breaking.Bad.S01E01.mkv";
        MovieFilenameParser.ParsedMovie p4 = MovieFilenameParser.parse(filename4);
        assertEquals("Breaking Bad", p4.title);
        assertEquals(1, p4.seasonNumber);
        assertEquals(1, p4.episodeNumber);

        // Test bracket groups with spaces and zero padded indices
        String filename5 = "[YTS MX] 01. Inception.2010.mp4";
        MovieFilenameParser.ParsedMovie p5 = MovieFilenameParser.parse(filename5);
        assertEquals("Inception", p5.title);
        assertEquals(2010, p5.year);

        String filename6 = "01_Tenet.2020.mkv";
        MovieFilenameParser.ParsedMovie p6 = MovieFilenameParser.parse(filename6);
        assertEquals("Tenet", p6.title);
        assertEquals(2020, p6.year);

        String filename7 = "01. 02. The Matrix.1999.mp4";
        MovieFilenameParser.ParsedMovie p7 = MovieFilenameParser.parse(filename7);
        assertEquals("The Matrix", p7.title);
        assertEquals(1999, p7.year);

        String filename8 = "Episode 01 - Pilot.mp4";
        MovieFilenameParser.ParsedMovie p8 = MovieFilenameParser.parse(filename8);
        assertEquals("Pilot", p8.title);

        String filename9 = "Track 01 - Interstellar.2014.mkv";
        MovieFilenameParser.ParsedMovie p9 = MovieFilenameParser.parse(filename9);
        assertEquals("Interstellar", p9.title);
        assertEquals(2014, p9.year);

        // Verify legitimate numbers in titles are preserved
        String filename10 = "12 Monkeys (1995).mkv";
        MovieFilenameParser.ParsedMovie p10 = MovieFilenameParser.parse(filename10);
        assertEquals("12 Monkeys", p10.title);
        assertEquals(1995, p10.year);

        String filename11 = "21 Jump Street.2012.mkv";
        MovieFilenameParser.ParsedMovie p11 = MovieFilenameParser.parse(filename11);
        assertEquals("21 Jump Street", p11.title);
        assertEquals(2012, p11.year);
    }
}
