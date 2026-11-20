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

import com.fishbowl.app.services.JellyfinLibraryClient;
import com.fishbowl.app.services.LibraryAutoDelete;
import com.fishbowl.app.services.LibraryPrefs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Library" tab: browse Jellyfin movies / series / seasons / episodes with delete buttons,
 * a "recently watched" list (latest first) and the auto-delete-after-watching settings.
 */
public final class LibraryTabView {

    private static final int MODE_BROWSE = 0;
    private static final int MODE_RECENT = 1;
    private static final int MODE_AUTO = 2;

    private final Activity act;
    private final LinearLayout root;
    private final LinearLayout controls;
    private final LinearLayout listContainer;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private int mode = MODE_BROWSE;
    private boolean browseSeries = false;
    private int level = 0; // 0 = top list, 1 = seasons, 2 = episodes
    private String seriesId, seriesName, seasonId, seasonName;
    private int requestCounter = 0;

    public LibraryTabView(Activity activity) {
        this.act = activity;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(4));
        TextView title = text("LIBRARY", 14f, "#FFFFFF", true);
        title.setLetterSpacing(0.14f);
        header.addView(title);
        TextView sub = text("Jellyfin movies & series - delete what you have watched", 11f, "#71717A", false);
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

    /** Returns true when the back press was consumed (navigated up inside the tab). */
    public boolean onBack() {
        if (mode == MODE_BROWSE && level > 0) {
            level--;
            refresh();
            return true;
        }
        return false;
    }

    public void refresh() {
        renderControls();
        if (mode == MODE_BROWSE) loadBrowse();
        else if (mode == MODE_RECENT) loadRecent();
        else renderAutoDelete();
    }

    // ------------------------------------------------------------------ controls

    private void renderControls() {
        controls.removeAllViews();

        LinearLayout modes = new LinearLayout(act);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.addView(pill("BROWSE", mode == MODE_BROWSE, v -> { mode = MODE_BROWSE; refresh(); }), weight());
        modes.addView(pill("RECENTLY WATCHED", mode == MODE_RECENT, v -> { mode = MODE_RECENT; refresh(); }), weight());
        modes.addView(pill("AUTO-DELETE", mode == MODE_AUTO, v -> { mode = MODE_AUTO; refresh(); }), weight());
        controls.addView(modes);

        if (mode == MODE_BROWSE) {
            LinearLayout kinds = new LinearLayout(act);
            kinds.setOrientation(LinearLayout.HORIZONTAL);
            kinds.setPadding(0, dp(6), 0, 0);
            kinds.addView(pill("MOVIES", !browseSeries, v -> { browseSeries = false; level = 0; refresh(); }), weight());
            kinds.addView(pill("SERIES", browseSeries, v -> { browseSeries = true; level = 0; refresh(); }), weight());
            controls.addView(kinds);

            if (browseSeries && level > 0) {
                String crumb = "<  " + seriesName + (level == 2 && seasonName != null ? "  /  " + seasonName : "");
                TextView back = text(crumb, 12f, "#38BDF8", true);
                back.setPadding(0, dp(10), 0, dp(2));
                back.setSingleLine(true);
                back.setEllipsize(TextUtils.TruncateAt.END);
                back.setOnClickListener(v -> onBack());
                controls.addView(back);
            }
        }
    }

    // ------------------------------------------------------------------ browse

    private void loadBrowse() {
        final int req = ++requestCounter;
        showMessage("Loading...");
        bg.execute(() -> {
            try {
                final List<JellyfinLibraryClient.LibItem> items;
                if (!browseSeries) {
                    items = JellyfinLibraryClient.movies(act);
                } else if (level == 0) {
                    items = JellyfinLibraryClient.series(act);
                } else if (level == 1) {
                    items = JellyfinLibraryClient.seasons(act, seriesId);
                } else {
                    items = JellyfinLibraryClient.episodes(act, seriesId, seasonId);
                }
                act.runOnUiThread(() -> {
                    if (req != requestCounter) return;
                    listContainer.removeAllViews();
                    if (items.isEmpty()) {
                        showMessage("Nothing here.");
                        return;
                    }
                    for (JellyfinLibraryClient.LibItem it : items) addBrowseRow(it);
                });
            } catch (Throwable t) {
                act.runOnUiThread(() -> { if (req == requestCounter) showError(t); });
            }
        });
    }

