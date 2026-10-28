package com.fishbowl.app.importer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * TMDB (The Movie Database) metadata provider with title ranking and local heuristic fallback.
 */
public final class TmdbMetadataProvider implements MovieMetadataProvider {

    private static final String TAG = "TmdbMetadataProvider";
    public static final String PREF_NAME = "fishbowl_importer";
    public static final String KEY_TMDB_API_KEY = "tmdb_api_key";
    private static final String TMDB_SEARCH_URL = "https://api.themoviedb.org/3/search/movie";
    private static final String TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w342";

    @Override
    public List<MovieMetadata> searchMovie(String queryTitle, int year, Context context) {
        List<MovieMetadata> results = new ArrayList<>();
        if (queryTitle == null || queryTitle.trim().isEmpty()) {
            return results;
        }

        String apiKey = getApiKey(context);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            try {
                results = searchTmdbApi(queryTitle.trim(), year, apiKey.trim());
            } catch (Throwable t) {
                Log.w(TAG, "TMDB API search error: " + t.getMessage());
            }
        }

        // If TMDB returns results, return sorted results
        if (!results.isEmpty()) {
            return results;
        }

        // Local Heuristic Fallback
        double localConfidence = year > 1888 ? 0.85 : 0.60;
        results.add(new MovieMetadata(
                queryTitle,
                year,
                queryTitle,
                "Metadata parsed from filename (Configure TMDB API key in settings for posters & overviews)",
                "",
                0,
                localConfidence,
                "Local Heuristic"
        ));
        return results;
    }

    private List<MovieMetadata> searchTmdbApi(String title, int year, String apiKey) throws Exception {
        List<MovieMetadata> list = new ArrayList<>();
        StringBuilder urlBuilder = new StringBuilder(TMDB_SEARCH_URL);
        urlBuilder.append("?api_key=").append(URLEncoder.encode(apiKey, "UTF-8"))
                  .append("&query=").append(URLEncoder.encode(title, "UTF-8"))
                  .append("&include_adult=false");

        if (year > 1888) {
            urlBuilder.append("&year=").append(year);
        }

        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code != 200) {
            Log.w(TAG, "TMDB responded with HTTP " + code);
            conn.disconnect();
            return list;
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        } finally {
            conn.disconnect();
        }

        JSONObject json = new JSONObject(response.toString());
        JSONArray arr = json.optJSONArray("results");
        if (arr == null) return list;

        for (int i = 0; i < Math.min(arr.length(), 8); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;

            String resTitle = obj.optString("title", "");
            String origTitle = obj.optString("original_title", "");
            String releaseDate = obj.optString("release_date", "");
            int resYear = 0;
            if (releaseDate.length() >= 4) {
                try {
                    resYear = Integer.parseInt(releaseDate.substring(0, 4));
                } catch (Throwable ignored) {}
            }

            String overview = obj.optString("overview", "");
            String posterPath = obj.optString("poster_path", "");
            String fullPosterUrl = (posterPath != null && !posterPath.isEmpty())
                    ? TMDB_POSTER_BASE + posterPath
                    : "";

            long tmdbId = obj.optLong("id", 0);

            // Calculate confidence score based on title similarity and year match
            double similarity = calculateStringSimilarity(title, resTitle);
            double yearScore = 0.0;
            if (year > 0 && resYear > 0) {
                int diff = Math.abs(year - resYear);
                if (diff == 0) yearScore = 0.30;
                else if (diff == 1) yearScore = 0.15;
            } else {
                yearScore = 0.15; // Neutral when unknown
            }
            double confidence = Math.min(1.0, (similarity * 0.70) + yearScore);

            list.add(new MovieMetadata(
                    resTitle,
                    resYear,
                    origTitle,
                    overview,
                    fullPosterUrl,
                    tmdbId,
                    confidence,
                    "TMDB"
            ));
        }

        // Sort descending by confidence
        Collections.sort(list, (a, b) -> Double.compare(b.confidence, a.confidence));
        return list;
    }

    public static String getApiKey(Context context) {
        if (context == null) return null;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_TMDB_API_KEY, null);
    }

    public static void setApiKey(Context context, String apiKey) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TMDB_API_KEY, apiKey != null ? apiKey.trim() : "")
                .apply();
    }

    /**
     * Bigram Jaccard similarity between two strings.
     */
    public static double calculateStringSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        String a = s1.trim().toLowerCase(Locale.ROOT);
        String b = s2.trim().toLowerCase(Locale.ROOT);
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        List<String> pairsA = getBigrams(a);
        List<String> pairsB = getBigrams(b);
        if (pairsA.isEmpty() || pairsB.isEmpty()) {
            return a.contains(b) || b.contains(a) ? 0.75 : 0.0;
        }

        int intersection = 0;
        List<String> bCopy = new ArrayList<>(pairsB);
        for (String p : pairsA) {
            if (bCopy.remove(p)) {
                intersection++;
            }
        }
        int union = pairsA.size() + pairsB.size() - intersection;
        return (double) intersection / (double) union;
    }

    private static List<String> getBigrams(String s) {
        List<String> bigrams = new ArrayList<>();
        for (int i = 0; i < s.length() - 1; i++) {
            bigrams.add(s.substring(i, i + 2));
        }
        return bigrams;
    }
}
