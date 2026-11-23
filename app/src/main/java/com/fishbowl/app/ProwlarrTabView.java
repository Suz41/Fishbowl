package com.fishbowl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.fishbowl.app.services.ProwlarrApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Prowlarr" tab: a native client for Prowlarr's web UI, structured like {@link RadarrTabView}.
 *
 * <ul>
 *   <li>INDEXERS - list with status badges, enable / disable, test, edit, delete, add from schema, test all.</li>
 *   <li>SEARCH - search all indexers (category chips) and grab a release.</li>
 *   <li>APPS - synced applications (Sonarr / Radarr ...), test, delete, sync now.</li>
 *   <li>STATS - per-indexer queries, grabs, failures and response time.</li>
 *   <li>HISTORY - paged indexer queries and grabs.</li>
 *   <li>SYSTEM - status, health, disk space, tasks, download clients, maintenance commands.</li>
 * </ul>
 */
public final class ProwlarrTabView {

    private static final int S_INDEXERS = 0;
    private static final int S_SEARCH = 1;
    private static final int S_APPS = 2;
    private static final int S_STATS = 3;
    private static final int S_HISTORY = 4;
    private static final int S_SYSTEM = 5;

    private static final String[] SECTION_NAMES = {"INDEXERS", "SEARCH", "APPS", "STATS", "HISTORY", "SYSTEM"};
    private static final String[] CAT_NAMES = {"ALL", "MOVIES", "TV", "AUDIO", "OTHER"};
    private static final int[] CAT_IDS = {0, 2000, 5000, 3000, 8000};
    private static final int PAGE_SIZE = 40;

    private interface Job<T> { T run(ProwlarrApi api) throws Exception; }
    private interface Done<T> { void run(T value); }
    private interface TextSink { void set(String s); }
    private interface BoolSink { void set(boolean b); }
    private interface IntSink { void set(int i); }

    private final Activity act;
    private final LinearLayout root;
    private final LinearLayout navRow;
    private final LinearLayout listContainer;
    private final ScrollView scroll;
    private final ExecutorService bg = Executors.newFixedThreadPool(2);

    private int section = S_INDEXERS;
    private int page = 1;
    private int requestCounter = 0;
    private String indexerFilter = "";
    private JSONArray indexerCache = null;
    private JSONArray statusCache = null;
    private JSONArray schemaCache = null;
    private final Map<Integer, String> indexerNames = new HashMap<>();

    private int searchCat = 0;
    private String searchQuery = "";
    private JSONArray searchResults = null;
    private LinearLayout searchBox = null;