    private void addBrowseRow(final JellyfinLibraryClient.LibItem it) {
        String subtitle;
        View.OnClickListener open = null;
        if ("Series".equals(it.type)) {
            subtitle = (it.childCount > 0 ? it.childCount + " season(s)" : "Series") + (it.year > 0 ? " - " + it.year : "");
            open = v -> { seriesId = it.id; seriesName = it.name; level = 1; refresh(); };
        } else if ("Season".equals(it.type)) {
            subtitle = "Season" + (it.childCount > 0 ? " - " + it.childCount + " episode(s)" : "");
            open = v -> { seasonId = it.id; seasonName = it.name; level = 2; refresh(); };
        } else if ("Episode".equals(it.type)) {
            subtitle = (it.played ? "Watched" : "Unwatched");
        } else {
            subtitle = (it.played ? "Watched" : "Unwatched");
        }
        String title = "Episode".equals(it.type)
                ? (it.episodeNumber >= 0 ? it.episodeNumber + ". " : "") + it.name
                : it.displayTitle();
        addRow(title, subtitle, open, it);
    }

    // ------------------------------------------------------------------ recently watched

    private void loadRecent() {
        final int req = ++requestCounter;
        showMessage("Loading...");
        bg.execute(() -> {
            try {
                final List<JellyfinLibraryClient.LibItem> items = JellyfinLibraryClient.recentlyWatched(act, 100);
                act.runOnUiThread(() -> {
                    if (req != requestCounter) return;
                    listContainer.removeAllViews();
                    if (items.isEmpty()) {
                        showMessage("Nothing has been watched yet.");
                        return;
                    }
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                    for (JellyfinLibraryClient.LibItem it : items) {
                        String by = it.playedBy != null ? "  -  " + it.playedBy : "";
                        addRow(it.displayTitle(), "Watched " + fmt.format(new Date(it.lastPlayedMs)) + by, null, it);
                    }
                });
            } catch (Throwable t) {
                act.runOnUiThread(() -> { if (req == requestCounter) showError(t); });
            }
        });
    }

    // ------------------------------------------------------------------ auto-delete settings

    private void renderAutoDelete() {
        requestCounter++;
        listContainer.removeAllViews();

        listContainer.addView(autoSection("Movies", "Delete a movie this many hours after it was last watched.",
                LibraryPrefs.isMoviesEnabled(act), LibraryPrefs.getMoviesHours(act),
                (on) -> LibraryPrefs.setMoviesEnabled(act, on),
                (h) -> LibraryPrefs.setMoviesHours(act, h),
                () -> LibraryPrefs.getMoviesHours(act)));

        listContainer.addView(autoSection("Series (episodes)", "Delete an episode this many hours after it was last watched.",
                LibraryPrefs.isSeriesEnabled(act), LibraryPrefs.getSeriesHours(act),
                (on) -> LibraryPrefs.setSeriesEnabled(act, on),
                (h) -> LibraryPrefs.setSeriesHours(act, h),
                () -> LibraryPrefs.getSeriesHours(act)));

        final TextView status = text("", 11f, "#A1A1AA", false);
        status.setPadding(0, dp(10), 0, dp(6));
        long last = LibraryPrefs.getLastRun(act);
        status.setText(last > 0
                ? "Last run: " + new Date(last) + "\n" + LibraryPrefs.getLastSummary(act)
                : "Checks every 30 minutes while Jellyfin is running. Not run yet.");
        listContainer.addView(status);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(actionButton("PREVIEW", "#38BDF8", v -> runAuto(true, status)), weight());
        row.addView(actionButton("RUN NOW", "#F87171", v -> runAuto(false, status)), weight());
        listContainer.addView(row);

        TextView note = text("Time is counted from the most recent watch by ANY Jellyfin user, so an item another user has not seen yet can still be deleted. "
                + "Deletion removes the file from disk. Radarr/Sonarr may download it again unless it is unmonitored there.",
                10.5f, "#71717A", false);
        note.setPadding(0, dp(12), 0, 0);
        listContainer.addView(note);
    }

