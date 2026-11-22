package com.fishbowl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fishbowl.app.services.TransmissionApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Transmission" tab: a native client for Transmission's web UI (in the spirit of nzb360).
 *
 * <ul>
 *   <li>TORRENTS - live list (auto refresh every ~3 s while visible), filters, search, sort, and a detail
 *       page with files (wanted / priority), peers, trackers and actions.</li>
 *   <li>ADD - magnet links / URLs / info hashes with optional download dir and start-paused.</li>
 *   <li>SPEED - current rates, global limits, alt-speed (turtle) and session statistics.</li>
 *   <li>SETTINGS - directories, seeding ratio, peer limits, queues, DHT/PEX/LPD, encryption, blocklist, port test.</li>
 * </ul>
 */
public final class TransmissionTabView {

    private static final int S_TORRENTS = 0;
    private static final int S_ADD = 1;
    private static final int S_SPEED = 2;
    private static final int S_SETTINGS = 3;

    private static final String[] SECTION_NAMES = {"TORRENTS", "ADD", "SPEED", "SETTINGS"};
    private static final String[] FILTER_NAMES = {"All", "Downloading", "Seeding", "Paused", "Error", "Completed"};
    private static final String[] SORT_NAMES = {"Name", "Date added", "Progress", "Size", "Download speed", "Ratio", "Queue"};

    private static final int D_FILES = 0;
    private static final int D_PEERS = 1;
    private static final int D_TRACKERS = 2;
    private static final int D_INFO = 3;
    private static final String[] DETAIL_TABS = {"FILES", "PEERS", "TRACKERS", "INFO"};

    private static final long TICK_MS = 3000L;

    private interface Job<T> { T run(TransmissionApi api) throws Exception; }
    private interface Done<T> { void run(T value); }
    private interface Collect { void put(JSONObject o) throws Exception; }
    private interface IntDone { void run(int value); }

    private final Activity act;
    private final LinearLayout root;
    private final LinearLayout navRow;
    private final LinearLayout listContainer;
    private final ScrollView scroll;
    private final TextView summary;
    private final ExecutorService bg = Executors.newFixedThreadPool(2);

    private int section = S_TORRENTS;
    private int requestCounter = 0;
    private int detailId = 0;
    private int detailTab = D_FILES;
    private volatile boolean polling = false;
    private boolean errorShown = false;

    // torrents list state
    private JSONArray torrents = null;
    private int filter = 0;
    private int sortKey = 1;
    private boolean sortDesc = true;
    private String query = "";
    private LinearLayout rowsBox = null;
    private LinearLayout chipRow = null;
    private TextView countView = null;
    private TextView banner = null;
    private Button sortButton = null;

