package com.fishbowl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fishbowl.app.services.RadarrApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Radarr" tab: a native client for Radarr's web UI, the movie twin of {@link SonarrTabView}.
 *
 * <ul>
 *   <li>MOVIES - browse / filter the library, open a movie (monitor, search, interactive search,
 *       refresh / rescan, delete file, delete movie).</li>
 *   <li>ADD - lookup a movie and add it with root folder, profile, availability, monitored, search.</li>
 *   <li>CALENDAR - cinema / digital / physical releases.</li>
 *   <li>QUEUE - live downloads with remove / blocklist.</li>
 *   <li>HISTORY - grabbed / imported / failed events.</li>
 *   <li>WANTED - monitored missing and cutoff unmet movies, search one or all.</li>
 *   <li>SYSTEM - status, health, disk space, root folders, profiles, indexers, clients, tasks.</li>
 * </ul>
 */
public final class RadarrTabView {

    private static final int S_MOVIES = 0;
    private static final int S_ADD = 1;
    private static final int S_CALENDAR = 2;
    private static final int S_QUEUE = 3;
    private static final int S_HISTORY = 4;
    private static final int S_WANTED = 5;
    private static final int S_SYSTEM = 6;

    private static final String[] SECTION_NAMES = {"MOVIES", "ADD", "CALENDAR", "QUEUE", "HISTORY", "WANTED", "SYSTEM"};
    private static final String[] FILTER_NAMES = {"ALL", "MONITORED", "MISSING", "DOWNLOADED"};
    private static final int PAGE_SIZE = 40;

    private interface Job<T> { T run(RadarrApi api) throws Exception; }
    private interface Done<T> { void run(T value); }

    private final Activity act;
    private final LinearLayout root;
    private final LinearLayout navRow;
    private final LinearLayout listContainer;
    private final ScrollView scroll;
    private final ExecutorService bg = Executors.newFixedThreadPool(2);
    private final ExecutorService imgPool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> imgCache = new LruCache<>(60);

    private int section = S_MOVIES;
    private int page = 1;
    private int detailMovieId = 0;
    private int requestCounter = 0;
    private int filterMode = 0;
    private boolean wantedCutoff = false;
    private String movieFilter = "";
    private JSONArray movieCache = null;

    private final Runnable queueTicker = new Runnable() {
        @Override public void run() {
            if (section == S_QUEUE && detailMovieId == 0 && root.isShown()) {
                loadQueue(true);
                root.postDelayed(this, 6000);
            }
        }
    };

