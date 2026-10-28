package com.fishbowl.app.importer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust filename parser and path sanitizer for Movies and TV Series.
 * Automatically identifies media type (Movie vs TV Show), seasons, and episodes.
 * Formats canonical Jellyfin structures:
 * - Movie:    "Movie Title (Year)/Movie Title (Year).ext"
 * - TV Show:  "Series Title (Year)/Season 01/Series Title - S01E05.ext"
 */
public final class MovieFilenameParser {

    private MovieFilenameParser() {}

    public enum MediaType {
        MOVIE,
        TV_SHOW
    }

    public static final class ParsedMovie {
        public final String originalFilename;
        public final MediaType mediaType;
        public final String title;
        public final int year;
        public final int seasonNumber;      // e.g. 5, or -1 for Movies
        public final int episodeNumber;     // e.g. 5, or -1 for Movies
        public final int endEpisodeNumber;  // e.g. 6 for multi-episodes, or -1
        public final String resolution;
        public final String edition;
        public final String codec;
        public final String extension;
        public final String proposedFolderName;
        public final String proposedFileName;
        public final String proposedRelativePath;

        // Movie Constructor
        public ParsedMovie(String originalFilename, String title, int year, String resolution,
                           String edition, String codec, String extension) {
            this(originalFilename, MediaType.MOVIE, title, year, -1, -1, -1, resolution, edition, codec, extension);
        }

        // Full Media (Movie or TV Show) Constructor
        public ParsedMovie(String originalFilename, MediaType mediaType, String title, int year,
                           int seasonNumber, int episodeNumber, int endEpisodeNumber,
                           String resolution, String edition, String codec, String extension) {
            this.originalFilename = originalFilename != null ? originalFilename.trim() : "";
            this.mediaType = mediaType != null ? mediaType : MediaType.MOVIE;
            this.title = title != null ? title.trim() : "";
            this.year = year;
            this.seasonNumber = seasonNumber;
            this.episodeNumber = episodeNumber;
            this.endEpisodeNumber = endEpisodeNumber;
            this.resolution = resolution != null ? resolution.trim() : "";
            this.edition = edition != null ? edition.trim() : "";
            this.codec = codec != null ? codec.trim() : "";
            this.extension = extension != null ? extension.trim().toLowerCase(Locale.ROOT) : "";

            if (this.mediaType == MediaType.TV_SHOW) {
                int sNum = this.seasonNumber > 0 ? this.seasonNumber : 1;
                int eNum = this.episodeNumber > 0 ? this.episodeNumber : 1;
                String folder = buildSanitizedTvFolder(this.title, this.year, sNum);
                String file = buildSanitizedTvFile(this.title, sNum, eNum, this.endEpisodeNumber, this.extension);
                this.proposedFolderName = folder;
                this.proposedFileName = file;
                this.proposedRelativePath = folder + "/" + file;
            } else {
                String folder = buildSanitizedName(this.title, this.year, "");
                String file = buildSanitizedName(this.title, this.year, this.extension);
                this.proposedFolderName = folder;
                this.proposedFileName = file;
                this.proposedRelativePath = folder + "/" + file;
            }
        }

        @Override
        public String toString() {
            return "ParsedMovie{" +
                    "type=" + mediaType +
                    ", title='" + title + '\'' +
                    ", year=" + year +
                    ", season=" + seasonNumber +
                    ", ep=" + episodeNumber +
                    ", res='" + resolution + '\'' +
                    ", path='" + proposedRelativePath + '\'' +
                    '}';
        }
    }

    private static final Pattern YEAR_PATTERN = Pattern.compile("(?<=^|[^0-9])(19\\d\\d|20\\d\\d)(?=[^0-9]|$)");

