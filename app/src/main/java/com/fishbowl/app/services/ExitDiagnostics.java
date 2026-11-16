package com.fishbowl.app.services;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.fishbowl.app.model.ManagedService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Records why the previous app process ended (Java crash, system kill, low memory...) and prints
 * it in the Jellyfin console log on the next launch.
 */
public final class ExitDiagnostics {

    private static final String TAG = "ExitDiagnostics";
    private static final String CRASH_FILE = "last_java_crash.txt";

    private ExitDiagnostics() {}

    public static void install(final Context ctx, final MediaServiceManager mgr) {
        final Context app = ctx.getApplicationContext();
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    StringWriter sw = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(sw));
                    String text = "Thread: " + thread.getName() + "\n" + sw;
                    try (FileOutputStream fos = new FileOutputStream(new File(app.getFilesDir(), CRASH_FILE))) {
                        fos.write(text.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Throwable ignored) {}
                if (previous != null) previous.uncaughtException(thread, throwable);
            });
        } catch (Throwable t) {
            Log.w(TAG, "Could not install crash handler: " + t.getMessage());
        }

        new Thread(() -> report(app, mgr), "Fishbowl-ExitDiagnostics").start();
    }

    private static void report(Context app, MediaServiceManager mgr) {
        try {
            Thread.sleep(3000L);
            ManagedService sink = mgr.getService("jellyfin");
            if (sink == null) return;

            File crash = new File(app.getFilesDir(), CRASH_FILE);
            if (crash.exists()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (FileInputStream fis = new FileInputStream(crash)) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = fis.read(b)) != -1) bos.write(b, 0, n);
                }
                String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                sink.appendLog("[ExitDiagnostics] Previous run ended with a JAVA CRASH:");
                for (String line : text.split("\n")) sink.appendLog("[ExitDiagnostics]   " + line);
                crash.renameTo(new File(app.getFilesDir(), "last_java_crash_prev.txt"));
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(app.getPackageName(), 0, 4);
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                for (ApplicationExitInfo i : list) {
                    sink.appendLog("[ExitDiagnostics] Process exit @ " + fmt.format(new Date(i.getTimestamp()))
                            + " | reason=" + reasonName(i.getReason())
                            + " | status=" + i.getStatus()
                            + " | importance=" + i.getImportance()
                            + " | pss=" + (i.getPss() / 1024) + "MB rss=" + (i.getRss() / 1024) + "MB"
                            + " | process=" + i.getProcessName()
                            + " | desc=" + i.getDescription());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "report failed: " + t.getMessage());
        }
    }

    private static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_CRASH: return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY(lmkd)";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_UNKNOWN: return "UNKNOWN";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            default: return "CODE_" + r;
        }
    }
}