    public RadarrTabView(Activity activity) {
        this.act = activity;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppTheme.bg());

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(4));
        TextView title = text("RADARR", 14f, AppTheme.text(), true);
        title.setLetterSpacing(0.14f);
        header.addView(title);
        TextView sub = text(I18n.tr("Manage your movie library from here"), 11f, AppTheme.textMuted(), false);
        sub.setPadding(0, dp(2), 0, dp(6));
        header.addView(sub);
        root.addView(header);

        HorizontalScrollView navScroll = new HorizontalScrollView(act);
        navScroll.setHorizontalScrollBarEnabled(false);
        navRow = new LinearLayout(act);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(dp(12), 0, dp(12), dp(6));
        navScroll.addView(navRow);
        root.addView(navScroll);

        scroll = new ScrollView(act);
        listContainer = new LinearLayout(act);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(listContainer, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
    }

    public View getView() { return root; }

    /** Returns true when the back press was consumed. */
    public boolean onBack() {
        if (detailMovieId > 0) {
            detailMovieId = 0;
            refresh();
            return true;
        }
        if ((section == S_WANTED || section == S_HISTORY) && page > 1) {
            page--;
            refresh();
            return true;
        }
        if (section != S_MOVIES) {
            section = S_MOVIES;
            page = 1;
            refresh();
            return true;
        }
        return false;
    }

    public void refresh() {
        renderNav();
        root.removeCallbacks(queueTicker);
        if (detailMovieId > 0) { loadDetail(); return; }
        switch (section) {
            case S_CALENDAR: loadCalendar(); break;
            case S_QUEUE: loadQueue(false); root.postDelayed(queueTicker, 6000); break;
            case S_HISTORY: loadHistory(); break;
            case S_WANTED: loadWanted(); break;
            case S_SYSTEM: loadSystem(); break;
            default: loadMovies(); break;
        }
    }

    private void renderNav() {
        navRow.removeAllViews();
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            final int idx = i;
            boolean selected = idx == section && detailMovieId == 0 && idx != S_ADD;
            TextView p = pill(SECTION_NAMES[i], selected);
            p.setOnClickListener(v -> {
                if (idx == S_ADD) {
                    section = S_MOVIES;
                    page = 1;
                    detailMovieId = 0;
                    refresh();
                    showAddDialog();
                    return;
                }
                section = idx;
                page = 1;
                detailMovieId = 0;
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.setMarginEnd(dp(6));
            navRow.addView(p, lp);
        }
    }

    // ================================================================== async helper

    private <T> void async(final Job<T> job, final Done<T> ok, final Done<Throwable> err) {
        bg.execute(() -> {
            try {
                final RadarrApi api = RadarrApi.get(act);
                final T r = job.run(api);
                act.runOnUiThread(() -> { if (ok != null) ok.run(r); });
            } catch (final Throwable t) {
                act.runOnUiThread(() -> {
                    if (err != null) err.run(t);
                    else toast(msg(t));
                });
            }
        });
    }

    /** Runs a fire-and-forget action and toasts the outcome. */
    private void action(final Job<Void> job, final String okMsg, final Runnable after) {
        async(job, v -> {
            toast(okMsg);
            if (after != null) after.run();
        }, t -> toast(msg(t)));
    }

    private static String msg(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null ? "Unknown error (is Radarr running?)" : m;
    }

    private void toast(String s) { Toast.makeText(act, I18n.tr(s), Toast.LENGTH_SHORT).show(); }

    // ================================================================== MOVIES list

    private void loadMovies() {
        final int req = ++requestCounter;
        listContainer.removeAllViews();
        if (movieCache != null) {
            renderMovieList();
        } else {
            showMessage("Loading...");
        }
        async(api -> api.movies(), arr -> {
            if (req != requestCounter || detailMovieId > 0 || section != S_MOVIES) return;
            movieCache = arr;
            renderMovieList();
        }, t -> { if (req == requestCounter && movieCache == null) showError(t); });
    }

    private void renderMovieList() {
        listContainer.removeAllViews();
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(4));
        final EditText filter = new EditText(act);
        filter.setHint(I18n.tr("Filter movies"));
        filter.setHintTextColor(AppTheme.textMuted());
        filter.setTextColor(AppTheme.text());
        filter.setTextSize(13f);
        filter.setSingleLine(true);
        filter.setText(movieFilter);
        filter.setSelection(filter.getText().length());
        bar.addView(filter, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final Button mode = actionButton(FILTER_NAMES[filterMode], AppTheme.textSecondary(), null);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, dp(36));
        mlp.setMarginStart(dp(8));
        bar.addView(mode, mlp);
        Button add = actionButton("+ ADD", AppTheme.accent(), v -> showAddDialog());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, dp(36));
        alp.setMarginStart(dp(6));
        bar.addView(add, alp);
        listContainer.addView(bar);

        final LinearLayout rows = new LinearLayout(act);
        rows.setOrientation(LinearLayout.VERTICAL);
        final TextView count = text("", 11f, AppTheme.textSecondary(), false);
        count.setPadding(0, dp(2), 0, dp(2));
        listContainer.addView(count);
        listContainer.addView(rows);
        fillMovieRows(rows, count);
        mode.setOnClickListener(v -> {
            filterMode = (filterMode + 1) % FILTER_NAMES.length;
            mode.setText(I18n.tr(FILTER_NAMES[filterMode]));
            fillMovieRows(rows, count);
        });
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                movieFilter = e.toString();
                fillMovieRows(rows, count);
            }
        });
    }

    private void fillMovieRows(LinearLayout rows, TextView count) {
        rows.removeAllViews();
        if (movieCache == null) return;
        List<JSONObject> list = new ArrayList<>();
        String f = movieFilter.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < movieCache.length(); i++) {
            JSONObject m = movieCache.optJSONObject(i);
            if (m == null) continue;
            if (!f.isEmpty() && !m.optString("title", "").toLowerCase(Locale.ROOT).contains(f)) continue;
            if (filterMode == 1 && !m.optBoolean("monitored")) continue;
            if (filterMode == 2 && (m.optBoolean("hasFile") || !m.optBoolean("monitored"))) continue;
            if (filterMode == 3 && !m.optBoolean("hasFile")) continue;
            list.add(m);
        }
        java.util.Collections.sort(list, (a, b) -> a.optString("sortTitle", a.optString("title"))
                .compareToIgnoreCase(b.optString("sortTitle", b.optString("title"))));
        count.setText(list.size() + " / " + movieCache.length() + " " + I18n.tr("movies"));
        if (list.isEmpty()) {
            TextView t = text(I18n.tr(movieCache.length() == 0 ? "No movies yet. Tap + ADD to add one." : "No matches."),
                    12f, AppTheme.textSecondary(), false);
            t.setPadding(0, dp(12), 0, 0);
            rows.addView(t);
            return;
        }
        for (JSONObject m : list) rows.addView(movieRow(m));
    }

    private View movieRow(final JSONObject m) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(10), dp(8));
        row.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        row.addView(poster(m, 46, 68), new LinearLayout.LayoutParams(dp(46), dp(68)));

        LinearLayout tx = new LinearLayout(act);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(10), 0, dp(6), 0);
        TextView t = text(m.optString("title", "?") + (m.optInt("year") > 0 ? "  (" + m.optInt("year") + ")" : ""),
                13f, AppTheme.text(), true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(t);
        String line = capitalize(m.optString("status", ""))
                + (m.optInt("runtime") > 0 ? " - " + m.optInt("runtime") + " min" : "")
                + (m.optString("studio").isEmpty() ? "" : " - " + m.optString("studio"));
        TextView l1 = text(line, 10.5f, AppTheme.textMuted(), false);
        l1.setPadding(0, dp(2), 0, 0);
        l1.setMaxLines(1);
        l1.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(l1);
        boolean has = m.optBoolean("hasFile");
        JSONObject file = m.optJSONObject("movieFile");
        String fileLine;
        int fileColor;
        if (has) {
            fileLine = "✔ " + (file == null ? "" : qualityName(file) + "  ") + fmtSize(file != null ? file.optLong("size") : m.optLong("sizeOnDisk"));
            fileColor = AppTheme.success();
        } else {
            fileLine = "✖ " + I18n.tr("No file");
            fileColor = m.optBoolean("monitored") ? AppTheme.warning() : AppTheme.textMuted();
        }
        TextView l2 = text(fileLine, 10.5f, fileColor, false);
        l2.setPadding(0, dp(2), 0, 0);
        tx.addView(l2);
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView mon = text(m.optBoolean("monitored") ? "●" : "○", 16f,
                m.optBoolean("monitored") ? AppTheme.accent() : AppTheme.textMuted(), false);
        row.addView(mon);

        row.setClickable(true);
        row.setOnClickListener(v -> openMovie(m.optInt("id")));
        return row;
    }

    private void openMovie(int id) {
        if (id <= 0) return;
        detailMovieId = id;
        refresh();
        scroll.scrollTo(0, 0);
    }

    // ================================================================== MOVIE detail

    private void loadDetail() {
        final int req = ++requestCounter;
        final int id = detailMovieId;
        listContainer.removeAllViews();
        showMessage("Loading...");
        async(api -> {
            JSONObject pack = new JSONObject();
            pack.put("movie", api.movie(id));
            try { pack.put("profiles", api.qualityProfiles()); } catch (Throwable ignored) {}
            return pack;
        }, pack -> {
            if (req != requestCounter || detailMovieId != id) return;
            renderDetail(pack.optJSONObject("movie"), pack.optJSONArray("profiles"));
        }, t -> { if (req == requestCounter) showError(t); });
    }

    private void renderDetail(final JSONObject movie, JSONArray profiles) {
        listContainer.removeAllViews();
        if (movie == null) { showMessage("Movie not found."); return; }
        final int id = movie.optInt("id");

        LinearLayout back = new LinearLayout(act);
        back.setOrientation(LinearLayout.HORIZONTAL);
        back.setPadding(0, dp(8), 0, dp(4));
        back.addView(actionButton("< BACK", AppTheme.textSecondary(), v -> onBack()), new LinearLayout.LayoutParams(-2, dp(34)));
        listContainer.addView(back);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(dp(10), dp(10), dp(10), dp(10));
        head.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 14));
        head.addView(poster(movie, 90, 132), new LinearLayout.LayoutParams(dp(90), dp(132)));
        LinearLayout info = new LinearLayout(act);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        info.addView(text(movie.optString("title") + (movie.optInt("year") > 0 ? " (" + movie.optInt("year") + ")" : ""),
                15f, AppTheme.text(), true));
        String meta = capitalize(movie.optString("status", ""))
                + (movie.optInt("runtime") > 0 ? " - " + movie.optInt("runtime") + " min" : "")
                + (movie.optString("studio").isEmpty() ? "" : " - " + movie.optString("studio"));
        TextView m = text(meta, 11f, AppTheme.textSecondary(), false);
        m.setPadding(0, dp(3), 0, 0);
        info.addView(m);

        String profName = "";
        int pid = movie.optInt("qualityProfileId");
        for (int i = 0; profiles != null && i < profiles.length(); i++) {
            JSONObject p = profiles.optJSONObject(i);
            if (p != null && p.optInt("id") == pid) { profName = p.optString("name"); break; }
        }
        TextView q = text(I18n.tr("Quality profile") + ": " + (profName.isEmpty() ? String.valueOf(pid) : profName)
                + "  -  " + I18n.tr("Availability") + ": " + movie.optString("minimumAvailability", "-"),
                11f, AppTheme.textSecondary(), false);
        q.setPadding(0, dp(2), 0, 0);
        info.addView(q);

        StringBuilder dates = new StringBuilder();
        appendDate(dates, "Cinemas", movie.optString("inCinemas", ""));
        appendDate(dates, "Digital", movie.optString("digitalRelease", ""));
        appendDate(dates, "Physical", movie.optString("physicalRelease", ""));
        if (dates.length() > 0) {
            TextView dt = text(dates.toString(), 10.5f, AppTheme.textMuted(), false);
            dt.setPadding(0, dp(2), 0, 0);
            info.addView(dt);
        }
        TextView path = text(movie.optString("path", ""), 10f, AppTheme.textMuted(), false);
        path.setPadding(0, dp(2), 0, 0);
        info.addView(path);
        String ov = movie.optString("overview", "");
        if (!ov.isEmpty()) {
            final TextView o = text(ov, 11f, AppTheme.textSecondary(), false);
            o.setMaxLines(4);
            o.setEllipsize(TextUtils.TruncateAt.END);
            o.setPadding(0, dp(6), 0, 0);
            o.setOnClickListener(v -> {
                boolean collapsed = o.getMaxLines() == 4;
                o.setMaxLines(collapsed ? 100 : 4);
            });
            info.addView(o);
        }
        head.addView(info, new LinearLayout.LayoutParams(0, -2, 1.0f));
        listContainer.addView(head);

        // file info
        final JSONObject file = movie.optJSONObject("movieFile");
        LinearLayout fileCard = new LinearLayout(act);
        fileCard.setOrientation(LinearLayout.VERTICAL);
        fileCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        fileCard.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams fclp = new LinearLayout.LayoutParams(-1, -2);
        fclp.topMargin = dp(8);
        fileCard.setLayoutParams(fclp);
        if (movie.optBoolean("hasFile") && file != null) {
            fileCard.addView(text("✔ " + qualityName(file) + "  -  " + fmtSize(file.optLong("size")), 12.5f, AppTheme.success(), true));
            String rel = file.optString("relativePath", "");
            if (!rel.isEmpty()) {
                TextView rt = text(rel, 10.5f, AppTheme.textSecondary(), false);
                rt.setPadding(0, dp(2), 0, 0);
                fileCard.addView(rt);
            }
            JSONObject mi = file.optJSONObject("mediaInfo");
            if (mi != null) {
                String codec = (mi.optString("videoCodec", "") + " " + mi.optString("resolution", "")).trim();
                String audio = (mi.optString("audioCodec", "") + (mi.optString("audioChannels", "").isEmpty() ? "" : " " + mi.optString("audioChannels"))).trim();
                String mline = codec + (codec.isEmpty() || audio.isEmpty() ? "" : "  -  ") + audio;
                if (!mline.isEmpty()) fileCard.addView(text(mline, 10.5f, AppTheme.textMuted(), false));
            }
            final int fileId = file.optInt("id");
            Button del = actionButton("DELETE FILE", AppTheme.danger(), v ->
                    confirm(I18n.tr("Delete the downloaded file of this movie?"), I18n.tr("DELETE"), AppTheme.danger(), () ->
                            action(api -> { api.deleteMovieFile(fileId); return null; }, "File deleted.", () -> {
                                movieCache = null;
                                refresh();
                            })));
            fileCard.addView(del, lpTop(dp(32), dp(8)));
        } else {
            fileCard.addView(text("✖ " + I18n.tr("No file on disk"), 12.5f, AppTheme.warning(), true));
        }
        listContainer.addView(fileCard);

        // monitored switch
        LinearLayout monRow = new LinearLayout(act);
        monRow.setOrientation(LinearLayout.HORIZONTAL);
        monRow.setGravity(Gravity.CENTER_VERTICAL);
        monRow.setPadding(dp(4), dp(10), dp(4), dp(2));
        monRow.addView(text(I18n.tr("Monitored"), 12.5f, AppTheme.text(), false), new LinearLayout.LayoutParams(0, -2, 1.0f));
        final Switch monSw = new Switch(act);
        monSw.setChecked(movie.optBoolean("monitored"));
        UiStyle.styleSwitch(act, monSw);
        monSw.setOnCheckedChangeListener((b, checked) -> {
            try {
                movie.put("monitored", checked);
                final JSONObject copy = new JSONObject(movie.toString());
                action(api -> { api.putMovie(copy); return null; }, checked ? "Monitoring enabled." : "Monitoring disabled.", () -> movieCache = null);
            } catch (Throwable ex) { toast(msg(ex)); }
        });
        monRow.addView(monSw);
        listContainer.addView(monRow);

        // actions
        LinearLayout acts = new LinearLayout(act);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.addView(actionButton("SEARCH", AppTheme.success(), v ->
                action(api -> { api.searchMovie(id); return null; }, "Search queued.", null)), weight());
        acts.addView(actionButton("INTERACTIVE", AppTheme.accent(), v -> showReleases(id,
                movie.optString("title") + (movie.optInt("year") > 0 ? " (" + movie.optInt("year") + ")" : ""))), weight());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(6);
        listContainer.addView(acts, alp);

        LinearLayout acts2 = new LinearLayout(act);
        acts2.setOrientation(LinearLayout.HORIZONTAL);
        acts2.addView(actionButton("REFRESH", AppTheme.accent(), v ->
                action(api -> { api.refreshMovie(id); return null; }, "Refresh queued.", () -> movieCache = null)), weight());
        acts2.addView(actionButton("RESCAN", AppTheme.accent(), v ->
                action(api -> { api.rescanMovie(id); return null; }, "Rescan queued.", () -> movieCache = null)), weight());
        acts2.addView(actionButton("DELETE", AppTheme.danger(), v -> confirmDeleteMovie(movie)), weight());
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(-1, -2);
        alp2.topMargin = dp(6);
        listContainer.addView(acts2, alp2);
    }

    private static void appendDate(StringBuilder sb, String label, String iso) {
        if (iso == null || iso.length() < 10) return;
        if (sb.length() > 0) sb.append("   ");
        sb.append(I18n.tr(label)).append(": ").append(iso.substring(0, 10));
    }

    private static String qualityName(JSONObject file) {
        if (file == null) return "";
        JSONObject q = file.optJSONObject("quality");
        JSONObject qq = q == null ? null : q.optJSONObject("quality");
        return qq == null ? "" : qq.optString("name", "");
    }

    private void confirmDeleteMovie(final JSONObject movie) {
        final LinearLayout body = dialogBody(I18n.tr("Delete movie"));
        body.addView(text(movie.optString("title"), 13f, AppTheme.text(), true));
        final Switch files = new Switch(act);
        files.setText(I18n.tr("Also delete files from disk"));
        files.setTextColor(AppTheme.text());
        UiStyle.styleSwitch(act, files);
        files.setPadding(0, dp(12), 0, dp(4));
        body.addView(files);
        final Dialog d = dialog(body);
        LinearLayout row = buttonRow();
        row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        row.addView(actionButton("DELETE", AppTheme.danger(), v -> {
            d.dismiss();
            final int id = movie.optInt("id");
            final boolean df = files.isChecked();
            action(api -> { api.deleteMovie(id, df); return null; }, "Movie deleted.", () -> {
                movieCache = null;
                detailMovieId = 0;
                refresh();
            });
        }), weight());
        body.addView(row);
        d.show();
    }

    // ================================================================== interactive search

    private void showReleases(final int movieId, String title) {
        final LinearLayout body = dialogBody(I18n.tr("Interactive search"));
        body.addView(text(title, 12f, AppTheme.textSecondary(), false));
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        final TextView loading = text(I18n.tr("Searching indexers... this can take up to a minute."), 12f, AppTheme.textSecondary(), false);
        loading.setPadding(0, dp(12), 0, 0);
        list.addView(loading);
        ScrollView sv = new ScrollView(act);
        sv.addView(list);
        body.addView(sv, new LinearLayout.LayoutParams(-1, dp(380)));
        final Dialog d = dialog(body);
        d.show();
        async(api -> api.releasesForMovie(movieId), arr -> {
            list.removeAllViews();
            if (arr.length() == 0) {
                list.addView(text(I18n.tr("No releases found."), 12f, AppTheme.textSecondary(), false));
                return;
            }
            List<JSONObject> rel = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null) rel.add(r);
            }
            java.util.Collections.sort(rel, (a, b) -> {
                boolean ra = a.optJSONArray("rejections") != null && a.optJSONArray("rejections").length() > 0;
                boolean rb = b.optJSONArray("rejections") != null && b.optJSONArray("rejections").length() > 0;
                if (ra != rb) return ra ? 1 : -1;
                return b.optInt("seeders") - a.optInt("seeders");
            });
            int n = 0;
            for (JSONObject r : rel) {
                if (++n > 60) break;
                list.addView(releaseRow(r, d));
            }
        }, t -> { list.removeAllViews(); list.addView(text(msg(t), 12f, AppTheme.danger(), false)); });
    }

    private View releaseRow(final JSONObject r, final Dialog d) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        box.setLayoutParams(lp);
        TextView t = text(r.optString("title"), 11.5f, AppTheme.text(), true);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t);
        JSONObject q = r.optJSONObject("quality");
        JSONObject qq = q == null ? null : q.optJSONObject("quality");
        String proto = r.optString("protocol", "");
        String line = (qq == null ? "" : qq.optString("name") + "  -  ") + fmtSize(r.optLong("size"))
                + "  -  " + r.optString("indexer", "")
                + ("torrent".equalsIgnoreCase(proto) ? "  -  S:" + r.optInt("seeders") + " L:" + r.optInt("leechers") : "")
                + "  -  " + fmtAge(r.optDouble("ageMinutes", 0));
        TextView l = text(line, 10f, AppTheme.textSecondary(), false);
        l.setPadding(0, dp(2), 0, dp(2));
        box.addView(l);
        JSONArray rej = r.optJSONArray("rejections");
        final boolean rejected = rej != null && rej.length() > 0;
        if (rejected) {
            box.addView(text("⚠ " + rej.optString(0), 10f, AppTheme.warning(), false));
        }
        box.addView(actionButton("GRAB", rejected ? AppTheme.warning() : AppTheme.success(), v -> {
            Runnable go = () -> action(api -> { api.grabRelease(r); return null; }, "Sent to the download client.", d::dismiss);
            if (rejected) confirm(I18n.tr("This release was rejected by Radarr. Download anyway?"), I18n.tr("GRAB"), AppTheme.warning(), go);
            else go.run();
        }), new LinearLayout.LayoutParams(-1, dp(34)));
        return box;
    }

    // ================================================================== ADD movie

    private void showAddDialog() {
        final LinearLayout body = dialogBody(I18n.tr("Add movie"));
        final EditText term = new EditText(act);
        term.setHint(I18n.tr("Search TMDb by title"));
        term.setHintTextColor(AppTheme.textMuted());
        term.setTextColor(AppTheme.text());
        term.setTextSize(13f);
        term.setSingleLine(true);
        term.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        body.addView(term);
        final LinearLayout results = new LinearLayout(act);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(act);
        sv.addView(results);
        final Dialog d = dialog(body);
        final Runnable[] go = new Runnable[1];
        go[0] = () -> {
            String q = term.getText().toString().trim();
            if (q.isEmpty()) return;
            results.removeAllViews();
            results.addView(text(I18n.tr("Searching..."), 12f, AppTheme.textSecondary(), false));
            async(api -> api.lookup(q), arr -> {
                results.removeAllViews();
                if (arr.length() == 0) {
                    results.addView(text(I18n.tr("No results."), 12f, AppTheme.textSecondary(), false));
                    return;
                }
                for (int i = 0; i < Math.min(arr.length(), 25); i++) {
                    final JSONObject r = arr.optJSONObject(i);
                    if (r != null) results.addView(lookupRow(r, d));
                }
            }, t -> { results.removeAllViews(); results.addView(text(msg(t), 12f, AppTheme.danger(), false)); });
        };
        term.setOnEditorActionListener((v, actionId, ev) -> { go[0].run(); return true; });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(34));
        blp.topMargin = dp(8);
        body.addView(actionButton("SEARCH", AppTheme.accent(), v -> go[0].run()), blp);
        body.addView(sv, new LinearLayout.LayoutParams(-1, dp(360)));
        d.show();
    }

    private View lookupRow(final JSONObject r, final Dialog parent) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        row.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        row.addView(poster(r, 40, 58), new LinearLayout.LayoutParams(dp(40), dp(58)));
        LinearLayout tx = new LinearLayout(act);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(8), 0, 0, 0);
        TextView t = text(r.optString("title") + (r.optInt("year") > 0 ? " (" + r.optInt("year") + ")" : ""), 12.5f, AppTheme.text(), true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(t);
        final boolean exists = r.optInt("id") > 0;
        String sline = (exists ? "✔ " + I18n.tr("Already added") + " - " : "")
                + capitalize(r.optString("status", ""))
                + (r.optInt("runtime") > 0 ? " - " + r.optInt("runtime") + " min" : "")
                + (r.optString("studio").isEmpty() ? "" : " - " + r.optString("studio"));
        TextView s = text(sline, 10.5f, exists ? AppTheme.success() : AppTheme.textMuted(), false);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(s);
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.setClickable(true);
        row.setOnClickListener(v -> {
            if (exists) { parent.dismiss(); openMovie(r.optInt("id")); }
            else showAddOptions(r, parent);
        });
        return row;
    }

    private void showAddOptions(final JSONObject lookup, final Dialog parent) {
        final LinearLayout body = dialogBody(I18n.tr("Add") + " " + lookup.optString("title"));
        final TextView loading = text(I18n.tr("Loading options..."), 12f, AppTheme.textSecondary(), false);
        body.addView(loading);
        final Dialog d = dialog(body);
        d.show();
        async(api -> {
            JSONObject o = new JSONObject();
            o.put("roots", api.rootFolders());
            o.put("profiles", api.qualityProfiles());
            return o;
        }, o -> {
            body.removeView(loading);
            final JSONArray roots = o.optJSONArray("roots");
            final JSONArray profiles = o.optJSONArray("profiles");
            if (roots == null || roots.length() == 0) {
                body.addView(text(I18n.tr("No root folder is configured. Add one in the SYSTEM section first."), 12f, AppTheme.danger(), false));
                return;
            }
            if (profiles == null || profiles.length() == 0) {
                body.addView(text(I18n.tr("No quality profile found in Radarr."), 12f, AppTheme.danger(), false));
                return;
            }
            final String[] rootNames = new String[roots.length()];
            for (int i = 0; i < roots.length(); i++) rootNames[i] = roots.optJSONObject(i).optString("path");
            final String[] profNames = new String[profiles.length()];
            final int[] profIds = new int[profiles.length()];
            for (int i = 0; i < profiles.length(); i++) {
                profNames[i] = profiles.optJSONObject(i).optString("name");
                profIds[i] = profiles.optJSONObject(i).optInt("id");
            }
            final String[] availLabels = {"Announced", "In cinemas", "Released"};
            final String[] availVals = {"announced", "inCinemas", "released"};
            final int[] selRoot = {0}, selProf = {0}, selAvail = {2};
            body.addView(choiceField("Root folder", rootNames, selRoot));
            body.addView(choiceField("Quality profile", profNames, selProf));
            body.addView(choiceField("Minimum availability", availLabels, selAvail));
            final Switch monitored = new Switch(act);
            monitored.setText(I18n.tr("Monitored"));
            monitored.setTextColor(AppTheme.text());
            monitored.setChecked(true);
            UiStyle.styleSwitch(act, monitored);
            monitored.setPadding(0, dp(10), 0, dp(2));
            body.addView(monitored);
            final Switch search = new Switch(act);
            search.setText(I18n.tr("Start search for movie"));
            search.setTextColor(AppTheme.text());
            search.setChecked(true);
            UiStyle.styleSwitch(act, search);
            search.setPadding(0, dp(6), 0, dp(6));
            body.addView(search);
            LinearLayout row = buttonRow();
            row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
            row.addView(actionButton("ADD", AppTheme.success(), v -> {
                d.dismiss();
                final String root0 = rootNames[selRoot[0]];
                final int prof0 = profIds[selProf[0]];
                final String avail0 = availVals[selAvail[0]];
                final boolean mon0 = monitored.isChecked();
                final boolean search0 = search.isChecked();
                action(api -> {
                    api.addMovie(lookup, root0, prof0, mon0, avail0, search0);
                    return null;
                }, "Movie added.", () -> {
                    movieCache = null;
                    parent.dismiss();
                    refresh();
                });
            }), weight());
            body.addView(row);
        }, t -> { loading.setText(msg(t)); loading.setTextColor(AppTheme.danger()); });
    }

    private View choiceField(final String label, final String[] options, final int[] selected) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        box.addView(text(I18n.tr(label), 10.5f, AppTheme.textMuted(), false));
        final TextView value = text(I18n.tr(options[selected[0]]), 13f, AppTheme.text(), false);
        value.setPadding(dp(10), dp(9), dp(10), dp(9));
        value.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 8));
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        value.setOnClickListener(v -> {
            final String[] shown = new String[options.length];
            for (int i = 0; i < options.length; i++) shown[i] = I18n.tr(options[i]);
            new AlertDialog.Builder(act).setItems(shown, (dd, which) -> {
                selected[0] = which;
                value.setText(shown[which]);
            }).show();
        });
        box.addView(value);
        return box;
    }

    // ================================================================== CALENDAR

    private void loadCalendar() {
        final int req = ++requestCounter;
        showMessage("Loading...");
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -3);
        final String start = f.format(c.getTime());
        c.add(Calendar.DAY_OF_YEAR, 45);
        final String end = f.format(c.getTime());
        async(api -> api.calendar(start, end), arr -> {
            if (req != requestCounter || section != S_CALENDAR || detailMovieId > 0) return;
            listContainer.removeAllViews();
            // One entry per (release date inside the window, release kind).
            TreeMap<String, List<JSONObject>> byDate = new TreeMap<>();
            String[] keys = {"inCinemas", "digitalRelease", "physicalRelease"};
            String[] kinds = {"In cinemas", "Digital release", "Physical release"};
            try {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    for (int k = 0; k < keys.length; k++) {
                        String iso = m.optString(keys[k], "");
                        if (iso.length() < 10) continue;
                        String day = iso.substring(0, 10);
                        if (day.compareTo(start) < 0 || day.compareTo(end) > 0) continue;
                        JSONObject entry = new JSONObject();
                        entry.put("movie", m);
                        entry.put("kind", kinds[k]);
                        List<JSONObject> l = byDate.get(day);
                        if (l == null) { l = new ArrayList<>(); byDate.put(day, l); }
                        l.add(entry);
                    }
                }
            } catch (Throwable ex) {
                showError(ex);
                return;
            }
            if (byDate.isEmpty()) { showMessage("Nothing scheduled in this period."); return; }
            String today = today();
            for (java.util.Map.Entry<String, List<JSONObject>> en : byDate.entrySet()) {
                boolean isToday = en.getKey().equals(today);
                TextView h = text(en.getKey() + (isToday ? "   - " + I18n.tr("TODAY") : ""), 11f,
                        isToday ? AppTheme.accent() : AppTheme.textMuted(), true);
                h.setPadding(dp(2), dp(14), 0, dp(2));
                listContainer.addView(h);
                for (JSONObject entry : en.getValue()) listContainer.addView(calendarRow(entry));
            }
        }, t -> { if (req == requestCounter) showError(t); });
    }

    private View calendarRow(JSONObject entry) {
        final JSONObject m = entry.optJSONObject("movie");
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(5);
        row.setLayoutParams(lp);
        row.addView(text(m == null ? "?" : m.optString("title") + (m.optInt("year") > 0 ? " (" + m.optInt("year") + ")" : ""),
                12.5f, AppTheme.text(), true));
        boolean has = m != null && m.optBoolean("hasFile");
        TextView t = text(I18n.tr(entry.optString("kind")) + (has ? "   ✔" : ""), 11f,
                has ? AppTheme.success() : AppTheme.textSecondary(), false);
        row.addView(t);
        if (m != null && m.optInt("id") > 0) {
            row.setClickable(true);
            final int mid = m.optInt("id");
            row.setOnClickListener(v -> openMovie(mid));
        }
        return row;
    }

    // ================================================================== QUEUE

    private void loadQueue(final boolean silent) {
        final int req = ++requestCounter;
        if (!silent) showMessage("Loading...");
        async(api -> api.queue(1, 100), o -> {
            if (req != requestCounter || section != S_QUEUE || detailMovieId > 0) return;
            int y = scroll.getScrollY();
            listContainer.removeAllViews();
            JSONArray recs = o.optJSONArray("records");
            if (recs == null || recs.length() == 0) { showMessage("The queue is empty."); return; }
            TextView c = text(recs.length() + " " + I18n.tr("items downloading"), 11f, AppTheme.textSecondary(), false);
            c.setPadding(0, dp(8), 0, dp(2));
            listContainer.addView(c);
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.optJSONObject(i);
                if (r != null) listContainer.addView(queueRow(r));
            }
            scroll.post(() -> scroll.scrollTo(0, y));
        }, t -> { if (req == requestCounter && !silent) showError(t); });
    }

    private View queueRow(final JSONObject r) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        box.setLayoutParams(lp);
        JSONObject m = r.optJSONObject("movie");
        box.addView(text(m == null ? r.optString("title") : m.optString("title")
                + (m.optInt("year") > 0 ? " (" + m.optInt("year") + ")" : ""), 12.5f, AppTheme.text(), true));
        TextView rel = text(r.optString("title"), 10f, AppTheme.textMuted(), false);
        rel.setMaxLines(2);
        rel.setEllipsize(TextUtils.TruncateAt.END);
        rel.setPadding(0, dp(2), 0, dp(4));
        box.addView(rel);
        double size = r.optDouble("size", 0), left = r.optDouble("sizeleft", 0);
        int pct = size > 0 ? (int) Math.max(0, Math.min(100, (size - left) * 100.0 / size)) : 0;
        ProgressBar pb = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(pct);
        box.addView(pb, new LinearLayout.LayoutParams(-1, dp(4)));
        String status = r.optString("status", "") + ("warning".equals(r.optString("trackedDownloadStatus")) ? " ⚠" : "")
                + "  -  " + pct + "%  -  " + fmtSize((long) size)
                + (r.optString("timeleft").isEmpty() ? "" : "  -  " + r.optString("timeleft"))
                + (r.optString("downloadClient").isEmpty() ? "" : "  -  " + r.optString("downloadClient"));
        TextView st = text(status, 10.5f, AppTheme.textSecondary(), false);
        st.setPadding(0, dp(4), 0, dp(4));
        box.addView(st);
        JSONArray msgs = r.optJSONArray("statusMessages");
        if (msgs != null && msgs.length() > 0) {
            JSONObject m0 = msgs.optJSONObject(0);
            JSONArray mm = m0 == null ? null : m0.optJSONArray("messages");
            String warn = mm != null && mm.length() > 0 ? mm.optString(0) : (m0 == null ? "" : m0.optString("title"));
            if (!warn.isEmpty()) box.addView(text("⚠ " + warn, 10f, AppTheme.warning(), false));
        }
        final int qid = r.optInt("id");
        box.addView(actionButton("REMOVE", AppTheme.danger(), v -> showRemoveQueue(qid)), new LinearLayout.LayoutParams(-1, dp(32)));
        return box;
    }

    private void showRemoveQueue(final int id) {
        final LinearLayout body = dialogBody(I18n.tr("Remove from queue"));
        final Switch client = new Switch(act);
        client.setText(I18n.tr("Also remove from the download client"));
        client.setTextColor(AppTheme.text());
        client.setChecked(true);
        UiStyle.styleSwitch(act, client);
        body.addView(client);
        final Switch block = new Switch(act);
        block.setText(I18n.tr("Add the release to the blocklist"));
        block.setTextColor(AppTheme.text());
        UiStyle.styleSwitch(act, block);
        block.setPadding(0, dp(10), 0, dp(10));
        body.addView(block);
        final Dialog d = dialog(body);
        LinearLayout row = buttonRow();
        row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        row.addView(actionButton("REMOVE", AppTheme.danger(), v -> {
            d.dismiss();
            final boolean rc = client.isChecked(), bl = block.isChecked();
            action(api -> { api.removeQueueItem(id, rc, bl); return null; }, "Removed.", this::refresh);
        }), weight());
        body.addView(row);
        d.show();
    }

    // ================================================================== HISTORY

    private void loadHistory() {
        final int req = ++requestCounter;
        final int pg = page;
        showMessage("Loading...");
        async(api -> api.history(pg, PAGE_SIZE), o -> {
            if (req != requestCounter) return;
            listContainer.removeAllViews();
            JSONArray recs = o.optJSONArray("records");
            if (recs == null || recs.length() == 0) { showMessage("No history yet."); return; }
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.optJSONObject(i);
                if (r != null) listContainer.addView(historyRow(r));
            }
            addPager(pg, o.optInt("totalRecords"));
        }, t -> { if (req == requestCounter) showError(t); });
    }

    private View historyRow(final JSONObject r) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        box.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(5);
        box.setLayoutParams(lp);
        JSONObject m = r.optJSONObject("movie");
        box.addView(text(m == null ? "?" : m.optString("title") + (m.optInt("year") > 0 ? " (" + m.optInt("year") + ")" : ""),
                12.5f, AppTheme.text(), true));
        String ev = r.optString("eventType", "");
        int color = AppTheme.textSecondary();
        String label = ev;
        if (ev.equals("grabbed")) { label = "Grabbed"; color = AppTheme.accent(); }
        else if (ev.equals("downloadFolderImported")) { label = "Imported"; color = AppTheme.success(); }
        else if (ev.equals("downloadFailed")) { label = "Download failed"; color = AppTheme.danger(); }
        else if (ev.equals("movieFileDeleted")) { label = "File deleted"; color = AppTheme.warning(); }
        else if (ev.equals("movieFileRenamed")) label = "Renamed";
        else if (ev.equals("downloadIgnored")) label = "Ignored";
        box.addView(text(I18n.tr(label), 11f, color, true));
        TextView src = text(r.optString("sourceTitle"), 10f, AppTheme.textMuted(), false);
        src.setMaxLines(2);
        src.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(src);
        String date = r.optString("date", "");
        box.addView(text(date.length() >= 16 ? date.substring(0, 10) + " " + date.substring(11, 16) : date, 10f, AppTheme.textMuted(), false));
        if (m != null && m.optInt("id") > 0) {
            final int mid = m.optInt("id");
            box.setClickable(true);
            box.setOnClickListener(v -> openMovie(mid));
        }
        return box;
    }

    // ================================================================== WANTED

    private void loadWanted() {
        final int req = ++requestCounter;
        final int pg = page;
        final boolean cutoff = wantedCutoff;
        showMessage("Loading...");
        async(api -> cutoff ? api.wantedCutoff(pg, PAGE_SIZE) : api.wantedMissing(pg, PAGE_SIZE), o -> {
            if (req != requestCounter || section != S_WANTED || detailMovieId > 0) return;
            listContainer.removeAllViews();
            int total = o.optInt("totalRecords");

            LinearLayout toggle = new LinearLayout(act);
            toggle.setOrientation(LinearLayout.HORIZONTAL);
            toggle.setPadding(0, dp(8), 0, dp(2));
            TextView pm = pill("MISSING", !cutoff);
            pm.setOnClickListener(v -> { wantedCutoff = false; page = 1; refresh(); });
            LinearLayout.LayoutParams plp1 = new LinearLayout.LayoutParams(-2, dp(34));
            plp1.setMarginEnd(dp(6));
            toggle.addView(pm, plp1);
            TextView pc = pill("CUTOFF UNMET", cutoff);
            pc.setOnClickListener(v -> { wantedCutoff = true; page = 1; refresh(); });
            toggle.addView(pc, new LinearLayout.LayoutParams(-2, dp(34)));
            listContainer.addView(toggle);

            LinearLayout bar = new LinearLayout(act);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(0, dp(6), 0, dp(4));
            bar.addView(text(total + " " + I18n.tr(cutoff ? "cutoff unmet" : "missing"), 11f, AppTheme.textSecondary(), false),
                    new LinearLayout.LayoutParams(0, -2, 1.0f));
            bar.addView(actionButton("SEARCH ALL", AppTheme.success(), v ->
                    confirm(I18n.tr(cutoff ? "Ask Radarr to search for all monitored movies that have not met their cutoff?"
                                    : "Ask Radarr to search for all monitored missing movies?"),
                            I18n.tr("SEARCH"), AppTheme.success(), () ->
                                    action(api2 -> {
                                        if (cutoff) api2.searchCutoffUnmet(); else api2.searchMissing();
                                        return null;
                                    }, "Search queued.", null))), new LinearLayout.LayoutParams(-2, dp(34)));
            listContainer.addView(bar);
            JSONArray recs = o.optJSONArray("records");
            if (recs == null || recs.length() == 0) {
                TextView none = text(I18n.tr(cutoff ? "No monitored movies with unmet cutoff." : "No monitored missing movies."),
                        12f, AppTheme.textSecondary(), false);
                none.setPadding(0, dp(12), 0, 0);
                listContainer.addView(none);
                return;
            }
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.optJSONObject(i);
                if (r != null) listContainer.addView(wantedRow(r));
            }
            addPager(pg, total);
        }, t -> { if (req == requestCounter) showError(t); });
    }

    private View wantedRow(final JSONObject m) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(10), dp(8));
        row.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(5);
        row.setLayoutParams(lp);
        row.addView(poster(m, 36, 52), new LinearLayout.LayoutParams(dp(36), dp(52)));
        LinearLayout tx = new LinearLayout(act);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(10), 0, dp(6), 0);
        TextView t = text(m.optString("title", "?") + (m.optInt("year") > 0 ? " (" + m.optInt("year") + ")" : ""),
                12.5f, AppTheme.text(), true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(t);
        StringBuilder dates = new StringBuilder(capitalize(m.optString("status", "")));
        String dig = m.optString("digitalRelease", "");
        String cin = m.optString("inCinemas", "");
        if (dig.length() >= 10) dates.append(" - ").append(I18n.tr("Digital")).append(" ").append(dig.substring(0, 10));
        else if (cin.length() >= 10) dates.append(" - ").append(I18n.tr("Cinemas")).append(" ").append(cin.substring(0, 10));
        if (m.optBoolean("hasFile")) {
            String qn = qualityName(m.optJSONObject("movieFile"));
            if (!qn.isEmpty()) dates.append(" - ").append(qn);
        }
        tx.addView(text(dates.toString(), 10.5f, AppTheme.textMuted(), false));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final int mid = m.optInt("id");
        row.addView(actionButton("SEARCH", AppTheme.success(), v ->
                action(api -> { api.searchMovie(mid); return null; }, "Search queued.", null)), new LinearLayout.LayoutParams(-2, dp(32)));
        if (mid > 0) {
            tx.setOnClickListener(v -> openMovie(mid));
        }
        return row;
    }

    private void addPager(int pg, int total) {
        LinearLayout pager = new LinearLayout(act);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(10);
        pager.setLayoutParams(plp);
        if (pg > 1) pager.addView(actionButton("PREV", AppTheme.accent(), v -> { page--; refresh(); }), weight());
        if (pg * PAGE_SIZE < total) pager.addView(actionButton("NEXT", AppTheme.accent(), v -> { page++; refresh(); }), weight());
        if (pager.getChildCount() > 0) listContainer.addView(pager);
    }

    // ================================================================== SYSTEM

    private void loadSystem() {
        final int req = ++requestCounter;
        showMessage("Loading...");
        async(api -> {
            JSONObject o = new JSONObject();
            o.put("status", api.status());
            try { o.put("health", api.health()); } catch (Throwable ignored) {}
            try { o.put("disk", api.diskSpace()); } catch (Throwable ignored) {}
            try { o.put("roots", api.rootFolders()); } catch (Throwable ignored) {}
            try { o.put("profiles", api.qualityProfiles()); } catch (Throwable ignored) {}
            try { o.put("indexers", api.indexers()); } catch (Throwable ignored) {}
            try { o.put("clients", api.downloadClients()); } catch (Throwable ignored) {}
            return o;
        }, o -> {
            if (req != requestCounter) return;
            renderSystem(o);
        }, t -> { if (req == requestCounter) showError(t); });
    }

    private LinearLayout sysCard(String title) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        card.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        TextView t = text(I18n.tr(title), 11f, AppTheme.textMuted(), true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(4));
        card.addView(t);
        listContainer.addView(card);
        return card;
    }

    private void renderSystem(JSONObject o) {
        listContainer.removeAllViews();
        JSONObject st = o.optJSONObject("status");
        LinearLayout c = sysCard("STATUS");
        if (st != null) {
            c.addView(text("Radarr v" + st.optString("version") + "  -  " + st.optString("osName") + " " + st.optString("osVersion"), 12f, AppTheme.text(), false));
            c.addView(text(st.optString("appData"), 10.5f, AppTheme.textMuted(), false));
            String sd = st.optString("startTime", "");
            if (!sd.isEmpty()) c.addView(text(I18n.tr("Started") + ": " + sd.replace('T', ' ').replaceAll("\\..*$", "").replace("Z", " UTC"), 10.5f, AppTheme.textMuted(), false));
        }

        JSONArray health = o.optJSONArray("health");
        c = sysCard("HEALTH");
        if (health == null || health.length() == 0) {
            c.addView(text("✔ " + I18n.tr("No problems detected."), 12f, AppTheme.success(), false));
        } else {
            for (int i = 0; i < health.length(); i++) {
                JSONObject h = health.optJSONObject(i);
                if (h == null) continue;
                boolean err = "error".equalsIgnoreCase(h.optString("type"));
                TextView t = text((err ? "✖ " : "⚠ ") + h.optString("message"), 11.5f, err ? AppTheme.danger() : AppTheme.warning(), false);
                t.setPadding(0, dp(3), 0, dp(3));
                c.addView(t);
            }
        }

        JSONArray disk = o.optJSONArray("disk");
        if (disk != null && disk.length() > 0) {
            c = sysCard("DISK SPACE");
            for (int i = 0; i < disk.length(); i++) {
                JSONObject dsk = disk.optJSONObject(i);
                if (dsk == null) continue;
                long total = dsk.optLong("totalSpace"), free = dsk.optLong("freeSpace");
                String label = dsk.optString("label", "").isEmpty() ? dsk.optString("path") : dsk.optString("label") + " (" + dsk.optString("path") + ")";
                c.addView(text(label, 11.5f, AppTheme.text(), false));
                ProgressBar pb = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(total > 0 ? (int) ((total - free) * 100 / total) : 0);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(4));
                plp.topMargin = dp(3);
                c.addView(pb, plp);
                TextView fr = text(fmtSize(free) + " " + I18n.tr("free of") + " " + fmtSize(total), 10.5f, AppTheme.textMuted(), false);
                fr.setPadding(0, dp(2), 0, dp(6));
                c.addView(fr);
            }
        }

        // root folders
        c = sysCard("ROOT FOLDERS");
        JSONArray roots = o.optJSONArray("roots");
        for (int i = 0; roots != null && i < roots.length(); i++) {
            final JSONObject rf = roots.optJSONObject(i);
            if (rf == null) continue;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout tx = new LinearLayout(act);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(text(rf.optString("path"), 12f, AppTheme.text(), false));
            tx.addView(text(fmtSize(rf.optLong("freeSpace")) + " " + I18n.tr("free"), 10.5f, AppTheme.textMuted(), false));
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
            row.addView(actionButton("REMOVE", AppTheme.danger(), v ->
                    confirm(I18n.tr("Remove this root folder from Radarr? Files are not deleted."), I18n.tr("REMOVE"), AppTheme.danger(), () ->
                            action(api -> { api.deleteRootFolder(rf.optInt("id")); return null; }, "Removed.", this::refresh))),
                    new LinearLayout.LayoutParams(-2, dp(32)));
            c.addView(row);
        }
        c.addView(actionButton("+ ADD ROOT FOLDER", AppTheme.accent(), v -> showAddRoot()),
                lpTop(dp(34), dp(8)));

        // profiles
        c = sysCard("QUALITY PROFILES");
        JSONArray profs = o.optJSONArray("profiles");
        for (int i = 0; profs != null && i < profs.length(); i++) {
            JSONObject p = profs.optJSONObject(i);
            if (p != null) c.addView(text("• " + p.optString("name"), 12f, AppTheme.text(), false));
        }

        c = sysCard("INDEXERS");
        addEnabledList(c, o.optJSONArray("indexers"), "enableRss");
        c = sysCard("DOWNLOAD CLIENTS");
        addEnabledList(c, o.optJSONArray("clients"), "enable");

        c = sysCard("TASKS");
        LinearLayout r1 = new LinearLayout(act);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(actionButton("RSS SYNC", AppTheme.accent(), v -> action(api -> { api.simpleCommand("RssSync"); return null; }, "RSS sync queued.", null)), weight());
        r1.addView(actionButton("REFRESH ALL", AppTheme.accent(), v -> action(api -> { api.simpleCommand("RefreshMovie"); return null; }, "Refresh queued.", null)), weight());
        c.addView(r1);
        LinearLayout r2 = new LinearLayout(act);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(actionButton("RESCAN ALL", AppTheme.accent(), v -> action(api -> { api.simpleCommand("RescanMovie"); return null; }, "Rescan queued.", null)), weight());
        r2.addView(actionButton("BACKUP", AppTheme.accent(), v -> action(api -> { api.simpleCommand("Backup"); return null; }, "Backup queued.", null)), weight());
        c.addView(r2, lpTop(-2, dp(6)));
        LinearLayout r3 = new LinearLayout(act);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        r3.addView(actionButton("CHECK DOWNLOADS", AppTheme.accent(), v -> action(api -> { api.simpleCommand("RefreshMonitoredDownloads"); return null; }, "Check queued.", null)), weight());
        r3.addView(actionButton("IMPORT SCAN", AppTheme.accent(), v -> action(api -> { api.simpleCommand("DownloadedMoviesScan"); return null; }, "Scan queued.", null)), weight());
        c.addView(r3, lpTop(-2, dp(6)));
        TextView note = text(I18n.tr("Indexers and download clients are managed by Prowlarr / the SERVICES tab. Advanced settings are available in Radarr's own web interface."),
                10.5f, AppTheme.textMuted(), false);
        note.setPadding(0, dp(12), 0, 0);
        listContainer.addView(note);
    }

    private void addEnabledList(LinearLayout card, JSONArray arr, String flag) {
        if (arr == null || arr.length() == 0) {
            card.addView(text(I18n.tr("None configured."), 11.5f, AppTheme.textSecondary(), false));
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            boolean on = o.has(flag) ? o.optBoolean(flag) : true;
            TextView t = text((on ? "● " : "○ ") + o.optString("name") + "  (" + o.optString("implementationName", o.optString("implementation")) + ")",
                    11.5f, on ? AppTheme.text() : AppTheme.textMuted(), false);
            t.setPadding(0, dp(2), 0, dp(2));
            card.addView(t);
        }
    }

    private void showAddRoot() {
        final LinearLayout body = dialogBody(I18n.tr("Add root folder"));
        final EditText path = new EditText(act);
        path.setHint("/path/to/movies");
        path.setHintTextColor(AppTheme.textMuted());
        path.setTextColor(AppTheme.text());
        path.setSingleLine(true);
        body.addView(path);
        final Dialog d = dialog(body);
        LinearLayout row = buttonRow();
        row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        row.addView(actionButton("ADD", AppTheme.success(), v -> {
            final String p = path.getText().toString().trim();
            if (p.isEmpty()) return;
            d.dismiss();
            action(api -> { api.addRootFolder(p); return null; }, "Root folder added.", this::refresh);
        }), weight());
        body.addView(row);
        d.show();
    }

    // ================================================================== dialogs & small UI helpers

    private LinearLayout dialogBody(String title) {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(16), dp(18), dp(14));
        TextView t = text(title, 15f, AppTheme.text(), true);
        t.setPadding(0, 0, 0, dp(6));
        body.addView(t);
        return body;
    }

    private Dialog dialog(View content) {
        AlertDialog d = new AlertDialog.Builder(act).setView(content).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(rounded(AppTheme.card(), AppTheme.outline(), 16));
        return d;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        return row;
    }

    private void confirm(String message, String positive, int color, final Runnable onYes) {
        final LinearLayout body = dialogBody(I18n.tr("Confirm"));
        body.addView(text(message, 12.5f, AppTheme.textSecondary(), false));
        final Dialog d = dialog(body);
        LinearLayout row = buttonRow();
        row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        row.addView(actionButton(positive, color, v -> { d.dismiss(); onYes.run(); }), weight());
        body.addView(row);
        d.show();
    }

    /** Poster for a movie or lookup result: local MediaCover url for library items, remoteUrl otherwise. */
    private ImageView poster(JSONObject movie, int wDp, int hDp) {
        final ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 6));
        iv.setClipToOutline(true);
        String url = null;
        JSONArray imgs = movie.optJSONArray("images");
        for (int i = 0; imgs != null && i < imgs.length(); i++) {
            JSONObject im = imgs.optJSONObject(i);
            if (im == null || !"poster".equals(im.optString("coverType"))) continue;
            String local = im.optString("url", "");
            String remote = im.optString("remoteUrl", "");
            url = movie.optInt("id") > 0 && !local.isEmpty() ? local : (!remote.isEmpty() ? remote : local);
            break;
        }
        if (url == null || url.isEmpty()) return iv;
        final String u = url;
        Bitmap cached = imgCache.get(u);
        if (cached != null) { iv.setImageBitmap(cached); return iv; }
        iv.setTag(u);
        imgPool.execute(() -> {
            try {
                RadarrApi api = RadarrApi.get(act);
                final Bitmap bmp = api.loadImage(u, 300);
                if (bmp == null) return;
                imgCache.put(u, bmp);
                act.runOnUiThread(() -> { if (u.equals(iv.getTag())) iv.setImageBitmap(bmp); });
            } catch (Throwable ignored) {}
        });
        return iv;
    }

    private void showMessage(String m) {
        listContainer.removeAllViews();
        TextView t = text(I18n.tr(m), 12f, AppTheme.textSecondary(), false);
        t.setPadding(0, dp(16), 0, 0);
        listContainer.addView(t);
    }

    private void showError(Throwable err) {
        listContainer.removeAllViews();
        TextView t = text(msg(err), 12f, AppTheme.danger(), false);
        t.setPadding(0, dp(16), 0, dp(8));
        listContainer.addView(t);
        listContainer.addView(actionButton("RETRY", AppTheme.accent(), v -> refresh()), new LinearLayout.LayoutParams(-1, dp(40)));
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
        lp.setMarginEnd(dp(6));
        return lp;
    }

    private LinearLayout.LayoutParams lpTop(int h, int topPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, h);
        lp.topMargin = topPx;
        return lp;
    }

    private TextView pill(String label, boolean selected) {
        TextView t = text(I18n.tr(label), 10f, selected ? AppTheme.onAccent() : AppTheme.textSecondary(), true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackground(rounded(selected ? AppTheme.accent() : AppTheme.card(), AppTheme.outline(), 17));
        t.setClickable(true);
        return t;
    }

    private Button actionButton(String label, int c, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(I18n.tr(label));
        b.setAllCaps(false);
        b.setTextSize(10.5f);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(c);
        b.setBackground(rounded(Color.argb(25, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(80, Color.red(c), Color.green(c), Color.blue(c)), 8));
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        double gb = bytes / 1073741824.0;
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb);
        double mb = bytes / 1048576.0;
        if (mb >= 1) return String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    private static String fmtAge(double minutes) {
        if (minutes < 60) return (int) minutes + "m";
        if (minutes < 2880) return (int) (minutes / 60) + "h";
        return (int) (minutes / 1440) + "d";
    }
}
