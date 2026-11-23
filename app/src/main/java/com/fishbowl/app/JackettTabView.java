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
import android.text.Html;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fishbowl.app.services.JackettApi;
import com.fishbowl.app.services.TransmissionApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Jackett" tab: a native client for Jackett's web-UI JSON endpoints, styled like the Sonarr / Radarr tabs.
 *
 * <ul>
 *   <li>INDEXERS - configured indexers (test / edit / delete, tap for the Torznab feed URL + API key),
 *       add a new indexer from the catalogue, TEST ALL.</li>
 *   <li>SEARCH - manual search over all indexers; copy the magnet / link or send it to Transmission.</li>
 *   <li>SETTINGS - API key and the basic server options.</li>
 *   <li>SYSTEM - version and server information.</li>
 * </ul>
 */
public final class JackettTabView {

    private static final int S_INDEXERS = 0;
    private static final int S_SEARCH = 1;
    private static final int S_SETTINGS = 2;
    private static final int S_SYSTEM = 3;

    private static final String[] SECTION_NAMES = {"INDEXERS", "SEARCH", "SETTINGS", "SYSTEM"};

    private static final String[] CAT_NAMES = {"All", "Movies", "TV", "Audio", "Other"};
    private static final int[][] CAT_IDS = {null, {2000}, {5000}, {3000}, {8000}};

    private static final int MAX_RESULTS = 150;
    private static final int MAX_CATALOG_ROWS = 120;

    // basic server options (shown only when the server config has the key)
    private static final String[][] SET_BOOLS = {
            {"external", "Allow external access"},
            {"updatedisabled", "Disable automatic updates"},
            {"prerelease", "Use pre-release updates"},
            {"logging", "Enhanced logging"},
            {"cache_enabled", "Enable result cache"},
    };
    private static final String[][] SET_INTS = {
            {"cache_ttl", "Cache TTL (seconds)"},
            {"cache_max_results_per_indexer", "Max cached results per indexer"},
            {"flaresolverr_maxtimeout", "FlareSolverr max timeout (ms)"},
    };
    private static final String[][] SET_STRINGS = {
            {"flaresolverrurl", "FlareSolverr URL"},
            {"omdbkey", "OMDB API key"},
            {"omdburl", "OMDB API URL"},
            {"blackholedir", "Blackhole directory"},
    };
    private static final String[] SECRET_KEYS = {"api_key", "password", "proxy_password", "omdbkey", "adminpassword"};

    private interface Job<T> { T run(JackettApi api) throws Exception; }
    private interface Done<T> { void run(T value); }

    private static final class TestRes {
        final boolean ok;
        final String msg;
        TestRes(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
    }

    private final Activity act;
    private final LinearLayout root;
    private final LinearLayout navRow;
    private final LinearLayout listContainer;
    private final ScrollView scroll;
    private final ExecutorService bg = Executors.newFixedThreadPool(2);

    private int section = S_INDEXERS;
    private int requestCounter = 0;

    private JSONArray indexersCache = null;
    private final Map<String, TestRes> testResults = new HashMap<>();
    private final Map<String, TextView> statusViews = new HashMap<>();

    private String searchQuery = "";
    private int searchCat = 0;
    private List<JSONObject> searchList = null;
    private int searchTotal = 0;
    private LinearLayout searchBox = null;
    private TextView searchStatus = null;