    // TV Patterns: S05E05, s01e02, S1E3, S01E01-E02, 5x05, Season 1 Episode 5, EP05
    private static final Pattern TV_PATTERN_S_E = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])s(\\d{1,3})\\s*e(\\d{1,3})(?:[-_ ]*e(\\d{1,3}))?(?:[^a-z0-9]|$)"
    );
    private static final Pattern TV_PATTERN_X = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(\\d{1,2})x(\\d{1,3})(?:[^a-z0-9]|$)"
    );
    private static final Pattern TV_PATTERN_WORDS = Pattern.compile(
            "(?i)\\b(?:season|s)\\s*(\\d{1,3})[.\\s_-]*(?:episode|ep|e)\\s*(\\d{1,3})\\b"
    );
    private static final Pattern TV_PATTERN_EP_ONLY = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:ep|episode)\\s*(\\d{1,3})(?:[^a-z0-9]|$)"
    );

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("(?i)\\b(2160p|4k|uhd|1080p|1080i|720p|576p|480p|360p)\\b");

    private static final Pattern CODEC_PATTERN = Pattern.compile("(?i)\\b(x264|x265|h264|h265|hevc|avc|xvid|divx|av1|vp9)\\b");

    private static final Pattern EDITION_PATTERN = Pattern.compile(
            "(?i)\\b(director'?s\\.cut|extended\\.cut|extended|remastered|unrated|theatrical\\.cut|theatrical|special\\.edition|imax|criterion)\\b"
    );

    private static final Pattern SOURCE_PATTERN = Pattern.compile(
            "(?i)\\b(bluray|blu-ray|bdrip|brrip|web-dl|webdl|web-rip|webrip|hdtv|hdrip|dvdrip|dvd|remux|screener|scr)\\b"
    );

    private static final Pattern AUDIO_TAGS = Pattern.compile(
            "(?i)\\b(aac|ac3|eac3|ddp|dd5\\.1|dts|dts-hd|truehd|atmos|flac|mp3|5\\.1|7\\.1)\\b"
    );

    private static final Pattern NOISE_TAGS = Pattern.compile(
            "(?i)\\b(hdr|hdr10|hdr10plus|dv|dovi|10bit|8bit|sdr|proper|repack|rerip|multi|dual\\.audio|complete|internal|yts|yify|rarbg|ettv|fgt|sparks|evo|etrg)\\b"
    );

    private static final Pattern WEBSITE_TAGS = Pattern.compile(
            "(?i)^(?:https?://)?(?:www\\.)[a-zA-Z0-9_-]+\\.[a-zA-Z]{2,6}\\s*[-_.]*\\s*|^[a-zA-Z0-9_-]+\\.(?:solar|wf|nl|org|com|net|vip|cc|biz|info|site|live|xyz|link)\\s*[-_]+\\s*"
    );

    private static final Pattern BRACKET_PREFIX_TAGS = Pattern.compile(
            "^[\\[\\(\\{][^\\]\\)\\}]+[\\]\\)\\}]\\s*[-_.]*\\s*"
    );

    private static final Pattern TRACK_PREFIX_TAGS = Pattern.compile(
            "(?i)^(?:track|cd|disc|disk|part|pt|episode|ep)[.\\s_-]*\\d{1,3}[.\\s_-]*"
    );

    private static final Pattern ZERO_PADDED_INDEX = Pattern.compile(
            "^0\\d{1,3}(?:[.\\s_#-]+|\\s+)"
    );

    private static final Pattern NUMBER_WITH_SEP = Pattern.compile(
            "^(?:#\\s*\\d{1,3}[.\\s_-]*|\\d{1,3}(?:\\.\\d{1,2})?[\\s._-]*[-_.:][\\s._-]*|\\d{1,3}\\.\\s*)"
    );

    private static final Pattern BRACKETED_INDEX = Pattern.compile(
            "^[\\[\\(\\{]#?\\d{1,3}[\\]\\)\\}]\\s*[-_.]*\\s*"
    );

    /**
     * Recursively and iteratively strips website tags, release group brackets,
     * track markers, and index numbers until none remain.
     */
    public static String cleanPrefixes(String text) {
        if (text == null) return "";
        String s = text.trim();
        String prev;
        do {
            prev = s;
            s = WEBSITE_TAGS.matcher(s).replaceFirst("");
            s = TRACK_PREFIX_TAGS.matcher(s).replaceFirst("");
            s = BRACKET_PREFIX_TAGS.matcher(s).replaceFirst("");
            s = BRACKETED_INDEX.matcher(s).replaceFirst("");
            s = ZERO_PADDED_INDEX.matcher(s).replaceFirst("");
            s = NUMBER_WITH_SEP.matcher(s).replaceFirst("");
            s = s.trim();
        } while (!s.equals(prev) && !s.isEmpty());
        return s;
    }

    /**
     * Parses a raw media filename into a structured ParsedMovie (supporting Movies & TV Shows).
     */
    public static ParsedMovie parse(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return new ParsedMovie("", "Unknown Media", 0, "", "", "", "");
        }

        String clean = rawName.trim();

        // Iteratively strip website prefixes, brackets, track markers, and index numbers early
        clean = cleanPrefixes(clean);

        String extension = "";
        int lastDot = clean.lastIndexOf('.');
        if (lastDot > 0 && lastDot < clean.length() - 1) {
            String candidateExt = clean.substring(lastDot + 1);
            if (candidateExt.length() <= 5 && !candidateExt.matches(".*\\d{3,}.*")) {
                extension = candidateExt;
                clean = clean.substring(0, lastDot);
            }
        }

        // Extract resolution
        String resolution = "";
        Matcher resMatch = RESOLUTION_PATTERN.matcher(clean);
        if (resMatch.find()) {
            resolution = resMatch.group(1).toUpperCase(Locale.ROOT);
        }

        // Extract codec
        String codec = "";
        Matcher codecMatch = CODEC_PATTERN.matcher(clean);
        if (codecMatch.find()) {
            codec = codecMatch.group(1).toLowerCase(Locale.ROOT);
        }

        // Extract edition
        String edition = "";
        Matcher edMatch = EDITION_PATTERN.matcher(clean);
        if (edMatch.find()) {
            edition = cleanTokenName(edMatch.group(1));
        }

        // 1. Check for TV Show Patterns (Season & Episode)
        MediaType detectedType = MediaType.MOVIE;
        int seasonNum = -1;
        int episodeNum = -1;
        int endEpisodeNum = -1;
        int tvMatchStart = -1;

        Matcher tvMatch1 = TV_PATTERN_S_E.matcher(clean);
        if (tvMatch1.find()) {
            detectedType = MediaType.TV_SHOW;
            seasonNum = Integer.parseInt(tvMatch1.group(1));
            episodeNum = Integer.parseInt(tvMatch1.group(2));
            if (tvMatch1.group(3) != null) {
                endEpisodeNum = Integer.parseInt(tvMatch1.group(3));
            }
            tvMatchStart = tvMatch1.start();
        } else {
            Matcher tvMatch2 = TV_PATTERN_X.matcher(clean);
            if (tvMatch2.find()) {
                detectedType = MediaType.TV_SHOW;
                seasonNum = Integer.parseInt(tvMatch2.group(1));
                episodeNum = Integer.parseInt(tvMatch2.group(2));
                tvMatchStart = tvMatch2.start();
            } else {
                Matcher tvMatch3 = TV_PATTERN_WORDS.matcher(clean);
                if (tvMatch3.find()) {
                    detectedType = MediaType.TV_SHOW;
                    seasonNum = Integer.parseInt(tvMatch3.group(1));
                    episodeNum = Integer.parseInt(tvMatch3.group(2));
                    tvMatchStart = tvMatch3.start();
                } else {
                    Matcher tvMatch4 = TV_PATTERN_EP_ONLY.matcher(clean);
                    if (tvMatch4.find()) {
                        detectedType = MediaType.TV_SHOW;
                        seasonNum = 1;
                        episodeNum = Integer.parseInt(tvMatch4.group(1));
                        tvMatchStart = tvMatch4.start();
                    }
                }
            }
        }

        // 2. Identify Year candidates
        int detectedYear = 0;
        int yearStartIndex = -1;

        // Find resolution or source boundary index
        int techBoundary = clean.length();
        Matcher boundMatch = Pattern.compile("(?i)\\b(2160p|4k|1080p|720p|480p|bluray|web-?dl|web-?rip|remux)\\b").matcher(clean);
        if (boundMatch.find()) {
            techBoundary = boundMatch.start();
        }

        int yearSearchLimit = (detectedType == MediaType.TV_SHOW && tvMatchStart > 0) ? tvMatchStart : techBoundary;

        Matcher yearMatch = YEAR_PATTERN.matcher(clean);
        while (yearMatch.find()) {
            int yStart = yearMatch.start();
            int yVal = Integer.parseInt(yearMatch.group(1));
            int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);

            if (yVal >= 1888 && yVal <= currentYear + 2) {
                if (yStart <= yearSearchLimit || detectedYear == 0) {
                    detectedYear = yVal;
                    yearStartIndex = yStart;
                }
            }
        }

        // 3. Title Extraction
        String rawTitleSection;
        if (detectedType == MediaType.TV_SHOW && tvMatchStart > 0) {
            // Text before S01E05
            String preTv = clean.substring(0, tvMatchStart);
            if (yearStartIndex > 0 && yearStartIndex < tvMatchStart) {
                rawTitleSection = preTv.substring(0, yearStartIndex);
            } else {
                rawTitleSection = preTv;
            }
        } else if (yearStartIndex > 0) {
            rawTitleSection = clean.substring(0, yearStartIndex);
        } else if (techBoundary < clean.length()) {
            rawTitleSection = clean.substring(0, techBoundary);
        } else {
            rawTitleSection = clean;
        }

        String title = sanitizeTitle(rawTitleSection);
        if (title.isEmpty()) {
            title = sanitizeTitle(clean);
            if (title.isEmpty()) {
                title = detectedType == MediaType.TV_SHOW ? "Unknown Series" : "Unknown Movie";
            }
        }

        return new ParsedMovie(
                rawName,
                detectedType,
                title,
                detectedYear,
                seasonNum,
                episodeNum,
                endEpisodeNum,
                resolution,
                edition,
                codec,
                extension
        );
    }

    private static String sanitizeTitle(String text) {
        if (text == null) return "";

        String s = cleanPrefixes(text);

        // Remove brackets, parentheses, curly braces
        s = s.replaceAll("[\\[\\]\\(\\)\\{\\}]", " ");

        // Replace dots, underscores, dashes with spaces
        s = s.replace('.', ' ')
             .replace('_', ' ')
             .replace('-', ' ')
             .replace('+', ' ');

        // Strip known noise words
        s = NOISE_TAGS.matcher(s).replaceAll(" ");
        s = AUDIO_TAGS.matcher(s).replaceAll(" ");
        s = SOURCE_PATTERN.matcher(s).replaceAll(" ");
        s = RESOLUTION_PATTERN.matcher(s).replaceAll(" ");
        s = CODEC_PATTERN.matcher(s).replaceAll(" ");

        // Compress multiple spaces and trim
        s = s.replaceAll("\\s+", " ").trim();

        // Run prefix cleaner again to catch any residual index exposed after noise tag removal
        s = cleanPrefixes(s);

        // Final safety: if a title starts with a zero-padded index (e.g. "01 Movie"), strip it
        s = s.replaceFirst("^0\\d+\\s+", "").trim();

        // Capitalize words cleanly
        return toProperCase(s);
    }

    private static String cleanTokenName(String token) {
        if (token == null) return "";
        return toProperCase(token.replace('.', ' ').replace('_', ' ').replace('-', ' ').trim());
    }

    private static String toProperCase(String s) {
        if (s == null || s.isEmpty()) return "";
        String[] words = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            if (w.length() == 1) {
                sb.append(w.toUpperCase(Locale.ROOT));
            } else {
                sb.append(Character.toUpperCase(w.charAt(0)))
                  .append(w.substring(1));
            }
            if (i < words.length - 1) sb.append(" ");
        }
        return sb.toString().trim();
    }

    /**
     * Sanitizes illegal filesystem characters for cross-platform storage.
     */
    public static String sanitizeForFilesystem(String name) {
        if (name == null || name.trim().isEmpty()) return "Unknown";
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|\\x00]", " ")
                               .replaceAll("\\s+", " ")
                               .trim();
        while (sanitized.endsWith(".") || sanitized.endsWith(" ")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        return sanitized.isEmpty() ? "Unknown" : sanitized;
    }

    /**
     * Standard Jellyfin Movie Naming:
     * Folder: "Movie Title (Year)"
     * File: "Movie Title (Year).ext"
     */
    public static String buildSanitizedName(String title, int year, String extension) {
        String cleanTitle = sanitizeForFilesystem(title);
        StringBuilder sb = new StringBuilder(cleanTitle);
        if (year > 1800) {
            sb.append(" (").append(year).append(")");
        }
        if (extension != null && !extension.trim().isEmpty()) {
            String ext = extension.trim();
            if (ext.startsWith(".")) ext = ext.substring(1);
            sb.append(".").append(ext.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /**
     * Standard Jellyfin TV Show Folder Naming:
     * "Series Title (Year)/Season 01" or "Series Title/Season 01"
     */
    public static String buildSanitizedTvFolder(String seriesTitle, int year, int season) {
        String cleanTitle = sanitizeForFilesystem(seriesTitle);
        StringBuilder sb = new StringBuilder(cleanTitle);
        if (year > 1800) {
            sb.append(" (").append(year).append(")");
        }
        sb.append("/Season ");
        sb.append(String.format(Locale.ROOT, "%02d", season > 0 ? season : 1));
        return sb.toString();
    }

    /**
     * Standard Jellyfin TV Show File Naming:
     * "Series Title - S01E05.ext" or "Series Title - S01E05-E06.ext"
     */
    public static String buildSanitizedTvFile(String seriesTitle, int season, int episode, int endEpisode, String extension) {
        String cleanTitle = sanitizeForFilesystem(seriesTitle);
        StringBuilder sb = new StringBuilder(cleanTitle);
        int s = season > 0 ? season : 1;
        int e = episode > 0 ? episode : 1;
        sb.append(String.format(Locale.ROOT, " - S%02dE%02d", s, e));
        if (endEpisode > e) {
            sb.append(String.format(Locale.ROOT, "-E%02d", endEpisode));
        }
        if (extension != null && !extension.trim().isEmpty()) {
            String ext = extension.trim();
            if (ext.startsWith(".")) ext = ext.substring(1);
            sb.append(".").append(ext.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
