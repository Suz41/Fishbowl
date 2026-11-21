package com.fishbowl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime English -> Arabic localisation for the programmatic UI.
 *
 * The UI is built in code with English literals, so instead of thousands of R.string lookups the app
 * ships a dictionary ({@code assets/i18n_ar.json}: exact strings + templates with {0},{1} placeholders)
 * and this class translates text when it is shown:
 *  - {@link #attach(View)} translates a view tree, and keeps translating views added later and text
 *    that is changed later (via a TextWatcher), so dynamic status text is covered too;
 *  - {@link #tr(CharSequence)} translates a string directly (Toasts, AlertDialog texts).
 * Anything without a translation stays English. Language choice: System / English / Arabic.
 * {@link #wrap(Context)} also sets the layout direction (RTL for Arabic).
 */
public final class I18n {
    private I18n() {}

    public static final String LANG_SYSTEM = "system";
    public static final String LANG_EN = "en";
    public static final String LANG_AR = "ar";

    private static final String PREFS = "fishbowl_settings";
    private static final String KEY_LANG = "app_language";

    private static volatile boolean arabic = false;
    private static String choice = LANG_SYSTEM;
    private static boolean loaded = false;
    private static final Map<String, String> EXACT = new HashMap<>();
    private static final List<Template> TEMPLATES = new ArrayList<>();
    private static final Map<String, String> CACHE = new HashMap<>();
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");

    private static final Map<View, Boolean> HIER = Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static final Map<View, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static boolean guard = false;

    private static final class Template {
        final Pattern pattern;
        final String ar;
        final String prefix;
        final int groups;

        Template(Pattern pattern, String ar, String prefix, int groups) {
            this.pattern = pattern;
            this.ar = ar;
            this.prefix = prefix;
            this.groups = groups;
        }
    }

    // ---- language choice -----------------------------------------------------------------

    /** Reads the saved language and (if Arabic) loads the dictionary. Cheap after the first call. */
    public static synchronized void init(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String c = sp.getString(KEY_LANG, LANG_SYSTEM);
        if (!LANG_EN.equals(c) && !LANG_AR.equals(c)) c = LANG_SYSTEM;
        choice = c;
        if (LANG_AR.equals(c)) {
            arabic = true;
        } else if (LANG_EN.equals(c)) {
            arabic = false;
        } else {
            arabic = "ar".equals(Locale.getDefault().getLanguage());
        }
        if (arabic) load(app);
    }

    public static String getChoice() { return choice; }

    public static boolean isArabic() { return arabic; }

    public static void setLanguage(Context ctx, String value) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANG, value).apply();
        init(ctx);
    }

    /**
     * Use from Activity.attachBaseContext(): forces the locale (and layout direction) of the chosen
     * language so RTL mirroring works. With "System" the context is returned untouched.
     */
    public static Context wrap(Context base) {
        init(base);
        if (LANG_SYSTEM.equals(choice)) return base;
        try {
            Locale loc = arabic ? new Locale("ar") : Locale.ENGLISH;
            Locale.setDefault(loc);
            Configuration c = new Configuration(base.getResources().getConfiguration());
            c.setLocale(loc);
            c.setLayoutDirection(loc);
            return base.createConfigurationContext(c);
        } catch (Throwable t) {
            return base;
        }
    }

    // ---- dictionary ----------------------------------------------------------------------

    private static synchronized void load(Context app) {
        if (loaded) return;
        try {
            InputStream in = app.getAssets().open("i18n_ar.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONObject ex = root.optJSONObject("exact");
            if (ex != null) {
                Iterator<String> it = ex.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    EXACT.put(k, ex.getString(k));
                }
            }
            JSONObject tp = root.optJSONObject("templates");
            if (tp != null) {
                Iterator<String> it = tp.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Template t = compile(k, tp.getString(k));
                    if (t != null) TEMPLATES.add(t);
                }
                // Longest literal part first so specific templates win over generic ones.
                Collections.sort(TEMPLATES, (a, b) -> b.pattern.pattern().length() - a.pattern.pattern().length());
            }
            loaded = true;
        } catch (Throwable ignored) {
            // No dictionary -> everything stays English.
        }
    }

    private static Template compile(String en, String ar) {
        Matcher m = PLACEHOLDER.matcher(en);
        StringBuilder rx = new StringBuilder("^");
        int last = 0;
        int groups = 0;
        String prefix = null;
        while (m.find()) {
            String lit = en.substring(last, m.start());
            if (prefix == null) prefix = lit;
            rx.append(Pattern.quote(lit)).append("(.*?)");
            last = m.end();
            groups++;
        }
        if (groups == 0) return null;
        if (prefix == null) prefix = "";
        rx.append(Pattern.quote(en.substring(last))).append("$");
        try {
            return new Template(Pattern.compile(rx.toString(), Pattern.DOTALL), ar, prefix, groups);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- translation ---------------------------------------------------------------------

    /** Translates a string; returns the input unchanged when not Arabic or no translation exists. */
    public static String tr(CharSequence en) {
        if (en == null) return null;
        String s = en.toString();
        if (!arabic) return s;
        String t = translate(s, 0);
        return t != null ? t : s;
    }

    /** Returns the translation or null when nothing matched. */
    private static String translate(String s, int depth) {
        if (s == null || s.length() < 2 || depth > 3) return null;
        if (!LATIN.matcher(s).find()) return null;
        synchronized (I18n.class) {
            String hit = EXACT.get(s);
            if (hit != null) return hit;
            hit = CACHE.get(s);
            if (hit != null) return hit.isEmpty() ? null : hit;
        }
        String out = translateUncached(s, depth);
        synchronized (I18n.class) {
            if (CACHE.size() > 600) CACHE.clear();
            CACHE.put(s, out == null ? "" : out);
        }
        return out;
    }

    private static String translateUncached(String s, int depth) {
        // 1) same text without surrounding whitespace
        String trimmed = s.trim();
        if (!trimmed.equals(s) && !trimmed.isEmpty()) {
            int a = s.indexOf(trimmed);
            String t = translate(trimmed, depth + 1);
            if (t != null) return s.substring(0, a) + t + s.substring(a + trimmed.length());
        }
        // 2) templates ("Error: {0}")
        for (Template tp : TEMPLATES) {
            if (!tp.prefix.isEmpty() && !s.startsWith(tp.prefix)) continue;
            Matcher m = tp.pattern.matcher(s);
            if (!m.matches()) continue;
            String[] vals = new String[tp.groups];
            for (int i = 0; i < tp.groups; i++) {
                String g = m.group(i + 1);
                String tg = g == null ? null : translate(g, depth + 1);
                vals[i] = tg != null ? tg : g;
            }
            Matcher pm = PLACEHOLDER.matcher(tp.ar);
            StringBuffer sb = new StringBuffer();
            while (pm.find()) {
                int idx = Integer.parseInt(pm.group(1));
                String v = idx < vals.length && vals[idx] != null ? vals[idx] : "";
                pm.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
            pm.appendTail(sb);
            return sb.toString();
        }
        // 3) multi-line text: translate line by line
        if (s.indexOf('\n') >= 0) {
            String[] lines = s.split("\n", -1);
            boolean any = false;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append('\n');
                String t = translate(lines[i], depth + 1);
                if (t != null) { sb.append(t); any = true; } else sb.append(lines[i]);
            }
            return any ? sb.toString() : null;
        }
        return null;
    }

    // ---- view trees ----------------------------------------------------------------------

    private static final ViewGroup.OnHierarchyChangeListener ADDED = new ViewGroup.OnHierarchyChangeListener() {
        @Override public void onChildViewAdded(View parent, View child) { process(child); }
        @Override public void onChildViewRemoved(View parent, View child) { }
    };

    /** Translates {@code root} now and keeps translating views/text added or changed later. */
    public static <T extends View> T attach(T root) {
        if (arabic && root != null) {
            try { process(root); } catch (Throwable ignored) { }
        }
        return root;
    }

    private static void process(View v) {
        if (v instanceof TextView) translateTextView((TextView) v);
        else translateDescription(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (HIER.put(g, Boolean.TRUE) == null) g.setOnHierarchyChangeListener(ADDED);
            for (int i = 0; i < g.getChildCount(); i++) process(g.getChildAt(i));
        }
    }

    private static void translateDescription(View v) {
        CharSequence d = v.getContentDescription();
        if (d != null) {
            String t = translate(d.toString(), 0);
            if (t != null) v.setContentDescription(t);
        }
    }

    private static boolean isPlain(CharSequence cs) {
        if (!(cs instanceof Spanned)) return true;
        Spanned sp = (Spanned) cs;
        return sp.getSpans(0, sp.length(), Object.class).length == 0;
    }

    private static void translateTextView(final TextView tv) {
        translateDescription(tv);
        CharSequence hint = tv.getHint();
        if (hint != null && isPlain(hint)) {
            String t = translate(hint.toString(), 0);
            if (t != null) tv.setHint(t);
        }
        if (tv instanceof EditText) return; // never touch what the user types
        applyText(tv);
        if (WATCHED.put(tv, Boolean.TRUE) == null) {
            tv.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable e) { applyText(tv); }
            });
        }
    }

    private static void applyText(TextView tv) {
        if (guard) return;
        CharSequence cs = tv.getText();
        if (cs == null || cs.length() < 2 || cs.length() > 4000 || !isPlain(cs)) return;
        String s = cs.toString();
        String t = translate(s, 0);
        if (t != null && !t.equals(s)) {
            guard = true;
            try { tv.setText(t); } finally { guard = false; }
        }
    }
}
