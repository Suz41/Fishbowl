package com.fishbowl.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fishbowl.app.services.AutomationPrefs;
import com.fishbowl.app.services.SonarrMissingSearch;

import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Sonarr" tab: list the monitored missing episodes (with a per-episode SEARCH button) and the
 * automatic missing-search settings that replace the old Tasker/Termux "missing-fix.sh" job.
 */
public final class SonarrTabView {

    private static final int MODE_AUTO = 0;
    private static final int MODE_MISSING = 1;
    private static final int PAGE_SIZE = 50;

    private final Activity act;
    private final LinearLayout root;
    private final LinearLayout controls;
    private final LinearLayout listContainer;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private int mode = MODE_AUTO;
    private int page = 1;
    private int requestCounter = 0;

    public SonarrTabView(Activity activity) {
        this.act = activity;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppTheme.bg());

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(4));
        TextView title = text("SONARR", 14f, "#F4F4F5", true);
        title.setLetterSpacing(0.14f);
        header.addView(title);
        TextView sub = text("Missing episodes - search them automatically", 11f, "#71717A", false);
        sub.setPadding(0, dp(2), 0, dp(8));
        header.addView(sub);
        root.addView(header);

        controls = new LinearLayout(act);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), 0, dp(16), dp(6));
        root.addView(controls);

        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(false);
        listContainer = new LinearLayout(act);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(listContainer, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
    }

    public View getView() { return root; }

    /** Returns true when the back press was consumed. */
    public boolean onBack() {
        if (mode == MODE_MISSING && page > 1) {
            page--;
            refresh();
            return true;
        }
        return false;
    }

    public void refresh() {
        renderControls();
        if (mode == MODE_MISSING) loadMissing();
        else renderAuto();
    }

    private void renderControls() {
        controls.removeAllViews();
        LinearLayout modes = new LinearLayout(act);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.addView(pill("AUTO-SEARCH", mode == MODE_AUTO, v -> { mode = MODE_AUTO; refresh(); }), weight());
        modes.addView(pill("MISSING", mode == MODE_MISSING, v -> { mode = MODE_MISSING; page = 1; refresh(); }), weight());
        controls.addView(modes);
    }

    // ------------------------------------------------------------------ auto-search settings

    private void renderAuto() {
        requestCounter++;
        listContainer.removeAllViews();

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tx = new LinearLayout(act);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text("Search missing episodes", 13.5f, "#F4F4F5", true));
        TextView h = text("Ask Sonarr to search every monitored missing episode on a fixed interval.", 11f, "#71717A", false);
        h.setPadding(0, dp(2), dp(8), 0);
        tx.addView(h);
        top.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch sw = new Switch(act);
        sw.setChecked(AutomationPrefs.isSonarrMissingEnabled(act));
        sw.setOnCheckedChangeListener((v, checked) -> {
            AutomationPrefs.setSonarrMissingEnabled(act, checked);
            Toast.makeText(act, I18n.tr(checked ? "Auto-search enabled." : "Auto-search disabled."), Toast.LENGTH_SHORT).show();
        });
        UiStyle.styleSwitch(act, sw);
        top.addView(sw);
        card.addView(top);

        LinearLayout period = new LinearLayout(act);
        period.setOrientation(LinearLayout.HORIZONTAL);
        period.setGravity(Gravity.CENTER_VERTICAL);
        period.setPadding(0, dp(8), 0, 0);
        period.addView(text("Search every (minutes)", 12.5f, "#F4F4F5", false), new LinearLayout.LayoutParams(0, -2, 1.0f));
        final EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(AutomationPrefs.getSonarrMissingMinutes(act)));
        input.setTextColor(AppTheme.text());
        input.setTextSize(13f);
        input.setGravity(Gravity.CENTER);
        input.setSingleLine(true);
        period.addView(input, new LinearLayout.LayoutParams(dp(80), -2));
        Button save = actionButton("SAVE", "#38BDF8", null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, dp(34));
        slp.setMarginStart(dp(8));
        period.addView(save, slp);
        final Runnable dirty = UiStyle.bindDirty(save, input,
                () -> String.valueOf(AutomationPrefs.getSonarrMissingMinutes(act)));
        save.setOnClickListener(v -> {
            int val = AutomationPrefs.DEFAULT_SONARR_MINUTES;
            try { val = Integer.parseInt(input.getText().toString().trim()); } catch (Throwable ignored) {}
            AutomationPrefs.setSonarrMissingMinutes(act, val);
            input.setText(String.valueOf(AutomationPrefs.getSonarrMissingMinutes(act)));
            dirty.run();
            Toast.makeText(act, I18n.tr("Saved: every " + AutomationPrefs.getSonarrMissingMinutes(act) + " min"), Toast.LENGTH_SHORT).show();
        });
        card.addView(period);
        listContainer.addView(card);

        final TextView status = text("", 11f, "#A1A1AA", false);
        status.setPadding(0, dp(10), 0, dp(6));
        long last = AutomationPrefs.getSonarrMissingLastRun(act);
        status.setText(last > 0
                ? "Last run: " + new Date(last) + "\n" + AutomationPrefs.getSonarrMissingLastSummary(act)
                : "Not run yet. Checked every 5 minutes while Sonarr is running.");
        listContainer.addView(status);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(actionButton("PREVIEW", "#38BDF8", v -> runAuto(true, status)), weight());
        row.addView(actionButton("SEARCH NOW", "#34D399", v -> runAuto(false, status)), weight());
        listContainer.addView(row);

        TextView note = text("Only monitored episodes are searched. Sonarr downloads whatever its indexers find, "
                + "so keep Prowlarr and Transmission running. Very short intervals can get you rate-limited by indexers.",
                10.5f, "#71717A", false);
        note.setPadding(0, dp(12), 0, 0);
        listContainer.addView(note);
    }

    private void runAuto(final boolean dryRun, final TextView status) {
        status.setText(dryRun ? "Checking..." : "Searching...");
        bg.execute(() -> {
            final String out = SonarrMissingSearch.run(act, dryRun);
            act.runOnUiThread(() -> {
                status.setText(out);
                if (!dryRun) refresh();
            });
        });
    }

    // ------------------------------------------------------------------ missing list

    private void loadMissing() {
        final int req = ++requestCounter;
        final int pg = page;
        showMessage("Loading...");
        bg.execute(() -> {
            try {
                final SonarrMissingSearch.Page result = SonarrMissingSearch.fetchMissing(act, pg, PAGE_SIZE);
                act.runOnUiThread(() -> {
                    if (req != requestCounter) return;
                    listContainer.removeAllViews();
                    if (result.items.isEmpty()) {
                        showMessage("No monitored missing episodes.");
                        return;
                    }
                    int from = (pg - 1) * PAGE_SIZE + 1;
                    TextView count = text(String.format(Locale.US, "%d-%d of %d missing",
                            from, from + result.items.size() - 1, result.totalRecords), 11f, "#A1A1AA", false);
                    count.setPadding(0, dp(6), 0, 0);
                    listContainer.addView(count);
                    for (SonarrMissingSearch.MissingEpisode e : result.items) addRow(e);
                    addPager(pg, result.totalRecords);
                });
            } catch (Throwable t) {
                act.runOnUiThread(() -> { if (req == requestCounter) showError(t); });
            }
        });
    }

    private void addPager(int pg, int total) {
        LinearLayout pager = new LinearLayout(act);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(10);
        pager.setLayoutParams(plp);
        if (pg > 1) pager.addView(actionButton("PREV", "#38BDF8", v -> { page--; refresh(); }), weight());
        if (pg * PAGE_SIZE < total) pager.addView(actionButton("NEXT", "#38BDF8", v -> { page++; refresh(); }), weight());
        if (pager.getChildCount() > 0) listContainer.addView(pager);
    }

    private void addRow(final SonarrMissingSearch.MissingEpisode e) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout tx = new LinearLayout(act);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(e.seriesTitle.isEmpty() ? "Unknown series" : e.seriesTitle, 12.5f, "#F4F4F5", true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(t);
        String sub = String.format(Locale.US, "S%02dE%02d", e.season, e.episode)
                + (e.title.isEmpty() ? "" : " - " + e.title)
                + (e.airDate.isEmpty() ? "" : "  (" + e.airDate + ")");
        TextView s = text(sub, 10.5f, "#71717A", false);
        s.setPadding(0, dp(2), 0, 0);
        tx.addView(s);
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView go = text("SEARCH", 10f, "#34D399", true);
        go.setGravity(Gravity.CENTER);
        go.setPadding(dp(8), dp(5), dp(8), dp(5));
        go.setBackground(rounded(AppTheme.successContainer(), AppTheme.success(), 4));
        go.setClickable(true);
        go.setOnClickListener(v -> {
            Toast.makeText(act, I18n.tr("Searching..."), Toast.LENGTH_SHORT).show();
            bg.execute(() -> {
                final boolean ok = SonarrMissingSearch.searchEpisode(act, e.episodeId);
                act.runOnUiThread(() -> Toast.makeText(act,
                        I18n.tr(ok ? "Search queued." : "Could not queue the search."), Toast.LENGTH_SHORT).show());
            });
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.setMarginStart(dp(8));
        row.addView(go, glp);

        listContainer.addView(row);
    }

    // ------------------------------------------------------------------ small UI helpers

    private void showMessage(String msg) {
        listContainer.removeAllViews();
        TextView t = text(msg, 12f, "#A1A1AA", false);
        t.setPadding(0, dp(16), 0, 0);
        listContainer.addView(t);
    }

    private void showError(Throwable err) {
        listContainer.removeAllViews();
        String msg = err == null ? null : err.getMessage();
        TextView t = text(msg == null ? "Unknown error (is Sonarr running?)" : msg, 12f, "#F87171", false);
        t.setPadding(0, dp(16), 0, dp(8));
        listContainer.addView(t);
        listContainer.addView(actionButton("RETRY", "#38BDF8", v -> refresh()), new LinearLayout.LayoutParams(-1, dp(40)));
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
        lp.setMarginEnd(dp(6));
        return lp;
    }

    private TextView pill(String label, boolean selected, View.OnClickListener l) {
        TextView t = text(label, 9.5f, selected ? "#000000" : "#A1A1AA", true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(rounded(selected ? AppTheme.accent() : AppTheme.card(),
                AppTheme.outline(), 8));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private Button actionButton(String label, String colorHex, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(10.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int c = col(colorHex);
        b.setTextColor(c);
        b.setBackground(rounded(Color.argb(25, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(80, Color.red(c), Color.green(c), Color.blue(c)), 6));
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    /** Maps the dark-theme hex literals used by callers to the active theme's roles. */
    private static int col(String hex) {
        switch (hex.toUpperCase(Locale.ROOT)) {
            case "#38BDF8": return AppTheme.accent();
            case "#F4F4F5": case "#FFFFFF": return AppTheme.text();
            case "#A1A1AA": return AppTheme.textSecondary();
            case "#71717A": return AppTheme.textMuted();
            case "#F87171": case "#EF4444": return AppTheme.danger();
            case "#34D399": return AppTheme.success();
            case "#000000": return AppTheme.onAccent();
            default: return Color.parseColor(hex);
        }
    }

    private TextView text(String s, float size, String colorHex, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(col(colorHex));
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
}