    public ProwlarrTabView(Activity activity) {
        this.act = activity;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppTheme.bg());

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(4));
        TextView title = text("PROWLARR", 14f, AppTheme.text(), true);
        title.setLetterSpacing(0.14f);
        header.addView(title);
        TextView sub = text(I18n.tr("Manage your indexers and search them from here"), 11f, AppTheme.textMuted(), false);
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
        if (section == S_HISTORY && page > 1) {
            page--;
            refresh();
            return true;
        }
        if (section != S_INDEXERS) {
            section = S_INDEXERS;
            page = 1;
            refresh();
            return true;
        }
        return false;
    }

    public void refresh() {
        renderNav();
        switch (section) {
            case S_SEARCH: renderSearch(); break;
            case S_APPS: loadApps(); break;
            case S_STATS: loadStats(); break;
            case S_HISTORY: loadHistory(); break;
            case S_SYSTEM: loadSystem(); break;
            default: loadIndexers(); break;
        }
    }

    private void renderNav() {
        navRow.removeAllViews();
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            final int idx = i;
            TextView p = pill(SECTION_NAMES[i], idx == section);
            p.setOnClickListener(v -> {
                section = idx;
                page = 1;
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
                final ProwlarrApi api = ProwlarrApi.get(act);
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
        return m == null ? "Unknown error (is Prowlarr running?)" : m;
    }

    private void toast(String s) { Toast.makeText(act, I18n.tr(s), Toast.LENGTH_SHORT).show(); }

    // ================================================================== JSON helpers

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
            // JSONException only for invalid numbers
        }
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (Exception e) {
            return o;
        }
    }

    /** Null-safe string of a JSON value ("" for missing / JSON null). */
    private static String str(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        return String.valueOf(v);
    }

    private static String shortDate(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return "-";
        String s = iso.replace('T', ' ');
        return s.length() >= 16 ? s.substring(0, 16) : s;
    }

    private void rememberNames(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optInt("id") > 0) indexerNames.put(o.optInt("id"), o.optString("name"));
        }
    }

    // ================================================================== INDEXERS

    private void loadIndexers() {
        final int req = ++requestCounter;
        listContainer.removeAllViews();
        if (indexerCache != null) renderIndexers(); else showMessage("Loading...");
        async(api -> {
            JSONObject o = new JSONObject();
            o.put("indexers", api.indexers());
            try { o.put("status", api.indexerStatus()); } catch (Throwable ignored) {}
            return o;
        }, o -> {
            if (req != requestCounter || section != S_INDEXERS) return;
            indexerCache = o.optJSONArray("indexers");
            statusCache = o.optJSONArray("status");
            rememberNames(indexerCache);
            renderIndexers();
        }, t -> { if (req == requestCounter && indexerCache == null) showError(t); });
    }

    private void renderIndexers() {
        listContainer.removeAllViews();
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(4));
        final EditText filter = new EditText(act);
        filter.setHint(I18n.tr("Filter indexers"));
        filter.setHintTextColor(AppTheme.textMuted());
        filter.setTextColor(AppTheme.text());
        filter.setTextSize(13f);
        filter.setSingleLine(true);
        filter.setText(indexerFilter);
        filter.setSelection(filter.getText().length());
        bar.addView(filter, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button testAll = actionButton("TEST ALL", AppTheme.textSecondary(), v ->
                action(api -> { api.testAllIndexers(); return null; }, "All indexers passed the test.", this::refresh));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, dp(36));
        tlp.setMarginStart(dp(8));
        bar.addView(testAll, tlp);
        Button add = actionButton("+ ADD", AppTheme.accent(), v -> showAddIndexer());
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
        fillIndexerRows(rows, count);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                indexerFilter = e.toString();
                fillIndexerRows(rows, count);
            }
        });
    }

    private void fillIndexerRows(LinearLayout rows, TextView count) {
        rows.removeAllViews();
        if (indexerCache == null) return;
        Map<Integer, String> failing = new HashMap<>();
        for (int i = 0; statusCache != null && i < statusCache.length(); i++) {
            JSONObject s = statusCache.optJSONObject(i);
            if (s != null && !str(s.opt("disabledTill")).isEmpty()) failing.put(s.optInt("indexerId"), str(s.opt("disabledTill")));
        }
        List<JSONObject> list = new ArrayList<>();
        String f = indexerFilter.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < indexerCache.length(); i++) {
            JSONObject o = indexerCache.optJSONObject(i);
            if (o == null) continue;
            if (!f.isEmpty() && !o.optString("name", "").toLowerCase(Locale.ROOT).contains(f)) continue;
            list.add(o);
        }
        Collections.sort(list, (a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
        count.setText(list.size() + " / " + indexerCache.length() + " " + I18n.tr("indexers"));
        if (list.isEmpty()) {
            TextView t = text(I18n.tr(indexerCache.length() == 0 ? "No indexers yet. Tap + ADD to add one." : "No matches."),
                    12f, AppTheme.textSecondary(), false);
            t.setPadding(0, dp(12), 0, 0);
            rows.addView(t);
            return;
        }
        for (JSONObject o : list) rows.addView(indexerRow(o, failing.containsKey(o.optInt("id"))));
    }

    private View indexerRow(final JSONObject ix, boolean failing) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        box.setLayoutParams(lp);

        final boolean enabled = ix.optBoolean("enable");
        TextView name = text(ix.optString("name"), 13f, enabled ? AppTheme.text() : AppTheme.textMuted(), true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(name);

        StringBuilder sb = new StringBuilder();
        String state = enabled ? "● " + I18n.tr("Enabled") : "○ " + I18n.tr("Disabled");
        sb.append(state);
        String proto = ix.optString("protocol", "");
        if (!proto.isEmpty()) sb.append("  -  ").append(proto.toUpperCase(Locale.ROOT));
        String privacy = ix.optString("privacy", "");
        if (!privacy.isEmpty()) sb.append("  -  ").append(capitalize(privacy));
        sb.append("  -  ").append(I18n.tr("Priority")).append(" ").append(ix.optInt("priority", 25));
        TextView meta = text(sb.toString(), 10.5f, AppTheme.textSecondary(), false);
        meta.setPadding(0, dp(2), 0, dp(2));
        box.addView(meta);
        if (failing) {
            box.addView(text("✖ " + I18n.tr("Failing - temporarily disabled by Prowlarr"), 10.5f, AppTheme.danger(), false));
        } else if (enabled) {
            box.addView(text("✔ " + I18n.tr("Healthy"), 10.5f, AppTheme.success(), false));
        }

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, 0);
        btns.addView(actionButton(enabled ? "DISABLE" : "ENABLE", enabled ? AppTheme.warning() : AppTheme.success(), v -> {
            final JSONObject upd = copy(ix);
            put(upd, "enable", !enabled);
            action(api -> { api.updateIndexer(upd); return null; }, enabled ? "Indexer disabled." : "Indexer enabled.", () -> {
                indexerCache = null;
                refresh();
            });
        }), weight());
        btns.addView(actionButton("TEST", AppTheme.accent(), v ->
                action(api -> { api.testIndexer(ix); return null; }, "Test passed.", null)), weight());
        btns.addView(actionButton("EDIT", AppTheme.accent(), v -> showIndexerForm(ix, false, null)), weight());
        btns.addView(actionButton("DELETE", AppTheme.danger(), v ->
                confirm(I18n.tr("Delete this indexer from Prowlarr?") + "\n" + ix.optString("name"), I18n.tr("DELETE"), AppTheme.danger(), () ->
                        action(api -> { api.deleteIndexer(ix.optInt("id")); return null; }, "Deleted.", () -> {
                            indexerCache = null;
                            refresh();
                        }))), weight());
        box.addView(btns);
        return box;
    }

    // ------------------------------------------------------------------ add indexer (schema picker)

    private void showAddIndexer() {
        final LinearLayout body = dialogBody(I18n.tr("Add indexer"));
        final EditText term = new EditText(act);
        term.setHint(I18n.tr("Search indexer definitions"));
        term.setHintTextColor(AppTheme.textMuted());
        term.setTextColor(AppTheme.text());
        term.setTextSize(13f);
        term.setSingleLine(true);
        body.addView(term);
        final LinearLayout results = new LinearLayout(act);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(act);
        sv.addView(results);
        body.addView(sv, new LinearLayout.LayoutParams(-1, dp(400)));
        final Dialog d = dialog(body);

        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            results.removeAllViews();
            if (schemaCache == null) return;
            String f = term.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < schemaCache.length(); i++) {
                JSONObject s = schemaCache.optJSONObject(i);
                if (s == null) continue;
                String n = s.optString("name", s.optString("implementationName")).toLowerCase(Locale.ROOT);
                if (!f.isEmpty() && !n.contains(f)) continue;
                list.add(s);
            }
            Collections.sort(list, (a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
            if (list.isEmpty()) {
                results.addView(text(I18n.tr("No matches."), 12f, AppTheme.textSecondary(), false));
                return;
            }
            int n = 0;
            for (final JSONObject s : list) {
                if (++n > 80) break;
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(5);
                row.setLayoutParams(lp);
                row.addView(text(s.optString("name", s.optString("implementationName")), 12.5f, AppTheme.text(), true));
                String sub = s.optString("protocol", "").toUpperCase(Locale.ROOT);
                String pv = s.optString("privacy", "");
                if (!pv.isEmpty()) sub += (sub.isEmpty() ? "" : "  -  ") + capitalize(pv);
                row.addView(text(sub, 10.5f, AppTheme.textSecondary(), false));
                row.setClickable(true);
                row.setOnClickListener(v -> {
                    d.dismiss();
                    openNewIndexerForm(s);
                });
                results.addView(row);
            }
            if (list.size() > 80) {
                results.addView(text(I18n.tr("Refine your search to see more."), 10.5f, AppTheme.textMuted(), false));
            }
        };
        term.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { fill[0].run(); }
        });
        d.show();
        if (schemaCache != null) {
            fill[0].run();
        } else {
            results.addView(text(I18n.tr("Loading..."), 12f, AppTheme.textSecondary(), false));
            async(api -> api.indexerSchema(), arr -> {
                schemaCache = arr;
                fill[0].run();
            }, t -> {
                results.removeAllViews();
                results.addView(text(msg(t), 12f, AppTheme.danger(), false));
            });
        }
    }

    private void openNewIndexerForm(final JSONObject schemaItem) {
        async(api -> api.appProfiles(), arr -> showIndexerForm(schemaItem, true, arr),
                t -> showIndexerForm(schemaItem, true, null));
    }

    // ------------------------------------------------------------------ add / edit form

    private void showIndexerForm(JSONObject src, final boolean isNew, JSONArray profiles) {
        final JSONObject ix = copy(src);
        if (isNew) {
            put(ix, "enable", true);
            if (ix.optString("name", "").isEmpty()) put(ix, "name", ix.optString("implementationName"));
            if (ix.optInt("priority", 0) <= 0) put(ix, "priority", 25);
            if (ix.optJSONArray("tags") == null) put(ix, "tags", new JSONArray());
        }
        JSONArray fl = ix.optJSONArray("fields");
        final JSONArray fields = fl == null ? new JSONArray() : fl;

        final LinearLayout body = dialogBody(I18n.tr(isNew ? "Add indexer" : "Edit indexer"));
        final LinearLayout form = new LinearLayout(act);
        form.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(act);
        sv.addView(form);
        body.addView(sv, new LinearLayout.LayoutParams(-1, dp(400)));

        form.addView(textField(I18n.tr("Name"), "", ix.optString("name"), InputType.TYPE_CLASS_TEXT,
                s -> put(ix, "name", s)));
        form.addView(switchField(I18n.tr("Enable"), ix.optBoolean("enable", true), b -> put(ix, "enable", b)));
        form.addView(textField(I18n.tr("Priority") + " (1-50)", "", String.valueOf(ix.optInt("priority", 25)),
                InputType.TYPE_CLASS_NUMBER, s -> {
                    try {
                        put(ix, "priority", Integer.parseInt(s.trim()));
                    } catch (NumberFormatException ignored) {
                        // keep previous value
                    }
                }));
        if (profiles != null && profiles.length() > 0) {
            final JSONArray profs = profiles;
            String[] names = new String[profs.length()];
            int cur = 0;
            boolean found = false;
            for (int i = 0; i < profs.length(); i++) {
                JSONObject p = profs.optJSONObject(i);
                names[i] = p == null ? "?" : p.optString("name");
                if (p != null && p.optInt("id") == ix.optInt("appProfileId", -1)) { cur = i; found = true; }
            }
            if (!found) {
                JSONObject p0 = profs.optJSONObject(0);
                if (p0 != null) put(ix, "appProfileId", p0.optInt("id"));
            }
            form.addView(pickField(I18n.tr("Sync profile"), names, cur, which -> {
                JSONObject p = profs.optJSONObject(which);
                if (p != null) put(ix, "appProfileId", p.optInt("id"));
            }));
        }

        final LinearLayout fieldsBox = new LinearLayout(act);
        fieldsBox.setOrientation(LinearLayout.VERTICAL);
        form.addView(fieldsBox);
        final boolean[] adv = {false};
        final Runnable build = () -> {
            fieldsBox.removeAllViews();
            for (int i = 0; i < fields.length(); i++) {
                JSONObject f = fields.optJSONObject(i);
                if (f == null) continue;
                if (f.optBoolean("advanced") && !adv[0]) continue;
                View v = fieldView(f);
                if (v != null) fieldsBox.addView(v);
            }
        };
        build.run();
        boolean hasAdv = false;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.optJSONObject(i);
            if (f != null && f.optBoolean("advanced")) hasAdv = true;
        }
        if (hasAdv) {
            final Button advBtn = actionButton("SHOW ADVANCED", AppTheme.textSecondary(), null);
            advBtn.setOnClickListener(v -> {
                adv[0] = !adv[0];
                advBtn.setText(I18n.tr(adv[0] ? "HIDE ADVANCED" : "SHOW ADVANCED"));
                build.run();
            });
            form.addView(advBtn, lpTop(dp(34), dp(10)));
        }

        final Dialog d = dialog(body);
        LinearLayout row = buttonRow();
        row.addView(actionButton("CANCEL", AppTheme.textSecondary(), v -> d.dismiss()), weight());
        row.addView(actionButton("TEST", AppTheme.accent(), v ->
                action(api -> { api.testIndexer(ix); return null; }, "Test passed.", null)), weight());
        row.addView(actionButton("SAVE", AppTheme.success(), v ->
                action(api -> {
                    if (isNew) api.addIndexer(ix); else api.updateIndexer(ix);
                    return null;
                }, "Saved.", () -> {
                    d.dismiss();
                    indexerCache = null;
                    refresh();
                })), weight());
        body.addView(row);
        d.show();
    }

    /** Builds the editor for one schema field; returns null for field types that cannot be edited here. */
    private View fieldView(final JSONObject f) {
        String hidden = f.optString("hidden", "normal");
        if ("hidden".equals(hidden)) return null;
        String type = f.optString("type", "textbox");
        String label = str(f.opt("label")).isEmpty() ? f.optString("name") : str(f.opt("label"));
        String help = str(f.opt("helpText"));
        Object val = f.opt("value");

        if ("info".equals(type) || "captcha".equals(type) || "oAuth".equals(type) || "tagSelect".equals(type)) return null;
        if (val instanceof JSONArray || val instanceof JSONObject && val != JSONObject.NULL) {
            JSONArray so = f.optJSONArray("selectOptions");
            if (!"select".equals(type) || so == null) return null;
        }

        if ("checkbox".equals(type)) {
            boolean on = val instanceof Boolean ? (Boolean) val : "true".equalsIgnoreCase(str(val));
            View v = switchField(label, on, b -> put(f, "value", b));
            return withHelp(v, help);
        }

        JSONArray opts = f.optJSONArray("selectOptions");
        if ("select".equals(type) && opts != null && opts.length() > 0) {
            final JSONArray options = opts;
            String[] names = new String[options.length()];
            int idx = -1;
            for (int i = 0; i < options.length(); i++) {
                JSONObject o = options.optJSONObject(i);
                names[i] = o == null ? "?" : o.optString("name", str(o.opt("value")));
                if (o != null && str(o.opt("value")).equals(str(val))) idx = i;
            }
            if (idx < 0 && str(val).isEmpty()) {
                idx = 0;
                JSONObject o0 = options.optJSONObject(0);
                if (o0 != null) put(f, "value", o0.opt("value"));
            }
            View v = pickField(label, names, idx, which -> {
                JSONObject o = options.optJSONObject(which);
                if (o != null) put(f, "value", o.opt("value"));
            });
            return withHelp(v, help);
        }

        final boolean numeric = "number".equals(type) || val instanceof Number;
        int inputType = InputType.TYPE_CLASS_TEXT;
        if ("password".equals(type)) inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        else if (numeric) inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        else if ("url".equals(type)) inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
        return textField(label, help, str(val), inputType, s -> {
            if (numeric) {
                String t = s.trim();
                if (t.isEmpty()) {
                    put(f, "value", null);
                    return;
                }
                try {
                    put(f, "value", Long.valueOf(Long.parseLong(t)));
                } catch (NumberFormatException e) {
                    try {
                        put(f, "value", Double.valueOf(Double.parseDouble(t)));
                    } catch (NumberFormatException e2) {
                        // ignore partial input such as "-"
                    }
                }
            } else {
                put(f, "value", s);
            }
        });
    }

    private View withHelp(View v, String help) {
        if (help.isEmpty()) return v;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(v);
        box.addView(text(help, 10f, AppTheme.textMuted(), false));
        return box;
    }

    private View textField(String label, String help, String value, int inputType, final TextSink sink) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        box.addView(text(label, 10.5f, AppTheme.textMuted(), false));
        EditText e = new EditText(act);
        e.setHintTextColor(AppTheme.textMuted());
        e.setTextColor(AppTheme.text());
        e.setTextSize(13f);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(value);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable ed) { sink.set(ed.toString()); }
        });
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        if (help != null && !help.isEmpty()) box.addView(text(help, 10f, AppTheme.textMuted(), false));
        return box;
    }

    private View switchField(String label, boolean value, final BoolSink sink) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        box.addView(text(label, 12.5f, AppTheme.text(), false), new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch sw = new Switch(act);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, checked) -> sink.set(checked));
        box.addView(sw);
        return box;
    }

    private View pickField(String label, final String[] options, int selected, final IntSink sink) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        box.addView(text(label, 10.5f, AppTheme.textMuted(), false));
        final TextView value = text(selected >= 0 && selected < options.length ? options[selected] : "-", 13f, AppTheme.text(), false);
        value.setPadding(dp(10), dp(9), dp(10), dp(9));
        value.setBackground(rounded(AppTheme.inner(), AppTheme.outline(), 8));
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        value.setOnClickListener(v -> new AlertDialog.Builder(act).setItems(options, (dd, which) -> {
            value.setText(options[which]);
            sink.set(which);
        }).show());
        box.addView(value);
        return box;
    }

    // ================================================================== SEARCH

    private void renderSearch() {
        ++requestCounter;
        listContainer.removeAllViews();
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(4));
        final EditText q = new EditText(act);
        q.setHint(I18n.tr("Search all indexers"));
        q.setHintTextColor(AppTheme.textMuted());
        q.setTextColor(AppTheme.text());
        q.setTextSize(13f);
        q.setSingleLine(true);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setText(searchQuery);
        q.setSelection(q.getText().length());
        bar.addView(q, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final Runnable go = () -> {
            searchQuery = q.getText().toString().trim();
            runSearch();
        };
        q.setOnEditorActionListener((v, actionId, ev) -> { go.run(); return true; });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, dp(36));
        blp.setMarginStart(dp(8));
        bar.addView(actionButton("SEARCH", AppTheme.accent(), v -> go.run()), blp);
        listContainer.addView(bar);

        final LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(4), 0, dp(4));
        HorizontalScrollView chipScroll = new HorizontalScrollView(act);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        listContainer.addView(chipScroll);
        final Runnable[] drawChips = new Runnable[1];
        drawChips[0] = () -> {
            chips.removeAllViews();
            for (int i = 0; i < CAT_NAMES.length; i++) {
                final int idx = i;
                TextView c = pill(CAT_NAMES[i], idx == searchCat);
                c.setOnClickListener(v -> {
                    searchCat = idx;
                    drawChips[0].run();
                    searchQuery = q.getText().toString().trim();
                    if (!searchQuery.isEmpty()) runSearch();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(30));
                lp.setMarginEnd(dp(6));
                chips.addView(c, lp);
            }
        };
        drawChips[0].run();

        searchBox = new LinearLayout(act);
        searchBox.setOrientation(LinearLayout.VERTICAL);
        listContainer.addView(searchBox);
        if (searchResults != null) {
            fillSearchResults();
        } else {
            TextView hint = text(I18n.tr("Type a title and search across all enabled indexers."), 12f, AppTheme.textSecondary(), false);
            hint.setPadding(0, dp(12), 0, 0);
            searchBox.addView(hint);
        }
    }

    private void runSearch() {
        final String q = searchQuery;
        if (q.isEmpty() || searchBox == null) return;
        final int req = ++requestCounter;
        final LinearLayout box = searchBox;
        box.removeAllViews();
        TextView loading = text(I18n.tr("Searching indexers... this can take up to a minute."), 12f, AppTheme.textSecondary(), false);
        loading.setPadding(0, dp(12), 0, 0);
        box.addView(loading);
        final int cat = CAT_IDS[searchCat];
        async(api -> api.search(q, "search", null, cat > 0 ? new int[]{cat} : null), arr -> {
            if (req != requestCounter || section != S_SEARCH) return;
            searchResults = arr;
            fillSearchResults();
        }, t -> {
            if (req != requestCounter) return;
            box.removeAllViews();
            box.addView(text(msg(t), 12f, AppTheme.danger(), false));
        });
    }

    private void fillSearchResults() {
        searchBox.removeAllViews();
        if (searchResults == null) return;
        if (searchResults.length() == 0) {
            TextView t = text(I18n.tr("No releases found."), 12f, AppTheme.textSecondary(), false);
            t.setPadding(0, dp(12), 0, 0);
            searchBox.addView(t);
            return;
        }
        List<JSONObject> rel = new ArrayList<>();
        for (int i = 0; i < searchResults.length(); i++) {
            JSONObject r = searchResults.optJSONObject(i);
            if (r != null) rel.add(r);
        }
        Collections.sort(rel, (a, b) -> {
            int c = b.optInt("seeders") - a.optInt("seeders");
            if (c != 0) return c;
            return Long.compare(b.optLong("size"), a.optLong("size"));
        });
        TextView count = text(rel.size() + " " + I18n.tr("results"), 11f, AppTheme.textSecondary(), false);
        count.setPadding(0, dp(4), 0, dp(2));
        searchBox.addView(count);
        int n = 0;
        for (JSONObject r : rel) {
            if (++n > 100) break;
            searchBox.addView(releaseRow(r));
        }
    }

    private View releaseRow(final JSONObject r) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        box.setLayoutParams(lp);
        TextView t = text(r.optString("title"), 11.5f, AppTheme.text(), true);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t);
        String proto = r.optString("protocol", "");
        double ageMin = r.has("ageMinutes") ? r.optDouble("ageMinutes", 0) : r.optDouble("age", 0) * 1440.0;
        String line = r.optString("indexer", "") + "  -  " + fmtSize(r.optLong("size"))
                + ("torrent".equalsIgnoreCase(proto) ? "  -  S:" + r.optInt("seeders") + " L:" + r.optInt("leechers") : "  -  " + proto)
                + "  -  " + fmtAge(ageMin);
        TextView l = text(line, 10f, AppTheme.textSecondary(), false);
        l.setPadding(0, dp(2), 0, dp(2));
        box.addView(l);
        final Runnable grab = () -> confirm(I18n.tr("Send this release to the download client?") + "\n" + r.optString("title"),
                I18n.tr("GRAB"), AppTheme.success(), () ->
                        action(api -> { api.grab(r); return null; }, "Sent to the download client.", null));
        box.addView(actionButton("GRAB", AppTheme.success(), v -> grab.run()), new LinearLayout.LayoutParams(-1, dp(34)));
        box.setClickable(true);
        box.setOnClickListener(v -> grab.run());
        return box;
    }

    // ================================================================== APPS

    private void loadApps() {
        final int req = ++requestCounter;
        showMessage("Loading...");
        async(api -> api.applications(), arr -> {
            if (req != requestCounter || section != S_APPS) return;
            listContainer.removeAllViews();
            LinearLayout bar = new LinearLayout(act);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setPadding(0, dp(8), 0, dp(4));
            bar.addView(actionButton("SYNC NOW", AppTheme.accent(), v ->
                    action(api2 -> { api2.simpleCommand("ApplicationIndexerSync"); return null; }, "Indexer sync queued.", null)), weight());
            bar.addView(actionButton("REFRESH", AppTheme.textSecondary(), v -> refresh()), weight());
            listContainer.addView(bar);
            if (arr.length() == 0) {
                TextView t = text(I18n.tr("No applications configured. Add Sonarr / Radarr in Prowlarr's web interface (Settings > Apps)."),
                        12f, AppTheme.textSecondary(), false);
                t.setPadding(0, dp(12), 0, 0);
                listContainer.addView(t);
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a != null) listContainer.addView(appRow(a));
            }
        }, t -> { if (req == requestCounter) showError(t); });
    }

    private View appRow(final JSONObject app) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        box.setLayoutParams(lp);
        box.addView(text(app.optString("name"), 13f, AppTheme.text(), true));
        String impl = app.optString("implementationName", app.optString("implementation"));
        String sync = app.optString("syncLevel", "");
        box.addView(text(impl + (sync.isEmpty() ? "" : "  -  " + I18n.tr("Sync") + ": " + syncLabel(sync)), 10.5f, AppTheme.textSecondary(), false));
        JSONArray fl = app.optJSONArray("fields");
        for (int i = 0; fl != null && i < fl.length(); i++) {
            JSONObject f = fl.optJSONObject(i);
            if (f != null && ("baseUrl".equals(f.optString("name")) || "prowlarrUrl".equals(f.optString("name")))
                    && !str(f.opt("value")).isEmpty()) {
                box.addView(text(f.optString("name") + ": " + str(f.opt("value")), 10f, AppTheme.textMuted(), false));
            }
        }
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, 0);
        btns.addView(actionButton("TEST", AppTheme.accent(), v ->
                action(api -> { api.testApplication(app); return null; }, "Test passed.", null)), weight());
        btns.addView(actionButton("DELETE", AppTheme.danger(), v ->
                confirm(I18n.tr("Remove this application from Prowlarr?") + "\n" + app.optString("name"), I18n.tr("DELETE"), AppTheme.danger(), () ->
                        action(api -> { api.deleteApplication(app.optInt("id")); return null; }, "Deleted.", this::refresh))), weight());
        box.addView(btns);
        return box;
    }

    private static String syncLabel(String level) {
        if ("fullSync".equals(level)) return I18n.tr("Full sync");
        if ("addOnly".equals(level)) return I18n.tr("Add and remove only");
        if ("disabled".equals(level)) return I18n.tr("Disabled");
        return level;
    }

    // ================================================================== STATS

    private void loadStats() {
        final int req = ++requestCounter;
        showMessage("Loading...");
        async(api -> api.indexerStats(), o -> {
            if (req != requestCounter || section != S_STATS) return;
            listContainer.removeAllViews();
            JSONArray arr = o.optJSONArray("indexers");
            if (arr == null || arr.length() == 0) { showMessage("No statistics yet."); return; }
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null) list.add(s);
            }
            Collections.sort(list, (a, b) -> b.optInt("numberOfQueries") - a.optInt("numberOfQueries"));
            LinearLayout card = sysCard("INDEXER STATISTICS");
            card.addView(statRow("INDEXER", "QUERIES", "GRABS", "FAILED", "AVG", AppTheme.textMuted(), true));
            long tq = 0, tg = 0, tf = 0;
            for (JSONObject s : list) {
                int id = s.optInt("indexerId");
                String name = s.optString("indexerName", "");
                if (name.isEmpty()) name = indexerNames.containsKey(id) ? indexerNames.get(id) : "#" + id;
                int q = s.optInt("numberOfQueries") + s.optInt("numberOfRssQueries");
                int g = s.optInt("numberOfGrabs");
                int fl = s.optInt("numberOfFailedQueries") + s.optInt("numberOfFailedGrabs");
                tq += q; tg += g; tf += fl;
                int avg = (int) s.optDouble("averageResponseTime", 0);
                card.addView(statRow(name, String.valueOf(q), String.valueOf(g), String.valueOf(fl), avg + " ms",
                        fl > 0 ? AppTheme.danger() : AppTheme.text(), false));
            }
            card.addView(statRow("TOTAL", String.valueOf(tq), String.valueOf(tg), String.valueOf(tf), "", AppTheme.accent(), true));
        }, t -> { if (req == requestCounter) showError(t); });
    }

    private View statRow(String a, String b, String c, String d, String e, int color, boolean bold) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.addView(cell(bold ? I18n.tr(a) : a, 2.4f, color, bold, false), new LinearLayout.LayoutParams(0, -2, 2.4f));
        row.addView(cell(bold ? I18n.tr(b) : b, 1.3f, color, bold, true), new LinearLayout.LayoutParams(0, -2, 1.3f));
        row.addView(cell(bold ? I18n.tr(c) : c, 1.0f, color, bold, true), new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(cell(bold ? I18n.tr(d) : d, 1.0f, color, bold, true), new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(cell(bold ? I18n.tr(e) : e, 1.3f, color, bold, true), new LinearLayout.LayoutParams(0, -2, 1.3f));
        return row;
    }

    private TextView cell(String s, float w, int color, boolean bold, boolean right) {
        TextView t = text(s, 10.5f, color, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (right) t.setGravity(Gravity.END);
        return t;
    }

    // ================================================================== HISTORY

    private void loadHistory() {
        final int req = ++requestCounter;
        final int pg = page;
        showMessage("Loading...");
        async(api -> {
            JSONObject o = api.history(pg, PAGE_SIZE);
            if (indexerNames.isEmpty()) {
                try { rememberNames(api.indexers()); } catch (Throwable ignored) {}
            }
            return o;
        }, o -> {
            if (req != requestCounter || section != S_HISTORY) return;
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

    private View historyRow(JSONObject r) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        box.setBackground(rounded(AppTheme.card(), AppTheme.outline(), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(5);
        box.setLayoutParams(lp);
        int id = r.optInt("indexerId");
        String name = indexerNames.containsKey(id) ? indexerNames.get(id) : "#" + id;
        box.addView(text(name, 12.5f, AppTheme.text(), true));
        JSONObject data = r.optJSONObject("data");
        String ev = r.optString("eventType", "");
        boolean ok = r.optBoolean("successful", true);
        int color = ok ? AppTheme.textSecondary() : AppTheme.danger();
        String label = ev;
        if (ev.equals("releaseGrabbed")) { label = "Grabbed"; color = ok ? AppTheme.success() : AppTheme.danger(); }
        else if (ev.equals("indexerQuery")) label = "Query";
        else if (ev.equals("indexerRss")) label = "RSS";
        else if (ev.equals("indexerAuth")) label = "Auth";
        else if (ev.equals("indexerInfo")) label = "Info";
        box.addView(text(I18n.tr(label) + (ok ? "" : " - " + I18n.tr("failed")), 11f, color, true));
        if (data != null) {
            String detail = str(data.opt("query"));
            if (detail.isEmpty()) detail = str(data.opt("title"));
            if (detail.isEmpty()) detail = str(data.opt("grabTitle"));
            String source = str(data.opt("source"));
            String extra = detail + (source.isEmpty() ? "" : (detail.isEmpty() ? "" : "  -  ") + source);
            if (!extra.isEmpty()) {
                TextView src = text(extra, 10f, AppTheme.textMuted(), false);
                src.setMaxLines(2);
                src.setEllipsize(TextUtils.TruncateAt.END);
                box.addView(src);
            }
        }
        box.addView(text(shortDate(r.optString("date", "")), 10f, AppTheme.textMuted(), false));
        return box;
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
            try { o.put("tasks", api.tasks()); } catch (Throwable ignored) {}
            try { o.put("clients", api.downloadClients()); } catch (Throwable ignored) {}
            try { o.put("tags", api.tags()); } catch (Throwable ignored) {}
            return o;
        }, o -> {
            if (req != requestCounter || section != S_SYSTEM) return;
            renderSystem(o);
        }, t -> { if (req == requestCounter) showError(t); });
    }

    private void renderSystem(JSONObject o) {
        listContainer.removeAllViews();
        JSONObject st = o.optJSONObject("status");
        LinearLayout c = sysCard("STATUS");
        if (st != null) {
            c.addView(text("Prowlarr v" + st.optString("version") + "  -  " + st.optString("osName") + " " + st.optString("osVersion"), 12f, AppTheme.text(), false));
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

        c = sysCard("DOWNLOAD CLIENTS");
        addEnabledList(c, o.optJSONArray("clients"), "enable");

        JSONArray tags = o.optJSONArray("tags");
        if (tags != null && tags.length() > 0) {
            c = sysCard("TAGS");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tags.length(); i++) {
                JSONObject tg = tags.optJSONObject(i);
                if (tg == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(tg.optString("label"));
            }
            c.addView(text(sb.toString(), 11.5f, AppTheme.text(), false));
        }

        JSONArray tasks = o.optJSONArray("tasks");
        if (tasks != null && tasks.length() > 0) {
            c = sysCard("SCHEDULED TASKS");
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject tk = tasks.optJSONObject(i);
                if (tk == null) continue;
                TextView n = text(tk.optString("name", tk.optString("taskName")), 11.5f, AppTheme.text(), false);
                n.setPadding(0, dp(4), 0, 0);
                c.addView(n);
                c.addView(text(I18n.tr("Last") + ": " + shortDate(tk.optString("lastExecution", ""))
                        + "   " + I18n.tr("Next") + ": " + shortDate(tk.optString("nextExecution", "")), 10f, AppTheme.textMuted(), false));
            }
        }

        c = sysCard("COMMANDS");
        LinearLayout r1 = new LinearLayout(act);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(actionButton("SYNC APPS", AppTheme.accent(), v -> action(api -> { api.simpleCommand("ApplicationIndexerSync"); return null; }, "Indexer sync queued.", null)), weight());
        r1.addView(actionButton("CHECK HEALTH", AppTheme.accent(), v -> action(api -> { api.simpleCommand("CheckHealth"); return null; }, "Health check queued.", this::refresh)), weight());
        c.addView(r1);
        LinearLayout r2 = new LinearLayout(act);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(actionButton("BACKUP", AppTheme.accent(), v -> action(api -> { api.simpleCommand("Backup"); return null; }, "Backup queued.", null)), weight());
        r2.addView(actionButton("HOUSEKEEPING", AppTheme.accent(), v -> action(api -> { api.simpleCommand("Housekeeping"); return null; }, "Housekeeping queued.", null)), weight());
        c.addView(r2, lpTop(-2, dp(6)));
        TextView note = text(I18n.tr("Advanced settings are available in Prowlarr's own web interface."), 10.5f, AppTheme.textMuted(), false);
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
