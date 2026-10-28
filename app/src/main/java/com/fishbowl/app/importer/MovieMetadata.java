package com.fishbowl.app.importer;

import java.io.Serializable;
import java.util.Locale;

/**
 * Data model representing candidate media metadata (Movie or TV Show)
 * from local parsing, TMDB, or user edits.
 */
public final class MovieMetadata implements Serializable {

    private static final long serialVersionUID = 2L;

    public final MovieFilenameParser.MediaType mediaType;
    public final String title;
    public final int year;
    public final int seasonNumber;      // e.g. 5, or -1 for Movies
    public final int episodeNumber;     // e.g. 5, or -1 for Movies
    public final int endEpisodeNumber;  // e.g. 6 or -1
    public final String originalTitle;
    public final String overview;
    public final String posterUrl;
    public final long tmdbId;
    public final double confidence;
    public final String source;

    public MovieMetadata(String title, int year, String originalTitle, String overview,
                         String posterUrl, long tmdbId, double confidence, String source) {
        this(MovieFilenameParser.MediaType.MOVIE, title, year, -1, -1, -1, originalTitle, overview, posterUrl, tmdbId, confidence, source);
    }

    public MovieMetadata(MovieFilenameParser.MediaType mediaType, String title, int year,
                         int seasonNumber, int episodeNumber, int endEpisodeNumber,
                         String originalTitle, String overview, String posterUrl,
                         long tmdbId, double confidence, String source) {
        this.mediaType = mediaType != null ? mediaType : MovieFilenameParser.MediaType.MOVIE;
        this.title = title != null ? title.trim() : "";
        this.year = year;
        this.seasonNumber = seasonNumber;
        this.episodeNumber = episodeNumber;
        this.endEpisodeNumber = endEpisodeNumber;
        this.originalTitle = originalTitle != null ? originalTitle.trim() : "";
        this.overview = overview != null ? overview.trim() : "";
        this.posterUrl = posterUrl != null ? posterUrl.trim() : "";
        this.tmdbId = tmdbId;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.source = source != null ? source.trim() : "Local";
    }

    public static MovieMetadata fromParsed(MovieFilenameParser.ParsedMovie parsed) {
        if (parsed == null) {
            return new MovieMetadata(MovieFilenameParser.MediaType.MOVIE, "Unknown Media", 0, -1, -1, -1, "", "", "", 0, 0.0, "None");
        }
        double conf = parsed.year > 0 ? 0.85 : 0.60;
        return new MovieMetadata(
                parsed.mediaType,
                parsed.title,
                parsed.year,
                parsed.seasonNumber,
                parsed.episodeNumber,
                parsed.endEpisodeNumber,
                parsed.title,
                "Extracted from filename: " + parsed.originalFilename,
                "",
                0,
                conf,
                "Local Heuristic"
        );
    }

    public String getDisplayTitle() {
        if (mediaType == MovieFilenameParser.MediaType.TV_SHOW && seasonNumber > 0 && episodeNumber > 0) {
            String epTag = String.format(Locale.ROOT, "S%02dE%02d", seasonNumber, episodeNumber);
            if (year > 1800) {
                return String.format(Locale.ROOT, "%s (%d) - %s", title, year, epTag);
            }
            return String.format(Locale.ROOT, "%s - %s", title, epTag);
        }
        if (year > 1800) {
            return String.format(Locale.ROOT, "%s (%d)", title, year);
        }
        return title;
    }

    public boolean hasPoster() {
        return posterUrl != null && !posterUrl.trim().isEmpty() && posterUrl.startsWith("http");
    }

    @Override
    public String toString() {
        return "MovieMetadata{" +
                "type=" + mediaType +
                ", title='" + title + '\'' +
                ", year=" + year +
                ", s=" + seasonNumber +
                ", ep=" + episodeNumber +
                ", confidence=" + String.format(Locale.ROOT, "%.2f", confidence) +
                ", source='" + source + '\'' +
                ", poster=" + hasPoster() +
                '}';
    }
}