    public JackettTabView(Activity activity) {
        this.act = activity;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppTheme.bg());

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(4));
        TextView title = text("JACKETT", 14f, AppTheme.text(), true);
        title.setLetterSpacing(0.14f);
        header.addView(title);
        TextView sub = text(I18n.tr("Manage your torrent indexers from here"), 11f, AppTheme.textMuted(), false);
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
        if (section != S_INDEXERS) {
            section = S_INDEXERS;
            refresh();
            return true;
        }
        return false;
    }

    public void refresh() {
        renderNav();
        switch (section) {
            case S_INDEXERS: loadIndexers(); break;
            case S_SEARCH: renderSearch(); break;
            case S_SETTINGS: loadSettings(); break;
            default: loadSystem(); break;
        }
    }

    private void renderNav() {
        navRow.removeAllViews();
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            final int idx = i;
            TextView p = pill(SECTION_NAMES[i], idx == section);
            p.setOnClickListener(v -> { section = idx; refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.setMarginEnd(dp(6));
            navRow.addView(p, lp);
        }
    }

    // ================================================================== async helpers

    private <T> void async(final Job<T> job, final Done<T> ok, final Done<Throwable> err) {
        bg.execute(() -> {
            try {
                final JackettApi api = JackettApi.get(act);
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
        return m == null ? "Unknown error (is Jackett running?)" : m;
    }

    private void toast(String s) { Toast.makeText(act, I18n.tr(s), Toast.LENGTH_SHORT).show(); }

    // ================================================================== INDEXERS

    private void loadIndexers() {
        final int req = ++requestCounter;
        statusViews.clear();
        listContainer.removeAllViews();
        if (indexersCache != null) renderIndexers();
        else showMessage("Loading...");
        async(api -> api.indexers(true), arr -> {
            if (req != requestCounter || section != S_INDEXERS) return;
            indexersCache = arr;
            renderIndexers();
        }, t -> { if (req == requestCounter && section == S_INDEXERS && indexersCache == null) showError(t); });
    }

    private void renderIndexers() {
        statusViews.clear();
        listContainer.removeAllViews();
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(4));
        TextView count = text((indexersCache == null ? 0 : indexersCache.length()) + " " + I18n.tr("indexers"),
                11f, AppTheme.textSecondary(), false);
        bar.addView(count, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button testAll = actionButton("TEST ALL", AppTheme.success(), v -> testAll());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, dp(36));
        tlp.setMarginEnd(dp(6));
        bar.addView(testAll, tlp);
        bar.addView(actionButton("+ ADD", AppTheme.accent(), v -> showAddDialog()), new LinearLayout.LayoutParams(-2, dp(36)));
        listContainer.addView(bar);

        if (indexersCache == null || indexersCache.length() == 0) {
            TextView t = text(I18n.tr("No indexers configured yet. Tap + ADD to add one."), 12f, AppTheme.textSecondary(), false);
            t.setPadding(0, dp(12), 0, 0);
            listContainer.addView(t);
            return;
        }
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < indexersCache.length(); i++) {
            JSONObject o = indexersCache.optJSONObject(i);
            if (o != null) list.add(o);
        }
        Collections.sort(list, (a, b) -> str(a, "name").compareToIgnoreCase(str(b, "name")));
        for (JSONObject o : list) listContainer.addView(indexerRow(o));
    }

    private View indexerRow(final JSONObject ix) {
        final String id = str(ix, "id");
        final String name = str(ix, "name").isEmpty() ? id : str(ix, "name");

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> showFeedDialog(id, name));

        TextView t = text(name, 13.5f, AppTheme.text(), true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);

        String type = str(ix, "type");
        String lang = str(ix, "language");
        TextView l1 = text(typeLabel(type) + (lang.isEmpty() ? "" : "  -  " + lang), 10.5f, typeColor(type), false);
        l1.setPadding(0, dp(2), 0, 0);
        row.addView(l1);

        String lastErr = str(ix, "last_error");
        if (!lastErr.isEmpty()) {
            TextView e = text(lastErr, 10.5f, AppTheme.warning(), false);
            e.setMaxLines(3);
            e.setEllipsize(TextUtils.TruncateAt.END);
            e.setPadding(0, dp(2), 0, 0);
            row.addView(e);
        }

        TextView status = text("", 10.5f, AppTheme.textSecondary(), false);
        status.setPadding(0, dp(2), 0, 0);
        TestRes tr = testResults.get(id);
        if (tr != null) applyStatus(status, tr);
        else status.setVisibility(View.GONE);
        statusViews.put(id, status);
        row.addView(status);

        LinearLayout btns = buttonRow();
        btns.addView(actionButton("TEST", AppTheme.success(), v -> testOne(id)), weight());
        btns.addView(actionButton("EDIT", AppTheme.accent(), v -> showIndexerForm(id, name)), weight());
        btns.addView(actionButton("DELETE", AppTheme.danger(), v ->
                confirm(I18n.tr("Delete indexer") + " \"" + name + "\"?", "DELETE", AppTheme.danger(), () ->
                        action(api -> { api.deleteIndexer(id); return null; }, "Indexer deleted.", () -> {
                            testResults.remove(id);
                            indexersCache = null;
                            if (section == S_INDEXERS) loadIndexers();
                        }))), weight());
        row.addView(btns);
        return row;
    }

    private void applyStatus(TextView status, TestRes r) {
        status.setVisibility(View.VISIBLE);
        status.setText(r.msg);
        status.setTextColor(r.ok ? AppTheme.success() : AppTheme.danger());
    }

    private void setStatus(String id, TestRes r) {
        if (r != null) testResults.put(id, r);
        TextView tv = statusViews.get(id);
        if (tv != null && r != null) applyStatus(tv, r);
    }

    private void testOne(final String id) {
        setStatus(id, new TestRes(true, I18n.tr("Testing...")));
        TextView tv = statusViews.get(id);
        if (tv != null) tv.setTextColor(AppTheme.textSecondary());
        async(api -> { api.testIndexer(id); return Boolean.TRUE; },
                ok -> setStatus(id, new TestRes(true, I18n.tr("Test passed"))),
                t -> setStatus(id, new TestRes(false, I18n.tr(msg(t)))));
    }

    private void testAll() {
        if (indexersCache == null || indexersCache.length() == 0) { toast("No indexers configured yet."); return; }
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < indexersCache.length(); i++) {
            JSONObject o = indexersCache.optJSONObject(i);
            if (o != null && !str(o, "id").isEmpty()) ids.add(str(o, "id"));
        }
        for (String id : ids) {
            setStatus(id, new TestRes(true, I18n.tr("Testing...")));
            TextView tv = statusViews.get(id);
            if (tv != null) tv.setTextColor(AppTheme.textSecondary());
        }
        toast("Testing all indexers...");
        bg.execute(() -> {
            final JackettApi api;
            try {
                api = JackettApi.get(act);
            } catch (final Throwable t) {
                act.runOnUiThread(() -> toast(msg(t)));
                return;
            }
            final int[] okFail = {0, 0};
            for (final String id : ids) {
                TestRes res;
                try {
                    api.testIndexer(id);
                    okFail[0]++;
                    res = new TestRes(true, I18n.tr("Test passed"));
                } catch (Throwable t) {
                    okFail[1]++;
                    res = new TestRes(false, I18n.tr(msg(t)));
                }
                final TestRes fr = res;
                act.runOnUiThread(() -> setStatus(id, fr));
            }
            act.runOnUiThread(() -> toast(okFail[0] + " OK, " + okFail[1] + " failed"));
        });
    }

    // ------------------------------------------------------------------ feed dialog

    private void showFeedDialog(final String id, final String name) {
        final LinearLayout body = dialogBody(name);
        body.addView(text(I18n.tr("Loading..."), 12f, AppTheme.textSecondary(), false));
        final Dialog d = dialog(body);
        d.show();
        async(api -> new String[]{api.torznabUrl(id), api.apiKey()}, vals -> {
            body.removeAllViews();
            TextView title = text(name, 15f, AppTheme.text(), true);
            title.setPadding(0, 0, 0, dp(6));
            body.addView(title);
            body.addView(text(I18n.tr("Torznab feed URL"), 11f, AppTheme.textMuted(), false));
            TextView url = text(vals[0], 12f, AppTheme.text(), false);
            url.setTextIsSelectable(true);
            url.setPadding(0, dp(2), 0, dp(8));
            body.addView(url);
            body.addView(text(I18n.tr("API key"), 11f, AppTheme.textMuted(), false));
            TextView key = text(vals[1], 12f, AppTheme.text(), false);
            key.setTextIsSelectable(true);
            key.setPadding(0, dp(2), 0, dp(4));
            body.addView(key);
            LinearLayout row = buttonRow();
            row.addView(actionButton("COPY URL", AppTheme.accent(), v -> copy("Torznab URL", vals[0])), weight());
            row.addView(actionButton("COPY KEY", AppTheme.accent(), v -> copy("API key", vals[1])), weight());
            row.addView(actionButton("CLOSE", AppTheme.textSecondary(), v -> d.dismiss()), weight());
            body.addView(row);
        }, t -> {
            body.removeAllViews();
            body.addView(text(I18n.tr(msg(t)), 12f, AppTheme.danger(), false));
            LinearLayout row = buttonRow();
            row.addView(actionButton("CLOSE", AppTheme.textSecondary(), v -> d.dismiss()), weight());
            body.addView(row);
        });
    }

    // ------------------------------------------------------------------ add indexer

    private void showAddDialog() {
        final LinearLayout body = dialogBody(I18n.tr("Add indexer"));
        final EditText filter = edit(I18n.tr("Search indexers"), "", InputType.TYPE_CLASS_TEXT);
        body.addView(filter);
        final LinearLayout rows = new LinearLayout(act);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(scrollBox(rows, 0.5f));
        final Dialog d = dialog(body);
        LinearLayout btn = buttonRow();
        btn.addView(actionButton("CLOSE", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        body.addView(btn);

        final List<JSONObject> catalog = new ArrayList<>();
        final Runnable fill = () -> {
            rows.removeAllViews();
            String f = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            int matches = 0;
            for (JSONObject o : catalog) {
                if (!f.isEmpty()) {
                    String hay = (str(o, "name") + " " + str(o, "id") + " " + str(o, "type") + " "
                            + str(o, "language") + " " + str(o, "description")).toLowerCase(Locale.ROOT);
                    if (!hay.contains(f)) continue;
                }
                matches++;
                if (shown >= MAX_CATALOG_ROWS) continue;
                shown++;
                rows.addView(catalogRow(o, d));
            }
            if (matches == 0) {
                rows.addView(text(I18n.tr("No matches."), 12f, AppTheme.textSecondary(), false));
            } else if (matches > shown) {
                TextView more = text(I18n.tr("Showing the first") + " " + shown + " / " + matches + ". " + I18n.tr("Type to narrow down."),
                        10.5f, AppTheme.textMuted(), false);
                more.setPadding(0, dp(6), 0, 0);
                rows.addView(more);
            }
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { fill.run(); }
        });
        rows.addView(text(I18n.tr("Loading..."), 12f, AppTheme.textSecondary(), false));
        d.show();
        async(api -> api.indexers(false), arr -> {
            catalog.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) catalog.add(o);
            }
            Collections.sort(catalog, (a, b) -> str(a, "name").compareToIgnoreCase(str(b, "name")));
            fill.run();
        }, t -> {
            rows.removeAllViews();
            rows.addView(text(I18n.tr(msg(t)), 12f, AppTheme.danger(), false));
        });
    }

    private View catalogRow(final JSONObject o, final Dialog d) {
        final String id = str(o, "id");
        final String name = str(o, "name").isEmpty() ? id : str(o, "name");
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.addView(text(name, 12.5f, AppTheme.text(), true));
        String type = str(o, "type");
        String lang = str(o, "language");
        TextView l = text(typeLabel(type) + (lang.isEmpty() ? "" : "  -  " + lang), 10.5f, typeColor(type), false);
        l.setPadding(0, dp(2), 0, 0);
        row.addView(l);
        String desc = str(o, "description");
        if (!desc.isEmpty()) {
            TextView dt = text(desc, 10f, AppTheme.textMuted(), false);
            dt.setMaxLines(2);
            dt.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(dt);
        }
        row.setOnClickListener(v -> { d.dismiss(); showIndexerForm(id, name); });
        return row;
    }

    // ------------------------------------------------------------------ indexer config form

    private void showIndexerForm(final String id, final String name) {
        final LinearLayout body = dialogBody(name);
        final LinearLayout form = new LinearLayout(act);
        form.setOrientation(LinearLayout.VERTICAL);
        form.addView(text(I18n.tr("Loading..."), 12f, AppTheme.textSecondary(), false));
        body.addView(scrollBox(form, 0.5f));
        final TextView status = text("", 11f, AppTheme.danger(), false);
        status.setPadding(0, dp(6), 0, 0);
        status.setVisibility(View.GONE);
        body.addView(status);
        final Dialog d = dialog(body);
        LinearLayout btns = buttonRow();
        final Button save = actionButton("SAVE", AppTheme.success(), null);
        save.setEnabled(false);
        save.setAlpha(0.38f);
        btns.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        btns.addView(save, weight());
        body.addView(btns);
        d.show();

        final List<Runnable> collectors = new ArrayList<>();
        final JSONArray[] fieldsRef = new JSONArray[1];
        async(api -> api.indexerConfig(id), fields -> {
            fieldsRef[0] = fields;
            form.removeAllViews();
            buildForm(form, fields, collectors);
            save.setEnabled(true);
            save.setAlpha(1f);
        }, t -> {
            form.removeAllViews();
            form.addView(text(I18n.tr(msg(t)), 12f, AppTheme.danger(), false));
        });

        save.setOnClickListener(v -> {
            if (fieldsRef[0] == null) return;
            for (Runnable r : collectors) r.run();
            save.setEnabled(false);
            save.setAlpha(0.38f);
            status.setVisibility(View.VISIBLE);
            status.setTextColor(AppTheme.textSecondary());
            status.setText(I18n.tr("Saving and testing the indexer, this can take a while..."));
            final JSONArray toSend = fieldsRef[0];
            async(api -> { api.saveIndexerConfig(id, toSend); return Boolean.TRUE; }, ok -> {
                d.dismiss();
                toast("Indexer saved.");
                testResults.remove(id);
                indexersCache = null;
                if (section == S_INDEXERS) loadIndexers();
            }, t -> {
                save.setEnabled(true);
                save.setAlpha(1f);
                status.setTextColor(AppTheme.danger());
                status.setText(I18n.tr(msg(t)));
            });
        });
    }

    private void buildForm(LinearLayout form, JSONArray fields, List<Runnable> collectors) {
        int shown = 0;
        for (int i = 0; i < fields.length(); i++) {
            final JSONObject f = fields.optJSONObject(i);
            if (f == null) continue;
            String type = str(f, "type").toLowerCase(Locale.ROOT);
            String fid = str(f, "id");
            String label = str(f, "name").isEmpty() ? fid : str(f, "name");

            if (type.equals("hiddendata")) continue;

            if (type.equals("displayinfo")) {
                String v = str(f, "value");
                CharSequence shownText = v.isEmpty() ? "" : Html.fromHtml(v);
                if (label.length() > 0 || shownText.length() > 0) {
                    TextView info = text(label, 11f, AppTheme.warning(), true);
                    info.setPadding(0, dp(8), 0, 0);
                    form.addView(info);
                    if (shownText.length() > 0) {
                        TextView body = text("", 11f, AppTheme.textSecondary(), false);
                        body.setText(shownText);
                        form.addView(body);
                    }
                    shown++;
                }
                continue;
            }

            if (type.equals("displayimage")) {
                TextView cap = text(I18n.tr("This indexer asks for a captcha that cannot be shown here."), 11f, AppTheme.warning(), false);
                cap.setPadding(0, dp(8), 0, 0);
                form.addView(cap);
                continue;
            }

            if (type.equals("inputbool")) {
                final Switch sw = addSwitchRow(form, label, null, f.optBoolean("value", false));
                collectors.add(() -> putQuiet(f, "value", Boolean.valueOf(sw.isChecked())));
                shown++;
                continue;
            }

            if (type.equals("inputselect")) {
                final JSONObject opts = f.optJSONObject("options");
                final List<String> keys = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                if (opts != null) {
                    Iterator<String> it = opts.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        keys.add(k);
                        labels.add(str(opts, k));
                    }
                }
                final String[] current = {str(f, "value")};
                form.addView(fieldLabel(label));
                final TextView picker = text("", 12.5f, AppTheme.text(), false);
                picker.setPadding(dp(10), dp(10), dp(10), dp(10));
                picker.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 8));
                picker.setClickable(true);
                final Runnable show = () -> {
                    int ix = keys.indexOf(current[0]);
                    picker.setText((ix >= 0 ? labels.get(ix) : current[0]) + "  ▾");
                };
                show.run();
                picker.setOnClickListener(v -> {
                    if (keys.isEmpty()) return;
                    AlertDialog pd = new AlertDialog.Builder(act)
                            .setItems(labels.toArray(new String[0]), (dlg, which) -> {
                                current[0] = keys.get(which);
                                show.run();
                            }).create();
                    if (pd.getWindow() != null) pd.getWindow().setBackgroundDrawable(rounded(AppTheme.card(), AppTheme.outline(), 16));
                    pd.show();
                });
                form.addView(picker);
                collectors.add(() -> putQuiet(f, "value", current[0]));
                shown++;
                continue;
            }

            if (type.equals("inputcheckbox")) {
                final JSONObject opts = f.optJSONObject("options");
                final JSONArray vals = f.optJSONArray("value");
                form.addView(fieldLabel(label));
                final List<String> keys = new ArrayList<>();
                final List<Switch> switches = new ArrayList<>();
                if (opts != null) {
                    Iterator<String> it = opts.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        boolean on = false;
                        for (int j = 0; vals != null && j < vals.length(); j++) {
                            if (k.equals(String.valueOf(vals.opt(j)))) on = true;
                        }
                        keys.add(k);
                        switches.add(addSwitchRow(form, str(opts, k), null, on));
                    }
                }
                collectors.add(() -> {
                    JSONArray sel = new JSONArray();
                    for (int j = 0; j < keys.size(); j++) {
                        if (switches.get(j).isChecked()) sel.put(keys.get(j));
                    }
                    putQuiet(f, "value", sel);
                });
                shown++;
                continue;
            }

            if (type.equals("inputtags")) {
                JSONArray vals = f.optJSONArray("value");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; vals != null && j < vals.length(); j++) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(String.valueOf(vals.opt(j)));
                }
                form.addView(fieldLabel(label));
                final EditText et = edit("", sb.toString(), InputType.TYPE_CLASS_TEXT);
                form.addView(et);
                collectors.add(() -> {
                    JSONArray out = new JSONArray();
                    for (String part : et.getText().toString().split(",")) {
                        String p = part.trim();
                        if (!p.isEmpty()) out.put(p);
                    }
                    putQuiet(f, "value", out);
                });
                shown++;
                continue;
            }

            // inputstring and anything unknown that carries a plain value
            if (type.equals("inputstring") || f.opt("value") == null || f.opt("value") instanceof String
                    || f.opt("value") == JSONObject.NULL) {
                String lower = (fid + " " + label).toLowerCase(Locale.ROOT);
                boolean secret = lower.contains("pass") || lower.contains("secret");
                int it = secret ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                        : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                form.addView(fieldLabel(label));
                final EditText et = edit("", str(f, "value"), it);
                form.addView(et);
                collectors.add(() -> putQuiet(f, "value", et.getText().toString()));
                shown++;
            }
        }
        if (shown == 0) {
            form.addView(text(I18n.tr("This indexer has no settings. Just press SAVE to add it."), 12f, AppTheme.textSecondary(), false));
        }
    }

    private TextView fieldLabel(String s) {
        TextView t = text(s, 11f, AppTheme.textMuted(), false);
        t.setPadding(0, dp(10), 0, dp(3));
        return t;
    }

    // ================================================================== SEARCH

    private void renderSearch() {
        requestCounter++;
        statusViews.clear();
        listContainer.removeAllViews();

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(4));
        final EditText q = edit(I18n.tr("Search all indexers"), searchQuery, InputType.TYPE_CLASS_TEXT);
        q.setSingleLine(true);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        bar.addView(q, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final Runnable go = () -> {
            searchQuery = q.getText().toString().trim();
            runSearch();
        };
        q.setOnEditorActionListener((v, actionId, ev) -> { go.run(); return true; });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, dp(40));
        blp.setMarginStart(dp(8));
        bar.addView(actionButton("SEARCH", AppTheme.accent(), v -> go.run()), blp);
        listContainer.addView(bar);

        HorizontalScrollView chipScroll = new HorizontalScrollView(act);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(4), 0, dp(6));
        for (int i = 0; i < CAT_NAMES.length; i++) {
            final int idx = i;
            TextView c = pill(CAT_NAMES[i], idx == searchCat);
            c.setOnClickListener(v -> {
                searchCat = idx;
                searchQuery = q.getText().toString().trim();
                renderSearch();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.setMarginEnd(dp(6));
            chips.addView(c, lp);
        }
        chipScroll.addView(chips);
        listContainer.addView(chipScroll);

        searchStatus = text("", 11f, AppTheme.textSecondary(), false);
        searchStatus.setPadding(0, dp(2), 0, dp(2));
        listContainer.addView(searchStatus);
        searchBox = new LinearLayout(act);
        searchBox.setOrientation(LinearLayout.VERTICAL);
        listContainer.addView(searchBox);

        if (searchList != null) renderSearchResults();
        else searchStatus.setText(I18n.tr("Type a query and press SEARCH. Results come from all configured indexers."));
    }

    private void runSearch() {
        if (searchQuery.isEmpty()) { toast("Enter a search term."); return; }
        final int req = ++requestCounter;
        final String query = searchQuery;
        final int[] cats = CAT_IDS[searchCat];
        searchList = null;
        searchBox.removeAllViews();
        searchStatus.setText(I18n.tr("Searching..."));
        async(api -> api.search(query, cats), arr -> {
            if (req != requestCounter || section != S_SEARCH) return;
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(o);
            }
            Collections.sort(list, (a, b) -> {
                int sa = a.optInt("Seeders", 0);
                int sb = b.optInt("Seeders", 0);
                return sa == sb ? 0 : (sa > sb ? -1 : 1);
            });
            searchTotal = list.size();
            searchList = list.size() > MAX_RESULTS ? new ArrayList<>(list.subList(0, MAX_RESULTS)) : list;
            renderSearchResults();
        }, t -> {
            if (req != requestCounter || section != S_SEARCH) return;
            searchStatus.setText("");
            searchBox.removeAllViews();
            TextView e = text(I18n.tr(msg(t)), 12f, AppTheme.danger(), false);
            e.setPadding(0, dp(8), 0, 0);
            searchBox.addView(e);
        });
    }

    private void renderSearchResults() {
        searchBox.removeAllViews();
        if (searchList == null) return;
        if (searchList.isEmpty()) {
            searchStatus.setText(I18n.tr("No results."));
            return;
        }
        searchStatus.setText(searchList.size() + (searchTotal > searchList.size() ? " / " + searchTotal : "") + " " + I18n.tr("results"));
        for (JSONObject r : searchList) searchBox.addView(resultRow(r));
    }

    private View resultRow(final JSONObject r) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> showResultDialog(r));

        TextView t = text(str(r, "Title"), 12.5f, AppTheme.text(), true);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);

        double age = ageMinutes(str(r, "PublishDate"));
        String line = str(r, "Tracker") + "  -  " + fmtSize(r.optLong("Size", 0))
                + "  -  S:" + r.optInt("Seeders", 0) + " P:" + r.optInt("Peers", 0)
                + (age >= 0 ? "  -  " + fmtAge(age) : "");
        TextView l = text(line, 10.5f, AppTheme.textSecondary(), false);
        l.setPadding(0, dp(2), 0, 0);
        row.addView(l);
        String cat = str(r, "CategoryDesc");
        if (!cat.isEmpty()) row.addView(text(cat, 10f, AppTheme.textMuted(), false));
        return row;
    }

    private void showResultDialog(final JSONObject r) {
        final String magnet = str(r, "MagnetUri");
        final String link = str(r, "Link");
        final LinearLayout body = dialogBody(I18n.tr("Result"));
        TextView title = text(str(r, "Title"), 12.5f, AppTheme.text(), true);
        title.setPadding(0, 0, 0, dp(4));
        body.addView(title);
        body.addView(text(str(r, "Tracker") + "  -  " + fmtSize(r.optLong("Size", 0))
                + "  -  S:" + r.optInt("Seeders", 0) + " P:" + r.optInt("Peers", 0), 11f, AppTheme.textSecondary(), false));
        final Dialog d = dialog(body);

        if (magnet.isEmpty() && link.isEmpty()) {
            TextView none = text(I18n.tr("This result has no magnet or download link."), 11.5f, AppTheme.warning(), false);
            none.setPadding(0, dp(8), 0, 0);
            body.addView(none);
        } else {
            LinearLayout copyRow = buttonRow();
            if (!magnet.isEmpty()) copyRow.addView(actionButton("COPY MAGNET", AppTheme.accent(), v -> { copy("Magnet", magnet); d.dismiss(); }), weight());
            if (!link.isEmpty()) copyRow.addView(actionButton("COPY LINK", AppTheme.accent(), v -> { copy("Link", link); d.dismiss(); }), weight());
            body.addView(copyRow);
            final String src = magnet.isEmpty() ? link : magnet;
            body.addView(actionButton("SEND TO TRANSMISSION", AppTheme.success(), v -> {
                d.dismiss();
                sendToTransmission(src);
            }), lpTop(dp(38), dp(8)));
        }
        LinearLayout closeRow = buttonRow();
        closeRow.addView(actionButton("CLOSE", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        body.addView(closeRow);
        d.show();
    }

    private void sendToTransmission(final String source) {
        toast("Sending to Transmission...");
        bg.execute(() -> {
            try {
                TransmissionApi.get(act).torrentAdd(source, null, null, false);
                act.runOnUiThread(() -> toast("Sent to Transmission."));
            } catch (final Throwable t) {
                act.runOnUiThread(() -> toast(msg(t)));
            }
        });
    }

    // ================================================================== SETTINGS

    private void loadSettings() {
        final int req = ++requestCounter;
        statusViews.clear();
        showMessage("Loading...");
        async(api -> new Object[]{api.serverConfig(), api.apiKey()}, o -> {
            if (req != requestCounter || section != S_SETTINGS) return;
            renderSettings((JSONObject) o[0], (String) o[1]);
        }, t -> { if (req == requestCounter && section == S_SETTINGS) showError(t); });
    }

    private void renderSettings(final JSONObject cfg, final String apiKey) {
        listContainer.removeAllViews();

        LinearLayout keyCard = card();
        keyCard.addView(text(I18n.tr("API key"), 13.5f, AppTheme.text(), true));
        TextView kv = text(apiKey, 12f, AppTheme.textSecondary(), false);
        kv.setTextIsSelectable(true);
        kv.setPadding(0, dp(4), 0, dp(4));
        keyCard.addView(kv);
        LinearLayout kr = buttonRow();
        kr.addView(actionButton("COPY KEY", AppTheme.accent(), v -> copy("API key", apiKey)), weight());
        keyCard.addView(kr);
        listContainer.addView(keyCard);

        LinearLayout card = card();
        card.addView(text(I18n.tr("Server settings"), 13.5f, AppTheme.text(), true));
        if (cfg.has("port")) {
            TextView p = text(I18n.tr("Port") + ": " + cfg.optInt("port") + "  (" + I18n.tr("managed by the app") + ")",
                    11.5f, AppTheme.textSecondary(), false);
            p.setPadding(0, dp(6), 0, 0);
            card.addView(p);
        }

        final Map<String, Switch> boolViews = new HashMap<>();
        for (String[] b : SET_BOOLS) {
            if (!cfg.has(b[0])) continue;
            boolViews.put(b[0], addSwitchRow(card, I18n.tr(b[1]), null, cfg.optBoolean(b[0], false)));
        }
        final Map<String, EditText> intViews = new HashMap<>();
        for (String[] s : SET_INTS) {
            if (!cfg.has(s[0])) continue;
            card.addView(fieldLabel(I18n.tr(s[1])));
            EditText et = edit("", String.valueOf(cfg.optLong(s[0], 0)), InputType.TYPE_CLASS_NUMBER);
            card.addView(et);
            intViews.put(s[0], et);
        }
        final Map<String, EditText> strViews = new HashMap<>();
        for (String[] s : SET_STRINGS) {
            if (!cfg.has(s[0])) continue;
            card.addView(fieldLabel(I18n.tr(s[1])));
            EditText et = edit("", str(cfg, s[0]), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            card.addView(et);
            strViews.put(s[0], et);
        }

        TextView note = text(I18n.tr("Changing some options makes Jackett restart itself."), 10.5f, AppTheme.textMuted(), false);
        note.setPadding(0, dp(10), 0, 0);
        card.addView(note);

        final Button save = actionButton("SAVE", AppTheme.success(), null);
        save.setOnClickListener(v -> {
            for (Map.Entry<String, Switch> e : boolViews.entrySet()) putQuiet(cfg, e.getKey(), Boolean.valueOf(e.getValue().isChecked()));
            for (Map.Entry<String, EditText> e : intViews.entrySet()) {
                long val = 0;
                try { val = Long.parseLong(e.getValue().getText().toString().trim()); } catch (Throwable ignored) { val = cfg.optLong(e.getKey(), 0); }
                putQuiet(cfg, e.getKey(), Long.valueOf(val));
            }
            for (Map.Entry<String, EditText> e : strViews.entrySet()) putQuiet(cfg, e.getKey(), e.getValue().getText().toString().trim());
            save.setEnabled(false);
            save.setAlpha(0.38f);
            async(api -> { api.saveServerConfig(cfg); return Boolean.TRUE; }, ok -> {
                save.setEnabled(true);
                save.setAlpha(1f);
                toast("Settings saved. Jackett may restart.");
            }, t -> {
                save.setEnabled(true);
                save.setAlpha(1f);
                toast(msg(t));
            });
        });
        card.addView(save, lpTop(dp(38), dp(10)));
        listContainer.addView(card);
    }

    // ================================================================== SYSTEM

    private void loadSystem() {
        final int req = ++requestCounter;
        statusViews.clear();
        showMessage("Loading...");
        async(api -> {
            Object[] out = new Object[4];
            out[0] = api.serverConfig();
            out[1] = api.torznabUrl("all");
            try { out[2] = Integer.valueOf(api.indexers(true).length()); } catch (Throwable ignored) { out[2] = null; }
            out[3] = JackettApi.HOST;
            return out;
        }, o -> {
            if (req != requestCounter || section != S_SYSTEM) return;
            renderSystem((JSONObject) o[0], (String) o[1], (Integer) o[2]);
        }, t -> { if (req == requestCounter && section == S_SYSTEM) showError(t); });
    }

    private void renderSystem(JSONObject cfg, final String allFeed, Integer indexerCount) {
        listContainer.removeAllViews();
        LinearLayout card = card();
        card.addView(text("Jackett", 13.5f, AppTheme.text(), true));
        String version = str(cfg, "app_version");
        if (version.isEmpty()) version = str(cfg, "version");
        card.addView(kvRow(I18n.tr("Version"), version.isEmpty() ? "?" : version));
        card.addView(kvRow(I18n.tr("Address"), JackettApi.HOST));
        if (indexerCount != null) card.addView(kvRow(I18n.tr("Configured indexers"), String.valueOf(indexerCount)));
        card.addView(kvRow(I18n.tr("Config folder"), "~/.config/Jackett"));
        card.addView(kvRow(I18n.tr("Aggregate Torznab feed"), allFeed));
        LinearLayout r = buttonRow();
        r.addView(actionButton("COPY URL", AppTheme.accent(), v -> copy("Torznab URL", allFeed)), weight());
        r.addView(actionButton("REFRESH", AppTheme.textSecondary(), v -> refresh()), weight());
        card.addView(r);
        listContainer.addView(card);

        LinearLayout info = card();
        info.addView(text(I18n.tr("Server config"), 13.5f, AppTheme.text(), true));
        TreeMap<String, String> sorted = new TreeMap<>();
        Iterator<String> it = cfg.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = cfg.opt(k);
            if (v instanceof JSONObject || v instanceof JSONArray) continue;
            boolean secret = false;
            for (String s : SECRET_KEYS) if (s.equalsIgnoreCase(k)) secret = true;
            if (secret) continue;
            String sv = cfg.isNull(k) ? "" : String.valueOf(v);
            if (sv.length() > 200) sv = sv.substring(0, 200) + "...";
            sorted.put(k, sv);
        }
        if (sorted.isEmpty()) info.addView(text(I18n.tr("No data."), 12f, AppTheme.textSecondary(), false));
        for (Map.Entry<String, String> e : sorted.entrySet()) info.addView(kvRow(e.getKey(), e.getValue().isEmpty() ? "-" : e.getValue()));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) info.getLayoutParams();
        lp.topMargin = dp(10);
        info.setLayoutParams(lp);
        listContainer.addView(info);
    }

    private View kvRow(String k, String v) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        row.addView(text(k, 10.5f, AppTheme.textMuted(), false));
        TextView val = text(v, 12f, AppTheme.text(), false);
        val.setTextIsSelectable(true);
        row.addView(val);
        return row;
    }

    // ================================================================== dialogs & small UI helpers

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

    private Switch addSwitchRow(LinearLayout parent, String label, String hint, boolean on) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        LinearLayout tx = new LinearLayout(act);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(label, 12.5f, AppTheme.text(), false));
        if (hint != null) tx.addView(text(hint, 10.5f, AppTheme.textMuted(), false));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch sw = new Switch(act);
        sw.setChecked(on);
        UiStyle.styleSwitch(act, sw);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private EditText edit(String hint, String value, int inputType) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setHintTextColor(AppTheme.textMuted());
        e.setTextColor(AppTheme.text());
        e.setTextSize(13f);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setText(value == null ? "" : value);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 8));
        return e;
    }

    private View scrollBox(View content, float screenFraction) {
        ScrollView sv = new ScrollView(act);
        sv.addView(content, new ViewGroup.LayoutParams(-1, -2));
        int h = (int) (act.getResources().getDisplayMetrics().heightPixels * screenFraction);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, h);
        lp.topMargin = dp(6);
        sv.setLayoutParams(lp);
        return sv;
    }

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

    private void copy(String label, String value) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) { toast("Clipboard not available."); return; }
            cm.setPrimaryClip(ClipData.newPlainText(label, value));
            toast("Copied.");
        } catch (Throwable t) {
            toast("Could not copy.");
        }
    }

    private void showMessage(String m) {
        listContainer.removeAllViews();
        TextView t = text(I18n.tr(m), 12f, AppTheme.textSecondary(), false);
        t.setPadding(0, dp(16), 0, 0);
        listContainer.addView(t);
    }

    private void showError(Throwable err) {
        listContainer.removeAllViews();
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

    private LinearLayout.LayoutParams lpTop(int h, int topPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, h);
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

    // ================================================================== pure helpers

    /** Null-safe string read that maps JSON null to "". */
    private static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        return o.optString(key, "");
    }

    private static void putQuiet(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Throwable ignored) {}
    }

    private static String typeLabel(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.equals("public")) return I18n.tr("Public");
        if (t.equals("private")) return I18n.tr("Private");
        if (t.equals("semi-private")) return I18n.tr("Semi-private");
        return t.isEmpty() ? "" : t;
    }

    private static int typeColor(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.equals("public")) return AppTheme.success();
        if (t.equals("private")) return AppTheme.warning();
        return AppTheme.accent();
    }

    /** Minutes since the given ISO-8601 timestamp, or -1 when it cannot be parsed. Offsets are ignored. */
    private static double ageMinutes(String iso) {
        if (iso == null || iso.length() < 19) return -1;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso.substring(0, 19));
            if (d == null) return -1;
            double m = (System.currentTimeMillis() - d.getTime()) / 60000.0;
            return m < 0 ? 0 : m;
        } catch (Throwable t) {
            return -1;
        }
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
