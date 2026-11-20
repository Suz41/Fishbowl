package com.fishbowl.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;

/** Vivid ON colour for switches and dirty-aware SAVE buttons. */
public final class UiStyle {
    private UiStyle() {}

    public interface StringSource { String get(); }

    private static final int ON = 0xFF38BDF8;

    private static ColorStateList states(int checked, int unchecked) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{checked, unchecked});
    }

    public static void styleSwitch(Context c, Switch s) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            s.setThumbTintList(states(0xFFFFFFFF, 0xFF9CA3AF));
            s.setTrackTintList(states(ON, 0xFF3F3F46));
        } catch (Throwable ignored) {}
    }

    public static void styleAllSwitches(Context c, View root) {
        if (root instanceof Switch) { styleSwitch(c, (Switch) root); return; }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) styleAllSwitches(c, g.getChildAt(i));
        }
    }

    /** SAVE is dimmed/disabled while the field equals the stored value. Returns a re-evaluate Runnable. */
    public static Runnable bindDirty(final Button save, final EditText input, final StringSource saved) {
        final Runnable update = new Runnable() {
            @Override public void run() {
                boolean dirty;
                try { dirty = !input.getText().toString().trim().equals(String.valueOf(saved.get()).trim()); }
                catch (Throwable t) { dirty = true; }
                save.setEnabled(dirty);
                save.setAlpha(dirty ? 1f : 0.35f);
            }
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { update.run(); }
        });
        update.run();
        return update;
    }
}
