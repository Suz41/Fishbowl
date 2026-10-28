package com.fishbowl.app.importer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent journal tracking import operations, state transitions, and processed download signatures.
 */
public final class MovieImportJournal {

    private static final String TAG = "MovieImportJournal";
    private static final String JOURNAL_FILE = "movie_import_journal.json";
    private static final String PREF_NAME = "fishbowl_importer_signatures";
    private static final String KEY_PROCESSED_SIGNATURES = "processed_signatures";

    public static final class ImportRecord {
        public String id;
        public String sourceUriString;
        public String sourcePath;
        public String sourceFilename;
        public String destinationPath;
        public String mediaType; // MOVIE, TV_SHOW
        public String movieTitle;
        public int movieYear;
        public int seasonNumber;
        public int episodeNumber;
        public long fileSizeBytes;
        public String status; // PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
        public String operation; // COPY, MOVE
        public long timestampMs;
        public String errorMessage;
        public boolean retainOriginal;

        public ImportRecord() {}

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("sourceUriString", sourceUriString);
                obj.put("sourcePath", sourcePath != null ? sourcePath : "");
                obj.put("sourceFilename", sourceFilename);
                obj.put("destinationPath", destinationPath);
                obj.put("mediaType", mediaType != null ? mediaType : "MOVIE");
                obj.put("movieTitle", movieTitle);
                obj.put("movieYear", movieYear);
                obj.put("seasonNumber", seasonNumber);
                obj.put("episodeNumber", episodeNumber);
                obj.put("fileSizeBytes", fileSizeBytes);
                obj.put("status", status);
                obj.put("operation", operation);
                obj.put("timestampMs", timestampMs);
                obj.put("errorMessage", errorMessage != null ? errorMessage : "");
                obj.put("retainOriginal", retainOriginal);
            } catch (Throwable ignored) {}
            return obj;
        }

        public static ImportRecord fromJson(JSONObject obj) {
            if (obj == null) return null;
            ImportRecord r = new ImportRecord();
            r.id = obj.optString("id", java.util.UUID.randomUUID().toString());
            r.sourceUriString = obj.optString("sourceUriString", "");
            r.sourcePath = obj.optString("sourcePath", "");
            r.sourceFilename = obj.optString("sourceFilename", "");
            r.destinationPath = obj.optString("destinationPath", "");
            r.mediaType = obj.optString("mediaType", "MOVIE");
            r.movieTitle = obj.optString("movieTitle", "");
            r.movieYear = obj.optInt("movieYear", 0);
            r.seasonNumber = obj.optInt("seasonNumber", -1);
            r.episodeNumber = obj.optInt("episodeNumber", -1);
            r.fileSizeBytes = obj.optLong("fileSizeBytes", 0);
            r.status = obj.optString("status", "PENDING");
            r.operation = obj.optString("operation", "COPY");
            r.timestampMs = obj.optLong("timestampMs", System.currentTimeMillis());
            r.errorMessage = obj.optString("errorMessage", "");
            r.retainOriginal = obj.optBoolean("retainOriginal", true);
            return r;
        }
    }

    private static MovieImportJournal instance;
    private final Context appContext;
    private final List<ImportRecord> records = new ArrayList<>();

    public static synchronized MovieImportJournal getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new MovieImportJournal(context.getApplicationContext());
        }
        return instance;
    }

    private MovieImportJournal(Context context) {
        this.appContext = context;
        loadJournal();
    }

    public synchronized void addRecord(ImportRecord record) {
        if (record == null) return;
        records.add(0, record); // Most recent first
        saveJournal();
    }

    public synchronized void updateRecord(ImportRecord record) {
        if (record == null || record.id == null) return;
        for (int i = 0; i < records.size(); i++) {
            if (record.id.equals(records.get(i).id)) {
                records.set(i, record);
                saveJournal();
                return;
            }
        }
        records.add(0, record);
        saveJournal();
    }

    public synchronized List<ImportRecord> getAllRecords() {
        return new ArrayList<>(records);
    }

    public synchronized List<ImportRecord> getRecentHistory(int limit) {
        int max = Math.min(records.size(), Math.max(1, limit));
        return new ArrayList<>(records.subList(0, max));
    }

    public synchronized List<ImportRecord> getPendingOrActiveRecords() {
        List<ImportRecord> list = new ArrayList<>();
        for (ImportRecord r : records) {
            if ("PENDING".equals(r.status) || "IN_PROGRESS".equals(r.status)) {
                list.add(r);
            }
        }
        return list;
    }

    public synchronized void clearHistory() {
        records.clear();
        saveJournal();
    }

    public synchronized boolean isAlreadyImported(String filename, String uriString, String path) {
        if (filename == null && uriString == null && path == null) return false;
        for (ImportRecord r : records) {
            if ("COMPLETED".equals(r.status) || "SKIPPED".equals(r.status)) {
                if (filename != null && !filename.isEmpty()) {
                    if (filename.equalsIgnoreCase(r.sourceFilename)) {
                        return true;
                    }
                    if (r.destinationPath != null && (r.destinationPath.endsWith("/" + filename) || r.destinationPath.equalsIgnoreCase(filename))) {
                        return true;
                    }
                }
                if (uriString != null && !uriString.isEmpty() && uriString.equals(r.sourceUriString)) {
                    return true;
                }
                if (path != null && !path.isEmpty()) {
                    if (path.equalsIgnoreCase(r.sourcePath) || path.equalsIgnoreCase(r.destinationPath)) {
                        return true;
                    }
                    if (r.destinationPath != null && r.destinationPath.equalsIgnoreCase(path)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public synchronized boolean isAlreadyImported(String filename, String uriString) {
        return isAlreadyImported(filename, uriString, null);
    }

    // -- Download Signature Debouncing -----------------------------------------

    public boolean isSignatureProcessed(String signature) {
        if (signature == null || signature.isEmpty() || appContext == null) return false;
        Set<String> set = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_PROCESSED_SIGNATURES, Collections.emptySet());
        return set.contains(signature);
    }

    public void recordSignatureProcessed(String signature) {
        if (signature == null || signature.isEmpty() || appContext == null) return;
        try {
            android.content.SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            Set<String> set = new HashSet<>(sp.getStringSet(KEY_PROCESSED_SIGNATURES, Collections.emptySet()));
            set.add(signature);
            // Limit signature cache size to 500 entries
            if (set.size() > 500) {
                List<String> list = new ArrayList<>(set);
                set = new HashSet<>(list.subList(list.size() - 400, list.size()));
            }
            sp.edit().putStringSet(KEY_PROCESSED_SIGNATURES, set).apply();
        } catch (Throwable t) {
            Log.w(TAG, "Error recording signature: " + t.getMessage());
        }
    }

    // -- Persistence -----------------------------------------------------------

    private synchronized void loadJournal() {
        records.clear();
        File file = new File(appContext.getFilesDir(), JOURNAL_FILE);
        if (!file.exists()) return;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int read = fis.read(b);
            if (read > 0) {
                JSONArray arr = new JSONArray(new String(b, StandardCharsets.UTF_8));
                for (int i = 0; i < arr.length(); i++) {
                    ImportRecord r = ImportRecord.fromJson(arr.optJSONObject(i));
                    if (r != null) {
                        // Reconcile IN_PROGRESS items on app restart (mark as interrupted)
                        if ("IN_PROGRESS".equals(r.status)) {
                            r.status = "FAILED";
                            r.errorMessage = "Interrupted by process death or system shutdown";
                        }
                        records.add(r);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load movie import journal: " + t.getMessage());
        }
    }

    private synchronized void saveJournal() {
        File file = new File(appContext.getFilesDir(), JOURNAL_FILE);
        File tmp = new File(appContext.getFilesDir(), JOURNAL_FILE + ".tmp");
        try {
            JSONArray arr = new JSONArray();
            // Cap journal at 2000 historical records
            int max = Math.min(records.size(), 2000);
            for (int i = 0; i < max; i++) {
                arr.put(records.get(i).toJson());
            }
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (tmp.exists()) {
                if (file.exists()) file.delete();
                tmp.renameTo(file);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to save movie import journal: " + t.getMessage());
        }
    }
}
