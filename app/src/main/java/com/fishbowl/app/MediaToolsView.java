package com.fishbowl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fishbowl.app.importer.DownloadDetectionManager;
import com.fishbowl.app.importer.MovieImportActivity;
import com.fishbowl.app.importer.MovieImportJournal;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Media tab tools: a live Media Storage card (usage per library folder, retention policy) and a
 * Movie Importer card (download detection switches, journal statistics, quick actions).
 */
public final class MediaToolsView {

    private static final int MAX_FILES_PER_FOLDER = 60000;

    private final Activity act;
    private final LinearLayout root;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private LinearLayout storageBody;
    private LinearLayout importerBody;
    private long lastScan = 0;
    private boolean scanning = false;

    public MediaToolsView(Activity activity) {
        this.act = activity;
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(buildStorageCard());
        root.addView(buildImporterCard());
    }

    public View getView() {
        return root;
    }

    /** Re-reads the importer statistics immediately and the storage sizes at most every 30 s (unless forced). */
    public void refresh(boolean force) {
        renderImporter();
        long now = System.currentTimeMillis();
        if (force || now - lastScan > 30000L) scanStorage();
    }

    // ------------------------------------------------------------------ storage card

    private View buildStorageCard() {
        LinearLayout c = card();
        c.addView(title("MEDIA STORAGE"));
        storageBody = new LinearLayout(act);
        storageBody.setOrientation(LinearLayout.VERTICAL);
        c.addView(storageBody);
        TextView loading = muted(I18n.tr("Calculating..."));
        storageBody.addView(loading);
        return c;
    }

    private static final class Entry {
        final String label;
        final File dir;
        long bytes;
        long files;

        Entry(String label, File dir) {
            this.label = label;
            this.dir = dir;
        }
    }

    private void scanStorage() {
        if (scanning) return;
        scanning = true;
        final MediaStorageManager sm = MediaStorageManager.getInstance(act);
        bg.execute(() -> {
            final Entry[] entries = {
                    new Entry("Movies", sm.getMoviesDir()),
                    new Entry("TV Shows", sm.getTvDir()),
                    new Entry("Music", sm.getMusicDir()),
                    new Entry("Books", sm.getBooksDir()),
                    new Entry("Downloads", sm.getDownloadsDir()),
                    new Entry("Backups", sm.getBackupsDir()),
            };
            for (Entry e : entries) {
                long[] acc = new long[2];
                size(e.dir, acc, 0);
                e.bytes = acc[0];
                e.files = acc[1];
            }
            long free = 0;
            long total = 0;
            File root = sm.getMediaRoot();
            try {
                File probe = root;
                while (probe != null && !probe.exists()) probe = probe.getParentFile();
                if (probe != null) {
                    StatFs fs = new StatFs(probe.getAbsolutePath());
                    free = fs.getAvailableBytes();
                    total = fs.getTotalBytes();
                }
            } catch (Throwable ignored) {}
            final long fFree = free;
            final long fTotal = total;
            final String rootPath = root.getAbsolutePath();
            act.runOnUiThread(() -> {
                scanning = false;
                lastScan = System.currentTimeMillis();
                renderStorage(entries, fFree, fTotal, rootPath);
            });
        });
    }

    /** Bounded recursive size; does not follow symlinks into other trees. */
    private void size(File f, long[] acc, int depth) {
        if (f == null || acc[1] > MAX_FILES_PER_FOLDER || depth > 12) return;
        File[] kids = f.listFiles();
        if (kids == null) {
            if (f.isFile()) {
                acc[0] += f.length();
                acc[1]++;
            }
            return;
        }
        for (File k : kids) {
            if (acc[1] > MAX_FILES_PER_FOLDER) return;
            if (k.isDirectory()) size(k, acc, depth + 1);
            else {
                acc[0] += k.length();
                acc[1]++;
            }
        }
    }