    // speed state
    private TextView liveDown = null;
    private TextView liveUp = null;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!root.isAttachedToWindow() || !isLive()) return;
            if (root.isShown()) tick();
            root.postDelayed(this, TICK_MS);
        }
    };

    public TransmissionTabView(Activity activity) {
        this.act = activity;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppTheme.bg());

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(4));

        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("TRANSMISSION", 14f, AppTheme.text(), true);
        title.setLetterSpacing(0.14f);
        titleCol.addView(title);
        TextView sub = text(I18n.tr("Manage your torrents from here"), 11f, AppTheme.textMuted(), false);
        sub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(sub);
        titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));
        LinearLayout.LayoutParams hb = new LinearLayout.LayoutParams(-2, dp(30));
        hb.setMarginStart(dp(6));
        titleRow.addView(actionButton("PAUSE ALL", AppTheme.warning(), v -> pauseAll()), hb);
        titleRow.addView(actionButton("RESUME ALL", AppTheme.success(), v -> resumeAll()), hb);
        header.addView(titleRow);

        summary = text("", 11f, AppTheme.textSecondary(), false);
        summary.setPadding(0, dp(6), 0, dp(6));
        header.addView(summary);
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

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { if (isLive()) startTicker(); }
            @Override public void onViewDetachedFromWindow(View v) { stopTicker(); }
        });
    }

    public View getView() { return root; }

    /** Returns true when the back press was consumed. */
    public boolean onBack() {
        if (detailId > 0) {
            detailId = 0;
            refresh();
            return true;
        }
        if (section != S_TORRENTS) {
            section = S_TORRENTS;
            refresh();
            return true;
        }
        return false;
    }

    public void refresh() {
        renderNav();
        stopTicker();
        errorShown = false;
        liveDown = null;
        liveUp = null;
        if (detailId > 0) { loadDetail(false); startTicker(); return; }
        switch (section) {
            case S_TORRENTS: loadTorrents(false); startTicker(); break;
            case S_ADD: renderAdd(); break;
            case S_SPEED: loadSpeed(); startTicker(); break;
            default: loadSettings(); break;
        }
    }

    private boolean isLive() {
        return detailId > 0 || section == S_TORRENTS || section == S_SPEED;
    }

    private void startTicker() {
        root.removeCallbacks(ticker);
        root.postDelayed(ticker, TICK_MS);
    }

    private void stopTicker() {
        root.removeCallbacks(ticker);
    }

    private void tick() {
        if (detailId > 0) loadDetail(true);
        else if (section == S_TORRENTS) loadTorrents(true);
        else if (section == S_SPEED) tickSpeed();
    }

    private void renderNav() {
        navRow.removeAllViews();
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            final int idx = i;
            TextView p = pill(SECTION_NAMES[i], idx == section && detailId == 0);
            p.setOnClickListener(v -> { section = idx; detailId = 0; refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.setMarginEnd(dp(6));
            navRow.addView(p, lp);
        }
    }

    // ================================================================== async helpers

    private <T> void async(final Job<T> job, final Done<T> ok, final Done<Throwable> err) {
        bg.execute(() -> {
            try {
                final TransmissionApi api = TransmissionApi.get(act);
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
            if (okMsg != null) toast(okMsg);
            if (after != null) after.run();
        }, t -> {
            toast(msg(t));
            if (after != null) after.run();
        });
    }

    private final Runnable afterAction = new Runnable() {
        @Override public void run() {
            if (detailId > 0) loadDetail(true);
            else if (section == S_TORRENTS) loadTorrents(true);
        }
    };

    private static String msg(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null ? TransmissionApi.NOT_RUNNING : m;
    }

    private void toast(String s) { Toast.makeText(act, I18n.tr(s), Toast.LENGTH_SHORT).show(); }

    private void pauseAll() {
        action(api -> { api.torrentStop(null); return null; }, "All torrents paused.", afterAction);
    }

    private void resumeAll() {
        action(api -> { api.torrentStart(null); return null; }, "All torrents resumed.", afterAction);
    }

    // ================================================================== TORRENTS list

    private void loadTorrents(final boolean silent) {
        if (silent && polling) return;
        final int req = silent ? requestCounter : ++requestCounter;
        if (silent) polling = true;
        if (!silent) {
            if (torrents != null) renderTorrentsShell();
            else showMessage("Loading...");
        }
        async(api -> api.torrentList(), arr -> {
            if (silent) polling = false;
            if (req != requestCounter || detailId > 0 || section != S_TORRENTS) return;
            torrents = arr;
            errorShown = false;
            if (rowsBox == null || rowsBox.getParent() != listContainer) renderTorrentsShell();
            banner.setVisibility(View.GONE);
            fillRows();
            updateSummary();
        }, t -> {
            if (silent) polling = false;
            if (req != requestCounter || detailId > 0 || section != S_TORRENTS) return;
            if (torrents != null && rowsBox != null && rowsBox.getParent() == listContainer) {
                banner.setText(I18n.tr(msg(t)));
                banner.setVisibility(View.VISIBLE);
                summary.setText(I18n.tr("Transmission not reachable"));
            } else if (!errorShown) {
                showError(t);
            }
        });
    }

    private void updateSummary() {
        if (torrents == null) return;
        long down = 0, up = 0;
        int active = 0;
        for (int i = 0; i < torrents.length(); i++) {
            JSONObject t = torrents.optJSONObject(i);
            if (t == null) continue;
            down += t.optLong("rateDownload");
            up += t.optLong("rateUpload");
            int st = t.optInt("status");
            if (st == TransmissionApi.ST_DOWNLOADING || st == TransmissionApi.ST_SEEDING) active++;
        }
        summary.setText("DL " + fmtSpeed(down) + "   UL " + fmtSpeed(up) + "   "
                + torrents.length() + " " + I18n.tr("torrents") + "   " + active + " " + I18n.tr("active"));
    }

    private void renderTorrentsShell() {
        listContainer.removeAllViews();
        errorShown = false;

        banner = text("", 11.5f, AppTheme.danger(), false);
        banner.setPadding(0, dp(8), 0, dp(2));
        banner.setVisibility(View.GONE);
        listContainer.addView(banner);

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(4));
        final EditText search = new EditText(act);
        search.setHint(I18n.tr("Search torrents"));
        search.setHintTextColor(AppTheme.textMuted());
        search.setTextColor(AppTheme.text());
        search.setTextSize(13f);
        search.setSingleLine(true);
        search.setText(query);
        search.setSelection(search.getText().length());
        bar.addView(search, new LinearLayout.LayoutParams(0, -2, 1.0f));
        sortButton = actionButton(sortLabel(), AppTheme.accent(), v -> showSortDialog());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, dp(36));
        slp.setMarginStart(dp(8));
        bar.addView(sortButton, slp);
        listContainer.addView(bar);

        HorizontalScrollView chipScroll = new HorizontalScrollView(act);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(act);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chipRow);
        listContainer.addView(chipScroll);

        countView = text("", 11f, AppTheme.textSecondary(), false);
        countView.setPadding(0, dp(8), 0, dp(2));
        listContainer.addView(countView);

        rowsBox = new LinearLayout(act);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        listContainer.addView(rowsBox);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                query = e.toString();
                fillRows();
            }
        });
    }

    private String sortLabel() {
        return I18n.tr("SORT") + ": " + I18n.tr(SORT_NAMES[sortKey]) + (sortDesc ? " v" : " ^");
    }

    private void showSortDialog() {
        final LinearLayout body = dialogBody(I18n.tr("Sort by"));
        final Dialog d = dialog(body);
        for (int i = 0; i < SORT_NAMES.length; i++) {
            final int idx = i;
            boolean sel = idx == sortKey;
            Button b = actionButton((sel ? (sortDesc ? "v  " : "^  ") : "") + I18n.tr(SORT_NAMES[i]),
                    sel ? AppTheme.accent() : AppTheme.textSecondary(), v -> {
                        if (idx == sortKey) sortDesc = !sortDesc;
                        else { sortKey = idx; sortDesc = idx != 0 && idx != 6; }
                        d.dismiss();
                        if (sortButton != null) sortButton.setText(sortLabel());
                        fillRows();
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(36));
            lp.topMargin = dp(6);
            body.addView(b, lp);
        }
        d.show();
    }

    private void fillRows() {
        if (rowsBox == null || torrents == null) return;
        int[] counts = new int[FILTER_NAMES.length];
        List<JSONObject> list = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < torrents.length(); i++) {
            JSONObject t = torrents.optJSONObject(i);
            if (t == null) continue;
            for (int f = 0; f < counts.length; f++) if (matches(t, f)) counts[f]++;
            if (!matches(t, filter)) continue;
            if (!q.isEmpty() && !t.optString("name", "").toLowerCase(Locale.ROOT).contains(q)) continue;
            list.add(t);
        }

        chipRow.removeAllViews();
        for (int f = 0; f < FILTER_NAMES.length; f++) {
            final int fi = f;
            TextView chip = pill(FILTER_NAMES[f], false);
            chip.setText(I18n.tr(FILTER_NAMES[f]) + " " + counts[f]);
            boolean sel = f == filter;
            chip.setTextColor(sel ? AppTheme.onAccent() : AppTheme.textSecondary());
            chip.setBackground(rounded(sel ? AppTheme.accent() : AppTheme.card(), AppTheme.outline(), 17));
            chip.setOnClickListener(v -> { filter = fi; fillRows(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.setMarginEnd(dp(6));
            chipRow.addView(chip, lp);
        }

        final int key = sortKey;
        final int dir = sortDesc ? -1 : 1;
        Collections.sort(list, (a, b) -> dir * compare(a, b, key));

        countView.setText(list.size() + " / " + torrents.length() + " " + I18n.tr("torrents"));
        rowsBox.removeAllViews();
        if (list.isEmpty()) {
            TextView t = text(I18n.tr(torrents.length() == 0
                    ? "No torrents yet. Open the ADD tab to add one." : "No matches."),
                    12f, AppTheme.textSecondary(), false);
            t.setPadding(0, dp(12), 0, 0);
            rowsBox.addView(t);
            return;
        }
        for (JSONObject t : list) rowsBox.addView(torrentRow(t));
    }

    private static int compare(JSONObject a, JSONObject b, int key) {
        switch (key) {
            case 0: return a.optString("name", "").compareToIgnoreCase(b.optString("name", ""));
            case 1: return Long.compare(a.optLong("addedDate"), b.optLong("addedDate"));
            case 2: return Double.compare(a.optDouble("percentDone"), b.optDouble("percentDone"));
            case 3: return Long.compare(a.optLong("totalSize"), b.optLong("totalSize"));
            case 4: return Long.compare(a.optLong("rateDownload"), b.optLong("rateDownload"));
            case 5: return Double.compare(a.optDouble("uploadRatio"), b.optDouble("uploadRatio"));
            default: return Integer.compare(a.optInt("queuePosition"), b.optInt("queuePosition"));
        }
    }

    private static boolean isDone(JSONObject t) {
        return t.optDouble("percentDone", 0) >= 1.0
                || (t.optLong("sizeWhenDone") > 0 && t.optLong("leftUntilDone", 1) == 0);
    }

    private static boolean matches(JSONObject t, int f) {
        int st = t.optInt("status");
        switch (f) {
            case 1: return st == TransmissionApi.ST_DOWNLOADING || st == TransmissionApi.ST_DOWNLOAD_WAIT;
            case 2: return st == TransmissionApi.ST_SEEDING || st == TransmissionApi.ST_SEED_WAIT;
            case 3: return st == TransmissionApi.ST_STOPPED;
            case 4: return t.optInt("error") != 0;
            case 5: return isDone(t);
            default: return true;
        }
    }

    private static String statusLabel(JSONObject t) {
        if (t.optInt("error") != 0) return "Error";
        switch (t.optInt("status")) {
            case TransmissionApi.ST_STOPPED: return isDone(t) ? "Completed" : "Paused";
            case TransmissionApi.ST_CHECK_WAIT: return "Queued to verify";
            case TransmissionApi.ST_CHECKING: return "Verifying";
            case TransmissionApi.ST_DOWNLOAD_WAIT: return "Queued";
            case TransmissionApi.ST_DOWNLOADING: return "Downloading";
            case TransmissionApi.ST_SEED_WAIT: return "Queued to seed";
            case TransmissionApi.ST_SEEDING: return "Seeding";
            default: return "Unknown";
        }
    }

    private static int statusColor(JSONObject t) {
        if (t.optInt("error") != 0) return AppTheme.danger();
        switch (t.optInt("status")) {
            case TransmissionApi.ST_STOPPED: return isDone(t) ? AppTheme.success() : AppTheme.textMuted();
            case TransmissionApi.ST_DOWNLOADING: return AppTheme.accent();
            case TransmissionApi.ST_SEEDING: return AppTheme.success();
            default: return AppTheme.warning();
        }
    }

    private View torrentRow(final JSONObject t) {
        final int id = t.optInt("id");
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> { detailId = id; detailTab = D_FILES; refresh(); });

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(t.optString("name", "?"), 13f, AppTheme.text(), true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final boolean stopped = t.optInt("status") == TransmissionApi.ST_STOPPED;
        Button quick = actionButton(stopped ? "START" : "PAUSE", stopped ? AppTheme.success() : AppTheme.warning(), v -> {
            final int[] ids = new int[]{id};
            if (stopped) action(api -> { api.torrentStart(ids); return null; }, null, afterAction);
            else action(api -> { api.torrentStop(ids); return null; }, null, afterAction);
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, dp(28));
        qlp.setMarginStart(dp(8));
        top.addView(quick, qlp);
        row.addView(top);

        double pct = t.optDouble("percentDone", 0);
        if (t.optInt("status") == TransmissionApi.ST_CHECKING) pct = t.optDouble("recheckProgress", pct);
        row.addView(progressBar(pct, statusColor(t)));

        String l1 = I18n.tr(statusLabel(t)) + "  -  " + fmtPct(pct) + "  -  "
                + fmtSize(t.optLong("sizeWhenDone")) + (t.optInt("status") == TransmissionApi.ST_SEEDING
                ? "  -  " + I18n.tr("Ratio") + " " + fmtRatio(t.optDouble("uploadRatio")) : "");
        TextView tl1 = text(l1, 10.5f, statusColor(t), false);
        tl1.setPadding(0, dp(4), 0, 0);
        row.addView(tl1);

        StringBuilder l2 = new StringBuilder();
        l2.append("DL ").append(fmtSpeed(t.optLong("rateDownload")))
                .append("   UL ").append(fmtSpeed(t.optLong("rateUpload")));
        if (t.optInt("status") == TransmissionApi.ST_DOWNLOADING) l2.append("   ETA ").append(fmtEta(t.optLong("eta", -1)));
        l2.append("   ").append(I18n.tr("Peers")).append(" ")
                .append(t.optInt("peersSendingToUs")).append("/").append(t.optInt("peersConnected"));
        TextView tl2 = text(l2.toString(), 10.5f, AppTheme.textSecondary(), false);
        tl2.setPadding(0, dp(2), 0, 0);
        row.addView(tl2);

        if (t.optInt("error") != 0 && !t.optString("errorString").isEmpty()) {
            TextView er = text(t.optString("errorString"), 10.5f, AppTheme.danger(), false);
            er.setMaxLines(2);
            er.setEllipsize(TextUtils.TruncateAt.END);
            er.setPadding(0, dp(2), 0, 0);
            row.addView(er);
        }
        return row;
    }

    private View progressBar(double pct, int color) {
        if (pct < 0) pct = 0;
        if (pct > 1) pct = 1;
        LinearLayout track = new LinearLayout(act);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setWeightSum(1000f);
        GradientDrawable g = new GradientDrawable();
        g.setColor(AppTheme.inner());
        g.setCornerRadius(dp(3));
        track.setBackground(g);
        View fill = new View(act);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(color);
        fg.setCornerRadius(dp(3));
        fill.setBackground(fg);
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, (float) (pct * 1000.0)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(6));
        lp.topMargin = dp(8);
        track.setLayoutParams(lp);
        return track;
    }

    // ================================================================== torrent DETAIL

    private void loadDetail(final boolean silent) {
        if (silent && polling) return;
        final int id = detailId;
        final int req = silent ? requestCounter : ++requestCounter;
        if (silent) polling = true;
        else showMessage("Loading...");
        async(api -> api.torrentDetails(id), t -> {
            if (silent) polling = false;
            if (req != requestCounter || id != detailId) return;
            errorShown = false;
            int y = scroll.getScrollY();
            renderDetail(t);
            if (silent) {
                final int fy = y;
                scroll.post(() -> scroll.scrollTo(0, fy));
            }
        }, e -> {
            if (silent) polling = false;
            if (req != requestCounter || id != detailId) return;
            String m = msg(e);
            if (m.contains("not found")) {
                detailId = 0;
                refresh();
            } else if (!silent) {
                showError(e);
            }
        });
    }

    private void renderDetail(final JSONObject t) {
        final int id = t.optInt("id");
        listContainer.removeAllViews();

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(10), 0, dp(4));
        head.addView(actionButton("< BACK", AppTheme.accent(), v -> onBack()), new LinearLayout.LayoutParams(-2, dp(32)));
        listContainer.addView(head);

        TextView name = text(t.optString("name", "?"), 14f, AppTheme.text(), true);
        name.setPadding(0, dp(4), 0, dp(2));
        listContainer.addView(name);

        double pct = t.optDouble("percentDone", 0);
        if (t.optInt("status") == TransmissionApi.ST_CHECKING) pct = t.optDouble("recheckProgress", pct);
        listContainer.addView(progressBar(pct, statusColor(t)));
        TextView st = text(I18n.tr(statusLabel(t)) + "  -  " + fmtPct(pct) + "  -  "
                + fmtSize(t.optLong("sizeWhenDone") - t.optLong("leftUntilDone")) + " / " + fmtSize(t.optLong("sizeWhenDone")),
                11.5f, statusColor(t), true);
        st.setPadding(0, dp(6), 0, 0);
        listContainer.addView(st);
        if (t.optInt("error") != 0 && !t.optString("errorString").isEmpty()) {
            TextView er = text(t.optString("errorString"), 11f, AppTheme.danger(), false);
            er.setPadding(0, dp(2), 0, 0);
            listContainer.addView(er);
        }
        StringBuilder l2 = new StringBuilder();
        l2.append("DL ").append(fmtSpeed(t.optLong("rateDownload"))).append("   UL ").append(fmtSpeed(t.optLong("rateUpload")));
        l2.append("   ETA ").append(fmtEta(t.optLong("eta", -1)));
        l2.append("   ").append(I18n.tr("Ratio")).append(" ").append(fmtRatio(t.optDouble("uploadRatio")));
        l2.append("   ").append(I18n.tr("Peers")).append(" ").append(t.optInt("peersConnected"));
        TextView tl2 = text(l2.toString(), 11f, AppTheme.textSecondary(), false);
        tl2.setPadding(0, dp(2), 0, dp(8));
        listContainer.addView(tl2);

        final int[] ids = new int[]{id};
        LinearLayout a1 = buttonRow();
        a1.addView(actionButton("START", AppTheme.success(), v ->
                action(api -> { api.torrentStartNow(ids); return null; }, "Started.", afterAction)), weight());
        a1.addView(actionButton("PAUSE", AppTheme.warning(), v ->
                action(api -> { api.torrentStop(ids); return null; }, "Paused.", afterAction)), weight());
        a1.addView(actionButton("VERIFY", AppTheme.accent(), v ->
                action(api -> { api.torrentVerify(ids); return null; }, "Verifying...", afterAction)), weight());
        a1.addView(actionButton("REANNOUNCE", AppTheme.accent(), v ->
                action(api -> { api.torrentReannounce(ids); return null; }, "Reannounce requested.", afterAction)), weight());
        listContainer.addView(a1);

        LinearLayout a2 = buttonRow();
        a2.addView(actionButton("MOVE", AppTheme.accent(), v -> showMoveDialog(id, t.optString("downloadDir", ""))), weight());
        a2.addView(actionButton("REMOVE", AppTheme.danger(), v -> showRemoveDialog(id, t.optString("name", ""))), weight());
        listContainer.addView(a2);

        LinearLayout q = buttonRow();
        q.addView(text(I18n.tr("Queue") + " #" + t.optInt("queuePosition"), 11f, AppTheme.textSecondary(), false),
                new LinearLayout.LayoutParams(-2, dp(34)));
        q.addView(actionButton("TOP", AppTheme.textSecondary(), v ->
                action(api -> { api.queueMoveTop(ids); return null; }, null, afterAction)), weight());
        q.addView(actionButton("UP", AppTheme.textSecondary(), v ->
                action(api -> { api.queueMoveUp(ids); return null; }, null, afterAction)), weight());
        q.addView(actionButton("DOWN", AppTheme.textSecondary(), v ->
                action(api -> { api.queueMoveDown(ids); return null; }, null, afterAction)), weight());
        q.addView(actionButton("BOTTOM", AppTheme.textSecondary(), v ->
                action(api -> { api.queueMoveBottom(ids); return null; }, null, afterAction)), weight());
        listContainer.addView(q);

        HorizontalScrollView tabScroll = new HorizontalScrollView(act);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(12), 0, dp(6));
        for (int i = 0; i < DETAIL_TABS.length; i++) {
            final int ti = i;
            TextView p = pill(DETAIL_TABS[i], ti == detailTab);
            p.setOnClickListener(v -> { detailTab = ti; loadDetail(true); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.setMarginEnd(dp(6));
            tabs.addView(p, lp);
        }
        tabScroll.addView(tabs);
        listContainer.addView(tabScroll);

        switch (detailTab) {
            case D_FILES: renderFiles(t); break;
            case D_PEERS: renderPeers(t); break;
            case D_TRACKERS: renderTrackers(t); break;
            default: renderInfo(t); break;
        }
    }

    private void renderFiles(final JSONObject t) {
        final int id = t.optInt("id");
        final JSONArray files = t.optJSONArray("files");
        final JSONArray stats = t.optJSONArray("fileStats");
        if (files == null || files.length() == 0) {
            listContainer.addView(text(I18n.tr("No file list yet (waiting for metadata)."), 12f, AppTheme.textSecondary(), false));
            return;
        }
        final int n = files.length();
        LinearLayout bulk = buttonRow();
        bulk.addView(actionButton("SELECT ALL", AppTheme.accent(), v -> bulkWanted(id, n, true)), weight());
        bulk.addView(actionButton("SELECT NONE", AppTheme.textSecondary(), v -> bulkWanted(id, n, false)), weight());
        listContainer.addView(bulk);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            JSONObject f = files.optJSONObject(i);
            JSONObject fs = stats == null ? null : stats.optJSONObject(i);
            if (f == null) continue;
            long len = f.optLong("length");
            long done = fs != null ? fs.optLong("bytesCompleted") : f.optLong("bytesCompleted");
            boolean wanted = fs == null || fs.optBoolean("wanted", true);
            final int prio = fs == null ? 0 : fs.optInt("priority", 0);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(8), dp(8));
            row.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = dp(5);
            row.setLayoutParams(rlp);

            final Switch sw = new Switch(act);
            sw.setChecked(wanted);
            UiStyle.styleSwitch(act, sw);
            sw.setOnCheckedChangeListener((b, checked) -> action(api -> {
                api.setFilesWanted(id, new int[]{idx}, checked);
                return null;
            }, null, afterAction));
            row.addView(sw);

            LinearLayout tx = new LinearLayout(act);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(8), 0, dp(6), 0);
            TextView fn = text(f.optString("name", "?"), 12f, wanted ? AppTheme.text() : AppTheme.textMuted(), false);
            fn.setMaxLines(2);
            fn.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            tx.addView(fn);
            double fp = len > 0 ? (double) done / (double) len : 0;
            TextView info = text(fmtPct(fp) + "  -  " + fmtSize(done) + " / " + fmtSize(len),
                    10.5f, AppTheme.textSecondary(), false);
            info.setPadding(0, dp(2), 0, 0);
            tx.addView(info);
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));

            String pl = prio > 0 ? "HIGH" : prio < 0 ? "LOW" : "NORMAL";
            int pc = prio > 0 ? AppTheme.warning() : AppTheme.textSecondary();
            row.addView(actionButton(pl, pc, v -> choose("File priority", new String[]{"Low", "Normal", "High"}, prio + 1,
                    sel -> action(api -> { api.setFilePriority(id, new int[]{idx}, sel - 1); return null; }, null, afterAction))),
                    new LinearLayout.LayoutParams(-2, dp(30)));
            listContainer.addView(row);
        }
    }

    private void bulkWanted(final int id, final int n, final boolean wanted) {
        final int[] all = new int[n];
        for (int i = 0; i < n; i++) all[i] = i;
        action(api -> { api.setFilesWanted(id, all, wanted); return null; }, null, afterAction);
    }

    private void renderPeers(JSONObject t) {
        JSONArray peers = t.optJSONArray("peers");
        if (peers == null || peers.length() == 0) {
            listContainer.addView(text(I18n.tr("No connected peers."), 12f, AppTheme.textSecondary(), false));
            return;
        }
        for (int i = 0; i < peers.length(); i++) {
            JSONObject p = peers.optJSONObject(i);
            if (p == null) continue;
            LinearLayout row = card();
            TextView a = text(p.optString("address", "?") + ":" + p.optInt("port"), 12.5f, AppTheme.text(), true);
            row.addView(a);
            String client = p.optString("clientName", "");
            TextView l1 = text((client.isEmpty() ? I18n.tr("Unknown client") : client) + "  -  "
                    + fmtPct(p.optDouble("progress")) + "  -  " + p.optString("flagStr", ""), 10.5f, AppTheme.textMuted(), false);
            l1.setPadding(0, dp(2), 0, 0);
            row.addView(l1);
            TextView l2 = text("DL " + fmtSpeed(p.optLong("rateToClient")) + "   UL " + fmtSpeed(p.optLong("rateToPeer")),
                    10.5f, AppTheme.textSecondary(), false);
            l2.setPadding(0, dp(2), 0, 0);
            row.addView(l2);
            listContainer.addView(row);
        }
    }

    private void renderTrackers(JSONObject t) {
        JSONArray ts = t.optJSONArray("trackerStats");
        if (ts == null || ts.length() == 0) {
            listContainer.addView(text(I18n.tr("No trackers."), 12f, AppTheme.textSecondary(), false));
            return;
        }
        for (int i = 0; i < ts.length(); i++) {
            JSONObject k = ts.optJSONObject(i);
            if (k == null) continue;
            LinearLayout row = card();
            String host = k.optString("host", "");
            TextView a = text(host.isEmpty() ? k.optString("announce", "?") : host, 12.5f, AppTheme.text(), true);
            row.addView(a);
            String res = k.optString("lastAnnounceResult", "");
            boolean ok = k.optBoolean("lastAnnounceSucceeded", false);
            TextView l1 = text((res.isEmpty() ? I18n.tr("No announce yet") : res), 11f,
                    ok || res.isEmpty() ? AppTheme.textSecondary() : AppTheme.danger(), false);
            l1.setPadding(0, dp(2), 0, 0);
            row.addView(l1);
            long last = k.optLong("lastAnnounceTime");
            long next = k.optLong("nextAnnounceTime");
            TextView l2 = text(I18n.tr("Seeds") + " " + k.optInt("seederCount") + "   " + I18n.tr("Leechers") + " "
                    + k.optInt("leecherCount") + (last > 0 ? "   " + I18n.tr("Last") + " " + fmtDate(last) : "")
                    + (next > 0 ? "   " + I18n.tr("Next") + " " + fmtDate(next) : ""), 10.5f, AppTheme.textMuted(), false);
            l2.setPadding(0, dp(2), 0, 0);
            row.addView(l2);
            listContainer.addView(row);
        }
    }

    private void renderInfo(final JSONObject t) {
        final int id = t.optInt("id");
        LinearLayout c = card();
        c.addView(infoLine("Location", t.optString("downloadDir", "")));
        c.addView(infoLine("Hash", t.optString("hashString", "")));
        c.addView(infoLine("Added", fmtDate(t.optLong("addedDate"))));
        long done = t.optLong("doneDate");
        c.addView(infoLine("Completed", done > 0 ? fmtDate(done) : "-"));
        c.addView(infoLine("Total size", fmtSize(t.optLong("totalSize"))));
        c.addView(infoLine("Downloaded", fmtSize(t.optLong("downloadedEver"))));
        c.addView(infoLine("Uploaded", fmtSize(t.optLong("uploadedEver"))));
        c.addView(infoLine("Corrupt", fmtSize(t.optLong("corruptEver"))));
        c.addView(infoLine("Pieces", t.optInt("pieceCount") + " x " + fmtSize(t.optLong("pieceSize"))));
        c.addView(infoLine("Private", t.optBoolean("isPrivate") ? "Yes" : "No"));
        String creator = t.optString("creator", "");
        if (!creator.isEmpty()) c.addView(infoLine("Created by", creator));
        String comment = t.optString("comment", "");
        if (!comment.isEmpty()) c.addView(infoLine("Comment", comment));
        JSONArray labels = t.optJSONArray("labels");
        StringBuilder lb = new StringBuilder();
        for (int i = 0; labels != null && i < labels.length(); i++) {
            if (i > 0) lb.append(", ");
            lb.append(labels.optString(i));
        }
        c.addView(infoLine("Labels", lb.length() == 0 ? "-" : lb.toString()));
        int bp = t.optInt("bandwidthPriority");
        c.addView(infoLine("Bandwidth priority", bp > 0 ? "High" : bp < 0 ? "Low" : "Normal"));
        c.addView(infoLine("Speed limits",
                (t.optBoolean("downloadLimited") ? "DL " + t.optInt("downloadLimit") + " KB/s" : "DL -") + "   "
                        + (t.optBoolean("uploadLimited") ? "UL " + t.optInt("uploadLimit") + " KB/s" : "UL -")));
        listContainer.addView(c);

        LinearLayout r1 = buttonRow();
        r1.addView(actionButton("LABELS", AppTheme.accent(), v -> showLabelsDialog(id, lb.toString())), weight());
        r1.addView(actionButton("LIMITS", AppTheme.accent(), v -> showLimitsDialog(t)), weight());
        final String magnet = t.optString("magnetLink", "");
        r1.addView(actionButton("COPY MAGNET", AppTheme.accent(), v -> {
            if (magnet.isEmpty()) { toast("No magnet link available."); return; }
            copyToClipboard(magnet);
            toast("Magnet link copied.");
        }), weight());
        listContainer.addView(r1);

        LinearLayout r2 = buttonRow();
        r2.addView(text(I18n.tr("Priority"), 11.5f, AppTheme.textSecondary(), false), new LinearLayout.LayoutParams(-2, dp(34)));
        final int[] ids = new int[]{id};
        r2.addView(actionButton("LOW", bp < 0 ? AppTheme.accent() : AppTheme.textSecondary(), v ->
                action(api -> { api.setBandwidthPriority(ids, -1); return null; }, "Priority: low", afterAction)), weight());
        r2.addView(actionButton("NORMAL", bp == 0 ? AppTheme.accent() : AppTheme.textSecondary(), v ->
                action(api -> { api.setBandwidthPriority(ids, 0); return null; }, "Priority: normal", afterAction)), weight());
        r2.addView(actionButton("HIGH", bp > 0 ? AppTheme.accent() : AppTheme.textSecondary(), v ->
                action(api -> { api.setBandwidthPriority(ids, 1); return null; }, "Priority: high", afterAction)), weight());
        listContainer.addView(r2);
    }

    private View infoLine(String label, String value) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView l = text(I18n.tr(label), 11f, AppTheme.textMuted(), false);
        row.addView(l, new LinearLayout.LayoutParams(dp(110), -2));
        TextView v = text(value, 11.5f, AppTheme.text(), false);
        v.setTextIsSelectable(true);
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1.0f));
        return row;
    }

    private void showMoveDialog(final int id, String currentDir) {
        final LinearLayout body = dialogBody(I18n.tr("Move torrent"));
        final Dialog d = dialog(body);
        final EditText dir = addNum(body, "New location", currentDir, false);
        dir.setInputType(InputType.TYPE_CLASS_TEXT);
        final Switch move = addSwitch(body, "Move existing data", null, true);
        LinearLayout row = buttonRow();
        row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        row.addView(actionButton("MOVE", AppTheme.accent(), v -> {
            final String loc = dir.getText().toString().trim();
            if (loc.isEmpty()) { toast("Enter a folder path."); return; }
            final boolean mv = move.isChecked();
            d.dismiss();
            action(api -> { api.torrentSetLocation(new int[]{id}, loc, mv); return null; }, "Location updated.", afterAction);
        }), weight());
        body.addView(row);
        d.show();
    }

    private void showRemoveDialog(final int id, String name) {
        final LinearLayout body = dialogBody(I18n.tr("Remove torrent"));
        final Dialog d = dialog(body);
        body.addView(text(name, 12.5f, AppTheme.textSecondary(), false));
        LinearLayout r1 = buttonRow();
        r1.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        r1.addView(actionButton("REMOVE", AppTheme.warning(), v -> {
            d.dismiss();
            doRemove(id, false);
        }), weight());
        body.addView(r1);
        LinearLayout r2 = new LinearLayout(act);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setPadding(0, dp(6), 0, 0);
        r2.addView(actionButton("REMOVE + DELETE DATA", AppTheme.danger(), v -> {
            d.dismiss();
            confirm("Permanently delete the downloaded files from disk?", "DELETE", AppTheme.danger(), () -> doRemove(id, true));
        }), new LinearLayout.LayoutParams(-1, dp(34)));
        body.addView(r2);
        d.show();
    }

    private void doRemove(final int id, final boolean deleteData) {
        action(api -> { api.torrentRemove(new int[]{id}, deleteData); return null; },
                deleteData ? "Torrent and data removed." : "Torrent removed.", () -> {
                    detailId = 0;
                    refresh();
                });
    }

    private void showLabelsDialog(final int id, String current) {
        final LinearLayout body = dialogBody(I18n.tr("Labels"));
        final Dialog d = dialog(body);
        final EditText in = addNum(body, "Comma separated labels", "-".equals(current) ? "" : current, false);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout row = buttonRow();
        row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        row.addView(actionButton("SAVE", AppTheme.accent(), v -> {
            String[] parts = in.getText().toString().split(",");
            final List<String> out = new ArrayList<>();
            for (String p : parts) if (!p.trim().isEmpty()) out.add(p.trim());
            d.dismiss();
            action(api -> { api.setLabels(new int[]{id}, out.toArray(new String[0])); return null; }, "Saved.", afterAction);
        }), weight());
        body.addView(row);
        d.show();
    }

    private void showLimitsDialog(JSONObject t) {
        final int id = t.optInt("id");
        final LinearLayout body = dialogBody(I18n.tr("Speed limits (KB/s)"));
        final Dialog d = dialog(body);
        final Switch dl = addSwitch(body, "Limit download", null, t.optBoolean("downloadLimited"));
        final EditText dlv = addNum(body, "Download KB/s", String.valueOf(t.optInt("downloadLimit")), false);
        final Switch ul = addSwitch(body, "Limit upload", null, t.optBoolean("uploadLimited"));
        final EditText ulv = addNum(body, "Upload KB/s", String.valueOf(t.optInt("uploadLimit")), false);
        LinearLayout row = buttonRow();
        row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        row.addView(actionButton("SAVE", AppTheme.accent(), v -> {
            final boolean dlOn = dl.isChecked();
            final boolean ulOn = ul.isChecked();
            final int dlK = parseInt(dlv, 0);
            final int ulK = parseInt(ulv, 0);
            d.dismiss();
            action(api -> { api.setTorrentSpeedLimits(id, dlOn, dlK, ulOn, ulK); return null; }, "Saved.", afterAction);
        }), weight());
        body.addView(row);
        d.show();
    }

    // ================================================================== ADD

    private void renderAdd() {
        listContainer.removeAllViews();
        LinearLayout c = card();
        c.addView(text(I18n.tr("Add torrent"), 13.5f, AppTheme.text(), true));
        TextView h = text(I18n.tr("Paste one or more magnet links, .torrent URLs or info hashes (one per line)."),
                11f, AppTheme.textMuted(), false);
        h.setPadding(0, dp(2), 0, dp(6));
        c.addView(h);

        final EditText links = new EditText(act);
        links.setHint("magnet:?xt=urn:btih:...");
        links.setHintTextColor(AppTheme.textMuted());
        links.setTextColor(AppTheme.text());
        links.setTextSize(12.5f);
        links.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        links.setMinLines(3);
        links.setMaxLines(8);
        links.setGravity(Gravity.TOP | Gravity.START);
        c.addView(links, new LinearLayout.LayoutParams(-1, -2));

        final EditText dir = addNum(c, "Download directory (optional)", "", false);
        dir.setInputType(InputType.TYPE_CLASS_TEXT);
        final Switch paused = addSwitch(c, "Start paused", null, false);

        LinearLayout row = buttonRow();
        row.addView(actionButton("PASTE", AppTheme.accent(), v -> {
            String clip = readClipboard();
            if (clip.isEmpty()) { toast("Clipboard is empty."); return; }
            String cur = links.getText().toString();
            links.setText(cur.isEmpty() ? clip : cur + "\n" + clip);
            links.setSelection(links.getText().length());
        }), weight());
        row.addView(actionButton("ADD", AppTheme.success(), v -> doAdd(links, dir, paused)), weight());
        c.addView(row);
        listContainer.addView(c);

        // Show the daemon's default download dir as a hint (best effort).
        async(api -> api.sessionGet().optString("download-dir", ""), s -> {
            if (!s.isEmpty() && dir.getParent() != null) dir.setHint(I18n.tr("Default") + ": " + s);
        }, e -> {});
    }

    private void doAdd(final EditText links, final EditText dir, final Switch paused) {
        final List<String> items = new ArrayList<>();
        for (String line : links.getText().toString().split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.matches("(?i)[0-9a-f]{40}")) s = "magnet:?xt=urn:btih:" + s;
            items.add(s);
        }
        if (items.isEmpty()) { toast("Paste a magnet link or URL first."); return; }
        final String dd = dir.getText().toString().trim();
        final boolean pz = paused.isChecked();
        async(api -> {
            int added = 0, dup = 0;
            for (String s : items) {
                JSONObject r = api.torrentAdd(s, null, dd, pz);
                if (r.has("torrent-duplicate")) dup++;
                else added++;
            }
            return new int[]{added, dup};
        }, r -> {
            links.setText("");
            toast(r[0] + " " + I18n.tr("added") + (r[1] > 0 ? ", " + r[1] + " " + I18n.tr("already present") : ""));
            if (r[0] > 0) { section = S_TORRENTS; refresh(); }
        }, e -> toast(msg(e)));
    }

    // ================================================================== SPEED

    private void loadSpeed() {
        final int req = ++requestCounter;
        showMessage("Loading...");
        async(api -> new JSONObject[]{api.sessionGet(), api.sessionStats()}, r -> {
            if (req != requestCounter || section != S_SPEED || detailId > 0) return;
            renderSpeed(r[0], r[1]);
        }, e -> { if (req == requestCounter) showError(e); });
    }

    private void tickSpeed() {
        if (liveDown == null || polling) return;
        final int req = requestCounter;
        polling = true;
        async(api -> api.sessionStats(), st -> {
            polling = false;
            if (req != requestCounter || section != S_SPEED || liveDown == null) return;
            liveDown.setText(fmtSpeed(st.optLong("downloadSpeed")));
            liveUp.setText(fmtSpeed(st.optLong("uploadSpeed")));
        }, e -> polling = false);
    }

    private void renderSpeed(final JSONObject s, JSONObject stats) {
        listContainer.removeAllViews();
        final List<Collect> cols = new ArrayList<>();

        LinearLayout live = card();
        LinearLayout lrow = new LinearLayout(act);
        lrow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout c1 = new LinearLayout(act);
        c1.setOrientation(LinearLayout.VERTICAL);
        c1.addView(text(I18n.tr("Download"), 11f, AppTheme.textMuted(), false));
        liveDown = text(fmtSpeed(stats.optLong("downloadSpeed")), 20f, AppTheme.accent(), true);
        c1.addView(liveDown);
        LinearLayout c2 = new LinearLayout(act);
        c2.setOrientation(LinearLayout.VERTICAL);
        c2.addView(text(I18n.tr("Upload"), 11f, AppTheme.textMuted(), false));
        liveUp = text(fmtSpeed(stats.optLong("uploadSpeed")), 20f, AppTheme.success(), true);
        c2.addView(liveUp);
        lrow.addView(c1, new LinearLayout.LayoutParams(0, -2, 1.0f));
        lrow.addView(c2, new LinearLayout.LayoutParams(0, -2, 1.0f));
        live.addView(lrow);
        TextView cnt = text(stats.optInt("activeTorrentCount") + " " + I18n.tr("active") + "  -  "
                + stats.optInt("pausedTorrentCount") + " " + I18n.tr("paused") + "  -  "
                + stats.optInt("torrentCount") + " " + I18n.tr("total"), 11f, AppTheme.textSecondary(), false);
        cnt.setPadding(0, dp(6), 0, 0);
        live.addView(cnt);
        listContainer.addView(live);

        // alt-speed (turtle): applied immediately
        LinearLayout turtle = card();
        final Switch alt = addSwitch(turtle, "Alternative speed limits (turtle)",
                "Swap to the alternative limits below right now.", s.optBoolean("alt-speed-enabled"));
        alt.setOnCheckedChangeListener((b, checked) -> action(api -> {
            JSONObject a = new JSONObject();
            a.put("alt-speed-enabled", checked);
            api.sessionSet(a);
            return null;
        }, checked ? "Turtle mode on." : "Turtle mode off.", null));
        final EditText altDown = addNum(turtle, "Alt download limit (KB/s)", String.valueOf(s.optInt("alt-speed-down", 50)), false);
        final EditText altUp = addNum(turtle, "Alt upload limit (KB/s)", String.valueOf(s.optInt("alt-speed-up", 50)), false);
        cols.add(o -> { o.put("alt-speed-down", parseInt(altDown, 50)); o.put("alt-speed-up", parseInt(altUp, 50)); });
        listContainer.addView(turtle);

        // global limits
        LinearLayout lim = card();
        lim.addView(text(I18n.tr("Global speed limits"), 13.5f, AppTheme.text(), true));
        final Switch dOn = addSwitch(lim, "Limit download speed", null, s.optBoolean("speed-limit-down-enabled"));
        final EditText dv = addNum(lim, "Download limit (KB/s)", String.valueOf(s.optInt("speed-limit-down", 100)), false);
        final Switch uOn = addSwitch(lim, "Limit upload speed", null, s.optBoolean("speed-limit-up-enabled"));
        final EditText uv = addNum(lim, "Upload limit (KB/s)", String.valueOf(s.optInt("speed-limit-up", 100)), false);
        cols.add(o -> {
            o.put("speed-limit-down-enabled", dOn.isChecked());
            o.put("speed-limit-down", parseInt(dv, 100));
            o.put("speed-limit-up-enabled", uOn.isChecked());
            o.put("speed-limit-up", parseInt(uv, 100));
        });
        listContainer.addView(lim);

        listContainer.addView(actionButton("SAVE", AppTheme.accent(), v -> saveSession(cols, "Speed limits saved.")),
                new LinearLayout.LayoutParams(-1, dp(40)));

        // statistics
        listContainer.addView(statsCard("This session", stats.optJSONObject("current-stats")));
        listContainer.addView(statsCard("All time", stats.optJSONObject("cumulative-stats")));
    }

    private View statsCard(String title, JSONObject cs) {
        LinearLayout c = card();
        c.addView(text(I18n.tr(title), 13.5f, AppTheme.text(), true));
        if (cs == null) {
            c.addView(infoLine("Statistics", "-"));
            return c;
        }
        c.addView(infoLine("Downloaded", fmtSize(cs.optLong("downloadedBytes"))));
        c.addView(infoLine("Uploaded", fmtSize(cs.optLong("uploadedBytes"))));
        long dl = cs.optLong("downloadedBytes");
        c.addView(infoLine("Ratio", dl > 0 ? fmtRatio((double) cs.optLong("uploadedBytes") / (double) dl) : "-"));
        c.addView(infoLine("Files added", String.valueOf(cs.optLong("filesAdded"))));
        c.addView(infoLine("Active time", fmtDuration(cs.optLong("secondsActive"))));
        if (cs.has("sessionCount")) c.addView(infoLine("Sessions", String.valueOf(cs.optLong("sessionCount"))));
        return c;
    }

    private void saveSession(List<Collect> cols, final String okMsg) {
        final JSONObject args = new JSONObject();
        try {
            for (Collect c : cols) c.put(args);
        } catch (Exception e) {
            toast(msg(e));
            return;
        }
        action(api -> { api.sessionSet(args); return null; }, okMsg, this::refresh);
    }

    // ================================================================== SETTINGS

    private void loadSettings() {
        final int req = ++requestCounter;
        showMessage("Loading...");
        async(api -> {
            JSONObject s = api.sessionGet();
            try {
                s.put("_free", api.freeSpace(s.optString("download-dir", "")));
            } catch (Throwable ignored) {}
            return s;
        }, s -> {
            if (req != requestCounter || section != S_SETTINGS || detailId > 0) return;
            renderSettings(s);
        }, e -> { if (req == requestCounter) showError(e); });
    }

    private void renderSettings(final JSONObject s) {
        listContainer.removeAllViews();
        final List<Collect> cols = new ArrayList<>();

        LinearLayout info = card();
        info.addView(text("Transmission " + s.optString("version", ""), 13.5f, AppTheme.text(), true));
        long free = s.optLong("_free", -1);
        TextView iv = text("RPC " + s.optInt("rpc-version") + (free >= 0 ? "   " + I18n.tr("Free space") + " " + fmtSize(free) : ""),
                11f, AppTheme.textSecondary(), false);
        iv.setPadding(0, dp(2), 0, 0);
        info.addView(iv);
        listContainer.addView(info);

        LinearLayout dirs = card();
        dirs.addView(text(I18n.tr("Folders"), 13.5f, AppTheme.text(), true));
        final EditText dd = addNum(dirs, "Download directory", s.optString("download-dir", ""), false);
        dd.setInputType(InputType.TYPE_CLASS_TEXT);
        final Switch idOn = addSwitch(dirs, "Use incomplete directory", null, s.optBoolean("incomplete-dir-enabled"));
        final EditText id = addNum(dirs, "Incomplete directory", s.optString("incomplete-dir", ""), false);
        id.setInputType(InputType.TYPE_CLASS_TEXT);
        final Switch startAdded = addSwitch(dirs, "Start added torrents", null, s.optBoolean("start-added-torrents", true));
        cols.add(o -> {
            String a = dd.getText().toString().trim();
            if (!a.isEmpty()) o.put("download-dir", a);
            o.put("incomplete-dir-enabled", idOn.isChecked());
            String b = id.getText().toString().trim();
            if (!b.isEmpty()) o.put("incomplete-dir", b);
            o.put("start-added-torrents", startAdded.isChecked());
        });
        listContainer.addView(dirs);

        LinearLayout seed = card();
        seed.addView(text(I18n.tr("Seeding"), 13.5f, AppTheme.text(), true));
        final Switch ratioOn = addSwitch(seed, "Stop seeding at ratio", null,
                s.has("seedRatioLimited") ? s.optBoolean("seedRatioLimited") : s.optBoolean("seed-ratio-limited"));
        double rl = s.has("seedRatioLimit") ? s.optDouble("seedRatioLimit", 2.0) : s.optDouble("seed-ratio-limit", 2.0);
        final EditText ratio = addNum(seed, "Ratio limit", fmtRatioPlain(rl), true);
        cols.add(o -> {
            double r = parseDouble(ratio, 2.0);
            o.put("seedRatioLimited", ratioOn.isChecked());
            o.put("seedRatioLimit", r);
            o.put("seed-ratio-limited", ratioOn.isChecked());
            o.put("seed-ratio-limit", r);
        });
        listContainer.addView(seed);

        LinearLayout peers = card();
        peers.addView(text(I18n.tr("Peers and queues"), 13.5f, AppTheme.text(), true));
        final EditText pg = addNum(peers, "Global peer limit", String.valueOf(s.optInt("peer-limit-global", 200)), false);
        final EditText pt = addNum(peers, "Peer limit per torrent", String.valueOf(s.optInt("peer-limit-per-torrent", 50)), false);
        final Switch dqOn = addSwitch(peers, "Limit active downloads", null, s.optBoolean("download-queue-enabled", true));
        final EditText dq = addNum(peers, "Max active downloads", String.valueOf(s.optInt("download-queue-size", 5)), false);
        final Switch sqOn = addSwitch(peers, "Limit active seeds", null, s.optBoolean("seed-queue-enabled"));
        final EditText sq = addNum(peers, "Max active seeds", String.valueOf(s.optInt("seed-queue-size", 10)), false);
        cols.add(o -> {
            o.put("peer-limit-global", parseInt(pg, 200));
            o.put("peer-limit-per-torrent", parseInt(pt, 50));
            o.put("download-queue-enabled", dqOn.isChecked());
            o.put("download-queue-size", parseInt(dq, 5));
            o.put("seed-queue-enabled", sqOn.isChecked());
            o.put("seed-queue-size", parseInt(sq, 10));
        });
        listContainer.addView(peers);

        LinearLayout net = card();
        net.addView(text(I18n.tr("Network"), 13.5f, AppTheme.text(), true));
        final Switch dht = addSwitch(net, "DHT", "Distributed hash table peer discovery.", s.optBoolean("dht-enabled", true));
        final Switch pex = addSwitch(net, "PEX", "Peer exchange.", s.optBoolean("pex-enabled", true));
        final Switch lpd = addSwitch(net, "LPD", "Local peer discovery.", s.optBoolean("lpd-enabled"));
        cols.add(o -> {
            o.put("dht-enabled", dht.isChecked());
            o.put("pex-enabled", pex.isChecked());
            o.put("lpd-enabled", lpd.isChecked());
        });
        net.addView(text(I18n.tr("Encryption"), 11.5f, AppTheme.textSecondary(), false));
        final String[] enc = new String[]{s.optString("encryption", "preferred")};
        final LinearLayout encRow = new LinearLayout(act);
        encRow.setOrientation(LinearLayout.HORIZONTAL);
        encRow.setPadding(0, dp(6), 0, dp(4));
        drawEncryption(encRow, enc);
        net.addView(encRow);
        cols.add(o -> o.put("encryption", enc[0]));
        listContainer.addView(net);

        LinearLayout bl = card();
        bl.addView(text(I18n.tr("Blocklist"), 13.5f, AppTheme.text(), true));
        final Switch blOn = addSwitch(bl, "Enable blocklist", null, s.optBoolean("blocklist-enabled"));
        final EditText blUrl = addNum(bl, "Blocklist URL", s.optString("blocklist-url", ""), false);
        blUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        cols.add(o -> {
            o.put("blocklist-enabled", blOn.isChecked());
            String u = blUrl.getText().toString().trim();
            if (!u.isEmpty()) o.put("blocklist-url", u);
        });
        TextView bs = text(I18n.tr("Rules loaded") + ": " + s.optInt("blocklist-size"), 11f, AppTheme.textSecondary(), false);
        bs.setPadding(0, dp(4), 0, 0);
        bl.addView(bs);
        LinearLayout blRow = buttonRow();
        blRow.addView(actionButton("UPDATE BLOCKLIST", AppTheme.accent(), v -> {
            toast("Updating blocklist...");
            async(api -> api.blocklistUpdate(),
                    n -> toast(I18n.tr("Blocklist updated") + ": " + n + " " + I18n.tr("rules")),
                    e -> toast(msg(e)));
        }), weight());
        blRow.addView(actionButton("PORT TEST", AppTheme.accent(), v -> {
            toast("Testing peer port...");
            async(api -> api.portTest(),
                    open -> toast(open ? "Peer port is open." : "Peer port is closed."),
                    e -> toast(msg(e)));
        }), weight());
        bl.addView(blRow);
        listContainer.addView(bl);

        Button save = actionButton("SAVE", AppTheme.accent(), v -> saveSession(cols, "Settings saved."));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(40));
        slp.topMargin = dp(4);
        listContainer.addView(save, slp);

        TextView note = text(I18n.tr("Fishbowl may re-apply some of these (for example the seeding ratio) when Transmission is restarted."),
                10.5f, AppTheme.textMuted(), false);
        note.setPadding(0, dp(10), 0, 0);
        listContainer.addView(note);
    }

    private void drawEncryption(final LinearLayout holder, final String[] cur) {
        holder.removeAllViews();
        final String[] values = {"required", "preferred", "tolerated"};
        final String[] labels = {"Required", "Preferred", "Allowed"};
        for (int i = 0; i < values.length; i++) {
            final String val = values[i];
            TextView p = pill(labels[i], val.equals(cur[0]));
            p.setOnClickListener(v -> { cur[0] = val; drawEncryption(holder, cur); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.setMarginEnd(dp(6));
            holder.addView(p, lp);
        }
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
        ScrollView wrap = new ScrollView(act);
        wrap.addView(content, new ViewGroup.LayoutParams(-1, -2));
        AlertDialog d = new AlertDialog.Builder(act).setView(wrap).create();
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
        body.addView(text(I18n.tr(message), 12.5f, AppTheme.textSecondary(), false));
        final Dialog d = dialog(body);
        LinearLayout row = buttonRow();
        row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        row.addView(actionButton(positive, color, v -> { d.dismiss(); onYes.run(); }), weight());
        body.addView(row);
        d.show();
    }

    private void choose(String title, String[] items, int selected, final IntDone cb) {
        final LinearLayout body = dialogBody(I18n.tr(title));
        final Dialog d = dialog(body);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            Button b = actionButton(items[i], i == selected ? AppTheme.accent() : AppTheme.textSecondary(),
                    v -> { d.dismiss(); cb.run(idx); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(36));
            lp.topMargin = dp(6);
            body.addView(b, lp);
        }
        d.show();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    /** Adds a labelled single-line EditText to parent and returns it. */
    private EditText addNum(LinearLayout parent, String label, String value, boolean decimal) {
        TextView l = text(I18n.tr(label), 11f, AppTheme.textMuted(), false);
        l.setPadding(0, dp(8), 0, 0);
        parent.addView(l);
        EditText e = new EditText(act);
        e.setTextColor(AppTheme.text());
        e.setHintTextColor(AppTheme.textMuted());
        e.setTextSize(13f);
        e.setSingleLine(true);
        e.setInputType(decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    /** Adds a label (+ optional hint) with a themed Switch on the right and returns the Switch. */
    private Switch addSwitch(LinearLayout parent, String label, String hint, boolean checked) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        LinearLayout tx = new LinearLayout(act);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(I18n.tr(label), 12.5f, AppTheme.text(), false));
        if (hint != null) {
            TextView h = text(I18n.tr(hint), 10.5f, AppTheme.textMuted(), false);
            h.setPadding(0, dp(2), dp(8), 0);
            tx.addView(h);
        }
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch sw = new Switch(act);
        sw.setChecked(checked);
        UiStyle.styleSwitch(act, sw);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private static int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }

    private static double parseDouble(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
            return cs == null ? "" : cs.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void copyToClipboard(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("magnet", s));
        } catch (Throwable ignored) {}
    }

    private void showMessage(String m) {
        listContainer.removeAllViews();
        rowsBox = null;
        TextView t = text(I18n.tr(m), 12f, AppTheme.textSecondary(), false);
        t.setPadding(0, dp(16), 0, 0);
        listContainer.addView(t);
    }

    private void showError(Throwable err) {
        listContainer.removeAllViews();
        rowsBox = null;
        errorShown = true;
        summary.setText("");
        TextView t = text(I18n.tr(msg(err)), 12f, AppTheme.danger(), false);
        t.setPadding(0, dp(16), 0, dp(8));
        listContainer.addView(t);
        listContainer.addView(actionButton("RETRY", AppTheme.accent(), v -> refresh()), new LinearLayout.LayoutParams(-1, dp(40)));
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
        lp.setMarginEnd(dp(6));
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

    // ================================================================== formatting

    private static String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        double gb = bytes / 1073741824.0;
        if (gb >= 1) return String.format(Locale.US, "%.2f GB", gb);
        double mb = bytes / 1048576.0;
        if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb);
        double kb = bytes / 1024.0;
        if (kb >= 1) return String.format(Locale.US, "%.0f KB", kb);
        return bytes + " B";
    }

    private static String fmtSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        return fmtSize(bytesPerSec) + "/s";
    }

    private static String fmtPct(double frac) {
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        return String.format(Locale.US, "%.1f%%", frac * 100.0);
    }

    private static String fmtRatio(double r) {
        if (r < 0) return "-";
        return String.format(Locale.US, "%.2f", r);
    }

    private static String fmtRatioPlain(double r) {
        return String.format(Locale.US, "%.2f", r);
    }

    private static String fmtEta(long secs) {
        if (secs < 0) return "--";
        return fmtDuration(secs);
    }

    private static String fmtDuration(long secs) {
        if (secs < 60) return secs + "s";
        long m = secs / 60;
        if (m < 60) return m + "m " + (secs % 60) + "s";
        long h = m / 60;
        if (h < 48) return h + "h " + (m % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    private static String fmtDate(long epochSec) {
        if (epochSec <= 0) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(epochSec * 1000L));
    }
}