    private interface BoolSetter { void set(boolean v); }
    private interface IntSetter { void set(int v); }
    private interface IntGetter { int get(); }

    private View autoSection(String name, String help, boolean on, int hours,
                             final BoolSetter setOn, final IntSetter setHours, final IntGetter getHours) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(rounded(Color.parseColor("#0C0C0E"), Color.argb(20, 255, 255, 255), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tx = new LinearLayout(act);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(name, 13.5f, "#F4F4F5", true));
        TextView h = text(help, 11f, "#71717A", false);
        h.setPadding(0, dp(2), dp(8), 0);
        tx.addView(h);
        top.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch sw = new Switch(act);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((v, checked) -> {
            setOn.set(checked);
            Toast.makeText(act, name + (checked ? " auto-delete enabled." : " auto-delete disabled."), Toast.LENGTH_SHORT).show();
        });
        UiStyle.styleSwitch(act, sw);
        top.addView(sw);
        card.addView(top);

        LinearLayout period = new LinearLayout(act);
        period.setOrientation(LinearLayout.HORIZONTAL);
        period.setGravity(Gravity.CENTER_VERTICAL);
        period.setPadding(0, dp(8), 0, 0);
        period.addView(text("Delete after (hours)", 12.5f, "#F4F4F5", false), new LinearLayout.LayoutParams(0, -2, 1.0f));
        final EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(hours));
        input.setTextColor(Color.WHITE);
        input.setTextSize(13f);
        input.setGravity(Gravity.CENTER);
        input.setSingleLine(true);
        period.addView(input, new LinearLayout.LayoutParams(dp(80), -2));
        Button save = actionButton("SAVE", "#38BDF8", v -> {
            int val = LibraryPrefs.DEFAULT_HOURS;
            try { val = Integer.parseInt(input.getText().toString().trim()); } catch (Throwable ignored) {}
            setHours.set(val);
            input.setText(String.valueOf(getHours.get()));
            Toast.makeText(act, "Saved: " + getHours.get() + " h", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, dp(34));
        slp.leftMargin = dp(8);
        period.addView(save, slp);
        final Runnable dirty = UiStyle.bindDirty(save, input, () -> String.valueOf(getHours.get()));
        save.setOnClickListener(v -> {
            int val = LibraryPrefs.DEFAULT_HOURS;
            try { val = Integer.parseInt(input.getText().toString().trim()); } catch (Throwable ignored) {}
            setHours.set(val);
            input.setText(String.valueOf(getHours.get()));
            dirty.run();
            Toast.makeText(act, "Saved: " + getHours.get() + " h", Toast.LENGTH_SHORT).show();
        });
        card.addView(period);
        return card;
    }

    private void runAuto(final boolean dryRun, final TextView status) {
        status.setText(dryRun ? "Checking..." : "Deleting...");
        bg.execute(() -> {
            String msg;
            try {
                msg = LibraryAutoDelete.run(act, dryRun);
            } catch (Throwable t) {
                msg = "Failed: " + t.getMessage();
            }
            final String out = msg;
            act.runOnUiThread(() -> status.setText(out));
        });
    }

    // ------------------------------------------------------------------ rows, deletion

    private void addRow(String title, String subtitle, View.OnClickListener open, final JellyfinLibraryClient.LibItem item) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackground(rounded(Color.parseColor("#0C0C0E"), Color.argb(20, 255, 255, 255), 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        if (open != null) {
            row.setClickable(true);
            row.setOnClickListener(open);
        }

        LinearLayout tx = new LinearLayout(act);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 12.5f, "#F4F4F5", true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(subtitle, 10.5f, "#71717A", false);
            s.setPadding(0, dp(2), 0, 0);
            tx.addView(s);
        }
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView del = text("DEL", 10f, "#EF4444", true);
        del.setGravity(Gravity.CENTER);
        del.setPadding(dp(8), dp(5), dp(8), dp(5));
        del.setBackground(rounded(Color.parseColor("#271212"), Color.parseColor("#7F1D1D"), 4));
        del.setClickable(true);
        del.setOnClickListener(v -> confirmDelete(item));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = dp(8);
        row.addView(del, dlp);

        listContainer.addView(row);
    }

    private void confirmDelete(final JellyfinLibraryClient.LibItem item) {
        String what;
        if ("Series".equals(item.type)) what = "the WHOLE series and all of its episode files";
        else if ("Season".equals(item.type)) what = "this season and all of its episode files";
        else what = "this file";
        new android.app.AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete from Jellyfin?")
                .setMessage(item.displayTitle() + "\n\nThis permanently deletes " + what + " from disk. It cannot be undone.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (d, w) -> {
                    Toast.makeText(act, "Deleting...", Toast.LENGTH_SHORT).show();
                    bg.execute(() -> {
                        final boolean ok = JellyfinLibraryClient.deleteItem(act, item.id);
                        act.runOnUiThread(() -> {
                            Toast.makeText(act, ok ? "Deleted." : "Jellyfin refused or could not delete it.", Toast.LENGTH_LONG).show();
                            if (ok && "Series".equals(item.type)) level = 0;
                            refresh();
                        });
                    });
                })
                .show();
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
        TextView t = text(msg == null ? "Unknown error" : msg, 12f, "#F87171", false);
        t.setPadding(0, dp(16), 0, dp(8));
        listContainer.addView(t);
        listContainer.addView(actionButton("RETRY", "#38BDF8", v -> refresh()), new LinearLayout.LayoutParams(-1, dp(40)));

        if (err instanceof JellyfinLibraryClient.AuthException) {
            TextView help = text("Sign in once with a Jellyfin administrator account. The password is stored on this device only.",
                    11f, "#A1A1AA", false);
            help.setPadding(0, dp(16), 0, dp(6));
            listContainer.addView(help);
            final EditText user = new EditText(act);
            user.setHint("Username");
            user.setSingleLine(true);
            user.setTextColor(Color.WHITE);
            user.setHintTextColor(Color.parseColor("#52525B"));
            user.setText(JellyfinLibraryClient.suggestedUsername(act));
            listContainer.addView(user, new LinearLayout.LayoutParams(-1, -2));
            final EditText pass = new EditText(act);
            pass.setHint("Password (leave empty if none)");
            pass.setSingleLine(true);
            pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            pass.setTextColor(Color.WHITE);
            pass.setHintTextColor(Color.parseColor("#52525B"));
            listContainer.addView(pass, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(40));
            blp.topMargin = dp(8);
            listContainer.addView(actionButton("SIGN IN", "#34D399", v -> {
                final String u = user.getText().toString().trim();
                final String pw = pass.getText().toString();
                Toast.makeText(act, "Signing in...", Toast.LENGTH_SHORT).show();
                bg.execute(() -> {
                    final String res = JellyfinLibraryClient.loginAndStore(act, u, pw);
                    act.runOnUiThread(() -> {
                        if (res == null) {
                            Toast.makeText(act, "Signed in.", Toast.LENGTH_SHORT).show();
                            refresh();
                        } else {
                            Toast.makeText(act, res, Toast.LENGTH_LONG).show();
                        }
                    });
                });
            }), blp);
        }
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
        lp.rightMargin = dp(6);
        return lp;
    }

    private TextView pill(String label, boolean selected, View.OnClickListener l) {
        TextView t = text(label, 9.5f, selected ? "#000000" : "#A1A1AA", true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(rounded(selected ? Color.parseColor("#38BDF8") : Color.parseColor("#0C0C0E"),
                Color.argb(30, 255, 255, 255), 8));
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
        int c = Color.parseColor(colorHex);
        b.setTextColor(c);
        b.setBackground(rounded(Color.argb(25, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(80, Color.red(c), Color.green(c), Color.blue(c)), 6));
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, float size, String colorHex, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(Color.parseColor(colorHex));
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