    private void renderStorage(Entry[] entries, long free, long total, String rootPath) {
        storageBody.removeAllViews();
        final MediaStorageManager sm = MediaStorageManager.getInstance(act);

        long used = 0;
        long max = 1;
        for (Entry e : entries) {
            used += e.bytes;
            max = Math.max(max, e.bytes);
        }

        if (total > 0) {
            TextView t = text(fmt(free) + " " + I18n.tr("free of") + " " + fmt(total) + "  •  "
                    + fmt(used) + " " + I18n.tr("in media folders"), 12f, AppTheme.text(), true);
            storageBody.addView(t);
            storageBody.addView(bar((int) Math.max(1, Math.min(100, 100 * (total - free) / total)), AppTheme.accent()),
                    barLp(6));
        }
        TextView path = text(rootPath, 10.5f, AppTheme.textMuted(), false);
        path.setTypeface(Typeface.MONOSPACE);
        path.setPadding(0, dp(4), 0, dp(8));
        storageBody.addView(path);

        for (Entry e : entries) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));
            LinearLayout line = new LinearLayout(act);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.addView(text(I18n.tr(e.label), 12f, AppTheme.text(), false),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            String files = e.files > MAX_FILES_PER_FOLDER ? MAX_FILES_PER_FOLDER + "+" : String.valueOf(e.files);
            line.addView(text(fmt(e.bytes) + "  •  " + files + " " + I18n.tr("files"), 11f, AppTheme.textSecondary(), false));
            row.addView(line);
            row.addView(bar((int) (100 * e.bytes / max), AppTheme.accent()), barLp(4));
            final File dir = e.dir;
            row.setOnClickListener(v -> copy(dir.getAbsolutePath()));
            storageBody.addView(row);
        }

        // Retention policy (how long the Downloads copy stays after a library import)
        TextView ret = text(I18n.tr("Staging retention") + ": " + I18n.tr(sm.getRetentionPolicyDisplayName()),
                11.5f, AppTheme.textSecondary(), false);
        ret.setPadding(0, dp(10), 0, dp(2));
        storageBody.addView(ret);

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(button(I18n.tr("RETENTION"), AppTheme.accent(), v -> showRetentionDialog()), actionLp(true));
        actions.addView(button(I18n.tr("REFRESH"), AppTheme.textSecondary(), v -> {
            storageBody.removeAllViews();
            storageBody.addView(muted(I18n.tr("Calculating...")));
            scanStorage();
        }), actionLp(true));
        actions.addView(button(I18n.tr("MANAGE"), AppTheme.accent(),
                v -> act.startActivity(new Intent(act, JellyfinStorageActivity.class))), actionLp(false));
        storageBody.addView(actions);
    }

    private void showRetentionDialog() {
        final MediaStorageManager sm = MediaStorageManager.getInstance(act);
        final String[] values = {
                MediaStorageManager.RETENTION_IMMEDIATE_ON_IMPORT,
                MediaStorageManager.RETENTION_UNTIL_TORRENT_REMOVED,
                MediaStorageManager.RETENTION_24_HOURS,
                MediaStorageManager.RETENTION_3_DAYS,
                MediaStorageManager.RETENTION_7_DAYS,
                MediaStorageManager.RETENTION_MANUAL};
        final String[] labels = {
                I18n.tr("Immediately after import (no duplicates)"),
                I18n.tr("Until the torrent is removed"),
                I18n.tr("Keep for 24 hours"),
                I18n.tr("Keep for 3 days"),
                I18n.tr("Keep for 7 days"),
                I18n.tr("Manual cleanup")};
        int checked = 0;
        String cur = sm.getRetentionPolicy();
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) checked = i;
        new AlertDialog.Builder(act)
                .setTitle(I18n.tr("Staging retention"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    sm.setRetentionPolicy(values[which]);
                    d.dismiss();
                    refresh(true);
                })
                .setNegativeButton(I18n.tr("Close"), null)
                .show();
    }

    // ------------------------------------------------------------------ importer card

    private View buildImporterCard() {
        LinearLayout c = card();
        c.addView(title("MOVIE IMPORTER"));
        importerBody = new LinearLayout(act);
        importerBody.setOrientation(LinearLayout.VERTICAL);
        c.addView(importerBody);
        renderImporter();
        return c;
    }

    private void renderImporter() {
        if (importerBody == null) return;
        importerBody.removeAllViews();
        final DownloadDetectionManager det = DownloadDetectionManager.getInstance(act);

        TextView desc = text(I18n.tr("Organises downloaded video files into Jellyfin movie folders with automatic metadata matching."),
                11.5f, AppTheme.textSecondary(), false);
        desc.setPadding(0, 0, 0, dp(6));
        importerBody.addView(desc);

        int pending = 0, done = 0, failed = 0;
        String last = null;
        try {
            MovieImportJournal j = MovieImportJournal.getInstance(act);
            for (MovieImportJournal.ImportRecord r : j.getAllRecords()) {
                if ("PENDING".equals(r.status) || "IN_PROGRESS".equals(r.status)) pending++;
                else if ("COMPLETED".equals(r.status)) {
                    done++;
                    if (last == null) last = r.movieTitle != null && !r.movieTitle.isEmpty() ? r.movieTitle : r.sourceFilename;
                } else if ("FAILED".equals(r.status)) failed++;
            }
        } catch (Throwable ignored) {}

        LinearLayout stats = new LinearLayout(act);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(stat(String.valueOf(pending), I18n.tr("Pending"), pending > 0 ? AppTheme.warning() : AppTheme.textMuted()),
                new LinearLayout.LayoutParams(0, -2, 1f));
        stats.addView(stat(String.valueOf(done), I18n.tr("Imported"), AppTheme.success()),
                new LinearLayout.LayoutParams(0, -2, 1f));
        stats.addView(stat(String.valueOf(failed), I18n.tr("Failed"), failed > 0 ? AppTheme.danger() : AppTheme.textMuted()),
                new LinearLayout.LayoutParams(0, -2, 1f));
        importerBody.addView(stats);
        if (last != null) {
            TextView l = text(I18n.tr("Last import") + ": " + last, 11f, AppTheme.textMuted(), false);
            l.setPadding(0, dp(6), 0, 0);
            importerBody.addView(l);
        }

        importerBody.addView(switchRow(I18n.tr("Detect new downloads"),
                I18n.tr("Watch the Downloads folder and offer to import new videos."),
                det.isDetectionEnabled(), (b, on) -> {
                    det.setDetectionEnabled(on);
                    if (on) det.startMonitoring();
                    else det.stopMonitoring();
                }));
        importerBody.addView(switchRow(I18n.tr("Keep screen on while importing"),
                I18n.tr("Prevents the phone from sleeping during long copies."),
                det.isKeepScreenOnEnabled(), (b, on) -> det.setKeepScreenOnEnabled(on)));

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(button(I18n.tr("SCAN NOW"), AppTheme.textSecondary(), v -> {
            try {
                det.scanForNewDownloads();
                Toast.makeText(act, I18n.tr("Scanning for new downloads..."), Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(act, String.valueOf(t.getMessage()), Toast.LENGTH_SHORT).show();
            }
            importerBody.postDelayed(this::renderImporter, 1500);
        }), actionLp(true));
        actions.addView(button(I18n.tr("OPEN IMPORTER"), AppTheme.accent(),
                v -> act.startActivity(new Intent(act, MovieImportActivity.class))), actionLp(false));
        importerBody.addView(actions);
    }

    // ------------------------------------------------------------------ small UI helpers

    private interface Toggle {
        void on(Switch s, boolean checked);
    }

    private View switchRow(String label, String hint, boolean checked, final Toggle t) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(2));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(label, 12.5f, AppTheme.text(), true));
        col.addView(text(hint, 10.5f, AppTheme.textMuted(), false));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(act);
        sw.setChecked(checked);
        UiStyle.styleSwitch(act, sw);
        sw.setOnCheckedChangeListener((b, on) -> t.on(sw, on));
        row.addView(sw);
        return row;
    }

    private View stat(String value, String label, int color) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(0, dp(6), 0, dp(6));
        c.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 10));
        TextView v = text(value, 18f, color, true);
        v.setGravity(Gravity.CENTER);
        c.addView(v);
        TextView l = text(label, 10f, AppTheme.textMuted(), false);
        l.setGravity(Gravity.CENTER);
        c.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        c.setLayoutParams(lp);
        return c;
    }

    private View bar(int percent, int color) {
        LinearLayout track = new LinearLayout(act);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(rounded(AppTheme.inner(), 0, 3));
        View fill = new View(act);
        fill.setBackground(rounded(color, 0, 3));
        int p = Math.max(1, Math.min(100, percent));
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, p));
        track.addView(new View(act), new LinearLayout.LayoutParams(0, -1, 100 - p));
        return track;
    }

    private LinearLayout.LayoutParams barLp(int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(h));
        lp.topMargin = dp(3);
        return lp;
    }

    private LinearLayout.LayoutParams actionLp(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        if (gap) lp.setMarginEnd(dp(8));
        return lp;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(10.5f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(color);
        b.setBackground(rounded(AppTheme.card(), color, 10));
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setOnClickListener(l);
        return b;
    }

    private void copy(String s) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", s));
            Toast.makeText(act, I18n.tr("Copied"), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView title(String s) {
        TextView t = text(I18n.tr(s), 10.5f, AppTheme.textMuted(), true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView muted(String s) {
        return text(s, 11.5f, AppTheme.textMuted(), false);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private static String fmt(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
