package com.fishbowl.app.importer;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.fishbowl.app.MediaStorageManager;
import com.termux.R;
import com.fishbowl.app.StorageDriveHelper;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated Media Importer Activity (Movies & TV Shows).
 * Harmonized with Fishbowl Pixel Dark UI tokens, pinned header bar,
 * real-time Season/Episode detection, MOVE (cut & delete) operations,
 * and live source-to-destination path tracking.
 */
public final class MovieImportActivity extends AppCompatActivity {

    private static final String TAG = "MovieImportActivity";

    // Design Tokens (Harmonized with FishbowlActivity)
    private static final int COLOR_BG = Color.parseColor("#000000");
    private static final int COLOR_SURFACE = Color.parseColor("#0C0C0E");
    private static final int COLOR_ELEVATED = Color.parseColor("#15161A");
    private static final int COLOR_BORDER = Color.argb(20, 255, 255, 255);
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#F4F4F5");
    private static final int COLOR_TEXT_MUTED = Color.parseColor("#71717A");
    private static final int COLOR_ACCENT = Color.parseColor("#38BDF8");
    private static final int COLOR_SUCCESS = Color.parseColor("#34D399");
    private static final int COLOR_WARNING = Color.parseColor("#FFD51E");
    private static final int COLOR_DANGER = Color.parseColor("#EF4444");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();

    private MovieImportJournal journal;
    private MovieImportManager importManager;
    private DownloadDetectionManager detectionManager;
    private TmdbMetadataProvider metadataProvider;

    private LinearLayout scannedFilesContainer;
    private LinearLayout historyContainer;
    private TextView detectionStatusBadge;
    private Switch detectionSwitch;

    // Pickers
    private final ActivityResultLauncher<String[]> videoFilePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    handlePickedVideoUri(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        journal = MovieImportJournal.getInstance(this);
        importManager = MovieImportManager.getInstance(this);
        detectionManager = DownloadDetectionManager.getInstance(this);
        metadataProvider = new TmdbMetadataProvider();

        configureSystemBars();
        setContentView(buildRootLayout());

        // Handle incoming review intent if opened from download notification
        handleIncomingIntent(getIntent());

        // Auto scan downloads on opening
        scanDownloadsDirectory();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri != null) {
            handlePickedVideoUri(uri);
        }
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(COLOR_BG);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
        }
    }

    private View buildRootLayout() {
        LinearLayout outerRoot = new LinearLayout(this);
        outerRoot.setOrientation(LinearLayout.VERTICAL);
        outerRoot.setBackgroundColor(COLOR_BG);

        // Apply status bar padding to outer container
        ViewCompat.setOnApplyWindowInsetsListener(outerRoot, (v, insets) -> {
            androidx.core.graphics.Insets sb = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            androidx.core.graphics.Insets nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(0, sb.top, 0, nb.bottom);
            return insets;
        });

        // 1. Pinned Header Bar (matching FishbowlActivity)
        outerRoot.addView(buildHeaderBar());

        // 2. Scrollable Body
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(36));

        // Primary Actions Dock (Choose Video & Scan Downloads)
        content.addView(buildPrimaryActionsCard());

        // Auto Detection Settings Card
        content.addView(buildDetectionSettingsCard());

        // Scanned Downloads Section
        content.addView(buildScannedDownloadsSection());

        // Recent Imports Section
        content.addView(buildHistorySection());

        scroll.addView(content);
        outerRoot.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        return outerRoot;
    }

    private View buildHeaderBar() {
        LinearLayout rootHeader = new LinearLayout(this);
        rootHeader.setOrientation(LinearLayout.VERTICAL);
        rootHeader.setBackgroundColor(COLOR_BG);
        rootHeader.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        // Back Button with touch target & ripple
        ImageView btnBack = new ImageView(this);
        btnBack.setImageResource(R.drawable.ic_back);
        btnBack.setColorFilter(COLOR_TEXT_PRIMARY);
        btnBack.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        btnBack.setPadding(dp(8), dp(8), dp(8), dp(8));
        btnBack.setClickable(true);
        btnBack.setFocusable(true);
        btnBack.setBackground(createButtonRippleDrawable(COLOR_ELEVATED, dp(18), COLOR_ACCENT));
        LinearLayout.LayoutParams pBack = new LinearLayout.LayoutParams(dp(38), dp(38));
        pBack.rightMargin = dp(12);
        btnBack.setLayoutParams(pBack);
        btnBack.setOnClickListener(v -> finish());
        header.addView(btnBack);

        // Fishbowl Logo Mark
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_fishbowl_logo);
        logo.setColorFilter(Color.WHITE);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        logoParams.rightMargin = dp(10);
        header.addView(logo, logoParams);

        // Title Column
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("MEDIA IMPORTER");
        title.setTextSize(13.5f);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(COLOR_TEXT_PRIMARY);
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText("MOVIES & TV SERIES");
        sub.setTextSize(9.5f);
        sub.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        sub.setTextColor(COLOR_TEXT_MUTED);
        titleCol.addView(sub);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tag = new TextView(this);
        tag.setText("JELLYFIN AUTO-ORG");
        tag.setTextSize(9.5f);
        tag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tag.setTextColor(COLOR_ACCENT);
        header.addView(tag);

        rootHeader.addView(header);

        // Hairline divider
        View border = new View(this);
        border.setBackgroundColor(Color.argb(12, 255, 255, 255));
        LinearLayout.LayoutParams pDiv = new LinearLayout.LayoutParams(-1, dp(1));
        pDiv.topMargin = dp(8);
        rootHeader.addView(border, pDiv);

        return rootHeader;
    }

    private View buildPrimaryActionsCard() {
        LinearLayout card = createCard(dp(16));

        TextView heading = new TextView(this);
        heading.setText("IMPORT MODES");
        heading.setTextSize(11f);
        heading.setLetterSpacing(0.08f);
        heading.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        heading.setTextColor(COLOR_TEXT_MUTED);
        heading.setPadding(0, 0, 0, dp(12));
        card.addView(heading);

        // Mode A: Manual Video File Picker
        Button btnChoose = createButton("CHOOSE VIDEO (MANUAL PICKER)", Color.WHITE, Color.BLACK);
        btnChoose.setOnClickListener(v -> {
            String[] mimeTypes = {"video/*", "application/x-matroska", "application/octet-stream"};
            videoFilePickerLauncher.launch(mimeTypes);
        });
        card.addView(btnChoose, new LinearLayout.LayoutParams(-1, dp(44)));

        View spacer = new View(this);
        card.addView(spacer, new LinearLayout.LayoutParams(-1, dp(10)));

        // Mode B: Scan Downloads Folder
        Button btnScan = createOutlineButton("SCAN DOWNLOADS FOR MEDIA", COLOR_ACCENT);
        btnScan.setOnClickListener(v -> scanDownloadsDirectory());
        card.addView(btnScan, new LinearLayout.LayoutParams(-1, dp(44)));

        // Library Roots Summary
        LinearLayout rootsCol = new LinearLayout(this);
        rootsCol.setOrientation(LinearLayout.VERTICAL);
        rootsCol.setPadding(0, dp(14), 0, 0);

        TextView lblRoots = new TextView(this);
        lblRoots.setText("Library Destinations:");
        lblRoots.setTextSize(10.5f);
        lblRoots.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblRoots.setTextColor(COLOR_TEXT_MUTED);
        rootsCol.addView(lblRoots);

        TextView lblMovies = new TextView(this);
        lblMovies.setText("• Movies: " + MediaStorageManager.getInstance(this).getMoviesDir().getAbsolutePath());
        lblMovies.setTextSize(10f);
        lblMovies.setTextColor(COLOR_TEXT_MUTED);
        lblMovies.setPadding(0, dp(2), 0, 0);
        rootsCol.addView(lblMovies);

        TextView lblTv = new TextView(this);
        lblTv.setText("• TV Series: " + MediaStorageManager.getInstance(this).getTvDir().getAbsolutePath());
        lblTv.setTextSize(10f);
        lblTv.setTextColor(COLOR_TEXT_MUTED);
        lblTv.setPadding(0, dp(2), 0, 0);
        rootsCol.addView(lblTv);

        card.addView(rootsCol);
        return card;
    }

    private View buildDetectionSettingsCard() {
        LinearLayout card = createCard(dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText("AUTO-DETECT NEW DOWNLOADS");
        label.setTextSize(11.5f);
        label.setLetterSpacing(0.06f);
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        label.setTextColor(COLOR_TEXT_PRIMARY);
        textCol.addView(label);

        TextView desc = new TextView(this);
        desc.setText("Notifies when a browser download completes. Never auto-moves files.");
        desc.setTextSize(10.5f);
        desc.setTextColor(COLOR_TEXT_MUTED);
        desc.setPadding(0, dp(2), 0, 0);
        textCol.addView(desc);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        detectionSwitch = new Switch(this);
        detectionSwitch.setChecked(detectionManager.isDetectionEnabled());
        detectionSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            detectionManager.setDetectionEnabled(isChecked);
            updateDetectionStatusBadge(isChecked);
        });
        row.addView(detectionSwitch, new LinearLayout.LayoutParams(-2, -2));

        card.addView(row);
        return card;
    }

    private void updateDetectionStatusBadge(boolean enabled) {
        if (detectionStatusBadge != null) {
            detectionStatusBadge.setText(enabled ? "ACTIVE" : "DISABLED");
            detectionStatusBadge.setTextColor(enabled ? COLOR_SUCCESS : COLOR_TEXT_MUTED);
        }
    }

    private View buildScannedDownloadsSection() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(18);
        container.setLayoutParams(lp);

        TextView header = new TextView(this);
        header.setText("SCANNED DOWNLOADS");
        header.setTextSize(11f);
        header.setLetterSpacing(0.08f);
        header.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        header.setTextColor(COLOR_TEXT_MUTED);
        header.setPadding(0, 0, 0, dp(8));
        container.addView(header);

        scannedFilesContainer = new LinearLayout(this);
        scannedFilesContainer.setOrientation(LinearLayout.VERTICAL);

        TextView empty = new TextView(this);
        empty.setText("Tap 'Scan Downloads For Media' above to search for downloaded videos.");
        empty.setTextSize(11);
        empty.setTextColor(COLOR_TEXT_MUTED);
        empty.setPadding(dp(14), dp(16), dp(14), dp(16));
        empty.setBackground(createRoundedBackground(COLOR_SURFACE, COLOR_BORDER, dp(12)));
        scannedFilesContainer.addView(empty);

        container.addView(scannedFilesContainer);
        return container;
    }

    private View buildHistorySection() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(20);
        container.setLayoutParams(lp);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, dp(8));

        TextView header = new TextView(this);
        header.setText("RECENT IMPORTS");
        header.setTextSize(11f);
        header.setLetterSpacing(0.08f);
        header.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        header.setTextColor(COLOR_TEXT_MUTED);
        headerRow.addView(header, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnClear = new Button(this);
        btnClear.setText("CLEAR");
        btnClear.setTextSize(10f);
        btnClear.setTextColor(COLOR_TEXT_MUTED);
        btnClear.setBackground(null);
        btnClear.setOnClickListener(v -> {
            journal.clearHistory();
            refreshHistoryUI();
        });
        headerRow.addView(btnClear, new LinearLayout.LayoutParams(-2, -2));
        container.addView(headerRow);

        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        container.addView(historyContainer);

        refreshHistoryUI();
        return container;
    }

    private void refreshHistoryUI() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();

        List<MovieImportJournal.ImportRecord> history = journal.getRecentHistory(20);
        if (history.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No import operations recorded yet.");
            empty.setTextSize(11);
            empty.setTextColor(COLOR_TEXT_MUTED);
            empty.setPadding(dp(14), dp(16), dp(14), dp(16));
            empty.setBackground(createRoundedBackground(COLOR_SURFACE, COLOR_BORDER, dp(12)));
            historyContainer.addView(empty);
            return;
        }

        for (MovieImportJournal.ImportRecord r : history) {
            historyContainer.addView(buildHistoryRow(r));
        }
    }

    private View buildHistoryRow(MovieImportJournal.ImportRecord r) {
        LinearLayout row = createCard(dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        String label = r.movieTitle + (r.movieYear > 0 ? " (" + r.movieYear + ")" : "");
        if ("TV_SHOW".equalsIgnoreCase(r.mediaType) && r.seasonNumber > 0 && r.episodeNumber > 0) {
            label = r.movieTitle + String.format(Locale.ROOT, " - S%02dE%02d", r.seasonNumber, r.episodeNumber);
        }
        title.setText(label);
        title.setTextSize(12.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(COLOR_TEXT_PRIMARY);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView opBadge = new TextView(this);
        opBadge.setText(r.operation != null ? r.operation : "MOVE");
        opBadge.setTextSize(9.5f);
        opBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        opBadge.setTextColor(COLOR_ACCENT);
        opBadge.setPadding(0, 0, dp(8), 0);
        top.addView(opBadge);

        TextView statusBadge = new TextView(this);
        statusBadge.setText(r.status);
        statusBadge.setTextSize(9.5f);
        statusBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        int badgeColor = "COMPLETED".equals(r.status) ? COLOR_SUCCESS
                : "SKIPPED".equals(r.status) ? COLOR_WARNING : COLOR_DANGER;
        statusBadge.setTextColor(badgeColor);
        top.addView(statusBadge);
        row.addView(top);

        // Exact FROM and TO paths
        if (r.sourcePath != null && !r.sourcePath.isEmpty()) {
            TextView fromView = new TextView(this);
            fromView.setText("From: " + r.sourcePath);
            fromView.setTextSize(9.5f);
            fromView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
            fromView.setTextColor(COLOR_TEXT_MUTED);
            fromView.setPadding(0, dp(4), 0, 0);
            row.addView(fromView);
        }

        TextView toView = new TextView(this);
        toView.setText("To:   " + r.destinationPath);
        toView.setTextSize(9.5f);
        toView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        toView.setTextColor(COLOR_ACCENT);
        toView.setPadding(0, dp(2), 0, 0);
        row.addView(toView);

        if (r.errorMessage != null && !r.errorMessage.isEmpty()) {
            TextView err = new TextView(this);
            err.setText("Error: " + r.errorMessage);
            err.setTextSize(9.5f);
            err.setTextColor(COLOR_DANGER);
            err.setPadding(0, dp(4), 0, 0);
            row.addView(err);
        }

        return row;
    }

    // -- Mode A: File Picking --------------------------------------------------

    private void handlePickedVideoUri(Uri uri) {
        String filename = resolveFilename(uri);
        MovieFilenameParser.ParsedMovie parsed = MovieFilenameParser.parse(filename);
        MovieMetadata metadata = MovieMetadata.fromParsed(parsed);

        showConfirmationDialog(uri, filename, metadata, null);
    }

    private String resolveFilename(Uri uri) {
        String name = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex);
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (name == null) {
            String path = uri.getPath();
            if (path != null) {
                int cut = path.lastIndexOf('/');
                if (cut != -1) name = path.substring(cut + 1);
            }
        }
        return name != null && !name.isEmpty() ? name : "media.mkv";
    }

    // -- Mode B: Scan Downloads Folder -----------------------------------------

    private void scanDownloadsDirectory() {
        if (scannedFilesContainer == null) return;
        scannedFilesContainer.removeAllViews();

        TextView scanning = new TextView(this);
        scanning.setText("Scanning Downloads folder for video files...");
        scanning.setTextSize(11);
        scanning.setTextColor(COLOR_TEXT_MUTED);
        scanning.setPadding(dp(14), dp(16), dp(14), dp(16));
        scannedFilesContainer.addView(scanning);

        backgroundExecutor.execute(() -> {
            List<ScannedVideoItem> rawItems = new ArrayList<>();

            String moviesLibPath = MediaStorageManager.getInstance(this).getMoviesDir().getAbsolutePath();
            String tvLibPath = MediaStorageManager.getInstance(this).getTvDir().getAbsolutePath();
            String fishbowlRoot = MediaStorageManager.getInstance(this).getMediaRoot().getAbsolutePath();

            // 1. Scan /storage/emulated/0/Download directly and common download subfolders
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            List<File> scanFolders = new ArrayList<>();
            if (downloadDir != null && downloadDir.exists() && downloadDir.canRead()) {
                scanFolders.add(downloadDir);
                File[] subdirs = downloadDir.listFiles(File::isDirectory);
                if (subdirs != null) {
                    for (File sub : subdirs) {
                        if (!sub.getName().startsWith(".")) {
                            scanFolders.add(sub);
                        }
                    }
                }
            }
            // Also check /storage/emulated/0/Subtitles folder
            File subtitlesDir = new File(Environment.getExternalStorageDirectory(), "Subtitles");
            if (subtitlesDir.exists() && subtitlesDir.canRead()) {
                scanFolders.add(subtitlesDir);
                File[] subSubs = subtitlesDir.listFiles(File::isDirectory);
                if (subSubs != null) {
                    for (File s : subSubs) {
                        if (!s.getName().startsWith(".")) {
                            scanFolders.add(s);
                        }
                    }
                }
            }

            for (File folder : scanFolders) {
                File[] files = folder.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (f.isFile() && StorageDriveHelper.isVideoFile(f.getName())) {
                        if (f.length() > 20 * 1024 * 1024L) { // Min 20MB
                            String absPath = f.getAbsolutePath();
                            // NEVER scan inside library folders!
                            if (absPath.startsWith(fishbowlRoot) || absPath.startsWith(moviesLibPath) || absPath.startsWith(tvLibPath) || absPath.contains("/FishbowlMedia/")) {
                                continue;
                            }
                            Uri uri = Uri.fromFile(f);
                            if (!journal.isAlreadyImported(f.getName(), uri.toString(), absPath)) {
                                rawItems.add(new ScannedVideoItem(uri, f.getName(), absPath, f.length(), f.lastModified()));
                            }
                        }
                    }
                }
            }

            // 2. Query MediaStore.Video for any other accessible downloads
            try {
                String[] proj = {
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_MODIFIED
                };
                try (Cursor cursor = getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        proj, null, null,
                        MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {

                    if (cursor != null && cursor.moveToFirst()) {
                        do {
                            long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                            String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME));
                            String dataPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA));
                            long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE));
                            long date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)) * 1000L;

                            if (name != null && size > 20 * 1024 * 1024L && StorageDriveHelper.isVideoFile(name)) {
                                // CRITICAL: Skip if dataPath is inside FishbowlMedia / Movies / TV library
                                if (dataPath != null && !dataPath.isEmpty()) {
                                    if (dataPath.startsWith(fishbowlRoot) || dataPath.startsWith(moviesLibPath) || dataPath.startsWith(tvLibPath) || dataPath.contains("/FishbowlMedia/")) {
                                        continue;
                                    }
                                    File checkFile = new File(dataPath);
                                    if (!checkFile.exists() || checkFile.length() < 20 * 1024 * 1024L) {
                                        continue; // File was moved or deleted
                                    }
                                }

                                boolean exists = false;
                                for (ScannedVideoItem existing : rawItems) {
                                    if (name.equalsIgnoreCase(existing.filename) || (dataPath != null && dataPath.equalsIgnoreCase(existing.directPath))) {
                                        exists = true;
                                        break;
                                    }
                                }
                                if (!exists) {
                                    Uri uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                                    if (!journal.isAlreadyImported(name, uri.toString(), dataPath)) {
                                        rawItems.add(new ScannedVideoItem(uri, name, dataPath != null ? dataPath : "", size, date));
                                    }
                                }
                            }
                        } while (cursor.moveToNext() && rawItems.size() < 40);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore scan error: " + t.getMessage());
            }

            // 3. Final safety filter: remove any items where source destination is already inside library or identical
            List<ScannedVideoItem> filtered = new ArrayList<>();
            for (ScannedVideoItem item : rawItems) {
                if (item.directPath != null && !item.directPath.isEmpty()) {
                    String normPath = item.directPath.toLowerCase(Locale.ROOT);
                    if (normPath.startsWith(fishbowlRoot.toLowerCase(Locale.ROOT)) ||
                        normPath.startsWith(moviesLibPath.toLowerCase(Locale.ROOT)) ||
                        normPath.startsWith(tvLibPath.toLowerCase(Locale.ROOT)) ||
                        normPath.contains("/fishbowlmedia")) {
                        continue;
                    }
                }
                MovieFilenameParser.ParsedMovie p = MovieFilenameParser.parse(item.filename);
                String rootDest = p.mediaType == MovieFilenameParser.MediaType.TV_SHOW ? tvLibPath : moviesLibPath;
                String destPath = rootDest + "/" + p.proposedRelativePath;
                if (destPath.equalsIgnoreCase(item.directPath)) {
                    continue; // Already at destination!
                }
                if (journal.isAlreadyImported(item.filename, item.uri.toString(), item.directPath)) {
                    continue; // Already imported according to journal!
                }
                // Check if target file already exists in library on disk
                File destFile = new File(destPath);
                if (destFile.exists() && destFile.length() > 0) {
                    continue; // Already imported and present in Jellyfin library!
                }
                // Check if movie directory already exists in library with valid video
                if (p.mediaType == MovieFilenameParser.MediaType.MOVIE && p.title != null && !p.title.isEmpty()) {
                    File movieFolder = new File(moviesLibPath, p.proposedFolderName);
                    if (movieFolder.exists() && movieFolder.isDirectory()) {
                        File[] existingVideos = movieFolder.listFiles((dir, name) -> StorageDriveHelper.isVideoFile(name));
                        if (existingVideos != null && existingVideos.length > 0) {
                            continue; // Movie already exists in library!
                        }
                    }
                }
                filtered.add(item);
            }

            mainHandler.post(() -> renderScannedItems(filtered));
        });
    }

    private static final class ScannedVideoItem {
        final Uri uri;
        final String filename;
        final String directPath;
        final long lengthBytes;
        final long lastModifiedMs;

        ScannedVideoItem(Uri uri, String filename, String directPath, long lengthBytes, long lastModifiedMs) {
            this.uri = uri;
            this.filename = filename;
            this.directPath = directPath;
            this.lengthBytes = lengthBytes;
            this.lastModifiedMs = lastModifiedMs;
        }
    }

    private void renderScannedItems(List<ScannedVideoItem> items) {
        if (scannedFilesContainer == null) return;
        scannedFilesContainer.removeAllViews();

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("All downloaded video files have already been imported!");
            empty.setTextSize(11);
            empty.setTextColor(COLOR_TEXT_MUTED);
            empty.setPadding(dp(14), dp(16), dp(14), dp(16));
            empty.setBackground(createRoundedBackground(COLOR_SURFACE, COLOR_BORDER, dp(12)));
            scannedFilesContainer.addView(empty);
            return;
        }

        for (ScannedVideoItem item : items) {
            View card = buildScannedItemCard(item);
            scannedFilesContainer.addView(card);
        }
    }

    private View buildScannedItemCard(ScannedVideoItem item) {
        LinearLayout row = createCard(dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        MovieFilenameParser.ParsedMovie parsed = MovieFilenameParser.parse(item.filename);

        // Header line: Filename and Type Badge
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(item.filename);
        name.setTextSize(12f);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setTextColor(COLOR_TEXT_PRIMARY);
        headerRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView typeBadge = new TextView(this);
        boolean isTv = parsed.mediaType == MovieFilenameParser.MediaType.TV_SHOW;
        typeBadge.setText(isTv ? "TV SERIES" : "MOVIE");
        typeBadge.setTextSize(9.5f);
        typeBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        typeBadge.setTextColor(isTv ? COLOR_ACCENT : COLOR_SUCCESS);
        typeBadge.setBackground(createRoundedBackground(COLOR_ELEVATED, COLOR_BORDER, dp(4)));
        typeBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        headerRow.addView(typeBadge);
        row.addView(headerRow);

        // Metadata Details (Size | Date | Detected Title)
        TextView details = new TextView(this);
        String sizeStr = MediaStorageManager.formatBytes(item.lengthBytes);
        String dateStr = item.lastModifiedMs > 0
                ? new SimpleDateFormat("MMM dd, yyyy", Locale.ROOT).format(new Date(item.lastModifiedMs))
                : "";
        String titleInfo = isTv
                ? parsed.title + String.format(Locale.ROOT, " (S%02dE%02d)", parsed.seasonNumber > 0 ? parsed.seasonNumber : 1, parsed.episodeNumber > 0 ? parsed.episodeNumber : 1)
                : parsed.title + (parsed.year > 0 ? " (" + parsed.year + ")" : "");

        details.setText(sizeStr + (dateStr.isEmpty() ? "" : " | " + dateStr) + " | " + titleInfo);
        details.setTextSize(10f);
        details.setTextColor(COLOR_TEXT_MUTED);
        details.setPadding(0, dp(4), 0, dp(6));
        row.addView(details);

        // PATHS: From -> To
        String sourceDisplay = item.directPath != null && !item.directPath.isEmpty()
                ? item.directPath
                : "/storage/emulated/0/Download/" + item.filename;

        String rootDest = isTv
                ? MediaStorageManager.getInstance(this).getTvDir().getAbsolutePath()
                : MediaStorageManager.getInstance(this).getMoviesDir().getAbsolutePath();

        String destDisplay = rootDest + "/" + parsed.proposedRelativePath;

        TextView fromView = new TextView(this);
        fromView.setText("From: " + sourceDisplay);
        fromView.setTextSize(9.5f);
        fromView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        fromView.setTextColor(COLOR_TEXT_MUTED);
        row.addView(fromView);

        TextView toView = new TextView(this);
        toView.setText("To:   " + destDisplay);
        toView.setTextSize(9.5f);
        toView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        toView.setTextColor(COLOR_ACCENT);
        toView.setPadding(0, dp(2), 0, dp(8));
        row.addView(toView);

        // Action Button: IMPORT (MOVE)
        Button btnReview = createButton("SELECT & REVIEW IMPORT (MOVE)", Color.WHITE, Color.BLACK);
        btnReview.setOnClickListener(v -> {
            MovieMetadata metadata = MovieMetadata.fromParsed(parsed);
            showConfirmationDialog(item.uri, item.filename, metadata, row);
        });
        row.addView(btnReview, new LinearLayout.LayoutParams(-1, dp(40)));

        return row;
    }

    // -- Confirmation & Review Dialog ------------------------------------------

    private void showConfirmationDialog(Uri sourceUri,
                                        String sourceFilename,
                                        MovieMetadata initialMetadata,
                                        View scannedCardViewToRemoveOnSuccess) {

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(24));
        content.setBackground(createRoundedBackground(COLOR_SURFACE, COLOR_BORDER, dp(14)));

        TextView title = new TextView(this);
        title.setText("CONFIRM MEDIA IMPORT");
        title.setTextSize(13.5f);
        title.setLetterSpacing(0.1f);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(COLOR_TEXT_PRIMARY);
        content.addView(title);

        TextView rawFile = new TextView(this);
        rawFile.setText("Source: " + sourceFilename);
        rawFile.setTextSize(10.5f);
        rawFile.setTextColor(COLOR_TEXT_MUTED);
        rawFile.setPadding(0, dp(4), 0, dp(14));
        content.addView(rawFile);

        // Segmented Media Type Switcher: [ MOVIE ] vs [ TV SHOW ]
        final boolean[] isTvShow = {initialMetadata.mediaType == MovieFilenameParser.MediaType.TV_SHOW};
        LinearLayout typeSwitchRow = new LinearLayout(this);
        typeSwitchRow.setOrientation(LinearLayout.HORIZONTAL);
        typeSwitchRow.setPadding(0, 0, 0, dp(14));

        Button btnMovieTab = createButton("MOVIE", isTvShow[0] ? COLOR_ELEVATED : Color.WHITE, isTvShow[0] ? COLOR_TEXT_MUTED : Color.BLACK);
        Button btnTvTab = createButton("TV SERIES", isTvShow[0] ? Color.WHITE : COLOR_ELEVATED, isTvShow[0] ? Color.BLACK : COLOR_TEXT_MUTED);

        LinearLayout.LayoutParams pTab1 = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        pTab1.rightMargin = dp(6);
        typeSwitchRow.addView(btnMovieTab, pTab1);

        LinearLayout.LayoutParams pTab2 = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        pTab2.leftMargin = dp(6);
        typeSwitchRow.addView(btnTvTab, pTab2);
        content.addView(typeSwitchRow);

        // Title Input
        TextView lblName = new TextView(this);
        lblName.setText("Title / Series Name:");
        lblName.setTextSize(11);
        lblName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblName.setTextColor(COLOR_TEXT_MUTED);
        content.addView(lblName);

        EditText editTitle = new EditText(this);
        editTitle.setText(initialMetadata.title);
        editTitle.setTextSize(13);
        editTitle.setTextColor(COLOR_TEXT_PRIMARY);
        editTitle.setBackground(createRoundedBackground(COLOR_ELEVATED, COLOR_BORDER, dp(8)));
        editTitle.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.addView(editTitle);

        // Year Input
        TextView lblYear = new TextView(this);
        lblYear.setText("Release Year (Optional):");
        lblYear.setTextSize(11);
        lblYear.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblYear.setTextColor(COLOR_TEXT_MUTED);
        lblYear.setPadding(0, dp(10), 0, 0);
        content.addView(lblYear);

        EditText editYear = new EditText(this);
        editYear.setText(initialMetadata.year > 0 ? String.valueOf(initialMetadata.year) : "");
        editYear.setTextSize(13);
        editYear.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editYear.setTextColor(COLOR_TEXT_PRIMARY);
        editYear.setBackground(createRoundedBackground(COLOR_ELEVATED, COLOR_BORDER, dp(8)));
        editYear.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.addView(editYear);

        // TV Specific Inputs (Season & Episode)
        LinearLayout tvFieldsContainer = new LinearLayout(this);
        tvFieldsContainer.setOrientation(LinearLayout.HORIZONTAL);
        tvFieldsContainer.setPadding(0, dp(10), 0, 0);
        tvFieldsContainer.setVisibility(isTvShow[0] ? View.VISIBLE : View.GONE);

        LinearLayout colSeason = new LinearLayout(this);
        colSeason.setOrientation(LinearLayout.VERTICAL);
        TextView lblSeason = new TextView(this);
        lblSeason.setText("Season #:");
        lblSeason.setTextSize(11);
        lblSeason.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblSeason.setTextColor(COLOR_TEXT_MUTED);
        colSeason.addView(lblSeason);

        EditText editSeason = new EditText(this);
        editSeason.setText(String.valueOf(initialMetadata.seasonNumber > 0 ? initialMetadata.seasonNumber : 1));
        editSeason.setTextSize(13);
        editSeason.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editSeason.setTextColor(COLOR_TEXT_PRIMARY);
        editSeason.setBackground(createRoundedBackground(COLOR_ELEVATED, COLOR_BORDER, dp(8)));
        editSeason.setPadding(dp(12), dp(10), dp(12), dp(10));
        colSeason.addView(editSeason);

        LinearLayout.LayoutParams pCol1 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        pCol1.rightMargin = dp(8);
        tvFieldsContainer.addView(colSeason, pCol1);

        LinearLayout colEpisode = new LinearLayout(this);
        colEpisode.setOrientation(LinearLayout.VERTICAL);
        TextView lblEpisode = new TextView(this);
        lblEpisode.setText("Episode #:");
        lblEpisode.setTextSize(11);
        lblEpisode.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblEpisode.setTextColor(COLOR_TEXT_MUTED);
        colEpisode.addView(lblEpisode);

        EditText editEpisode = new EditText(this);
        editEpisode.setText(String.valueOf(initialMetadata.episodeNumber > 0 ? initialMetadata.episodeNumber : 1));
        editEpisode.setTextSize(13);
        editEpisode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editEpisode.setTextColor(COLOR_TEXT_PRIMARY);
        editEpisode.setBackground(createRoundedBackground(COLOR_ELEVATED, COLOR_BORDER, dp(8)));
        editEpisode.setPadding(dp(12), dp(10), dp(12), dp(10));
        colEpisode.addView(editEpisode);

        LinearLayout.LayoutParams pCol2 = new LinearLayout.LayoutParams(0, -2, 1.0f);
        pCol2.leftMargin = dp(8);
        tvFieldsContainer.addView(colEpisode, pCol2);
        content.addView(tvFieldsContainer);

        // Path Preview Box (FROM -> TO)
        LinearLayout pathBox = createCard(dp(12));
        LinearLayout.LayoutParams pBox = new LinearLayout.LayoutParams(-1, -2);
        pBox.topMargin = dp(14);
        pathBox.setLayoutParams(pBox);

        TextView lblFrom = new TextView(this);
        String resolvedSrc = sourceUri.getPath() != null && sourceUri.getPath().startsWith("/")
                ? sourceUri.getPath()
                : "/storage/emulated/0/Download/" + sourceFilename;
        lblFrom.setText("FROM (Source):\n" + resolvedSrc);
        lblFrom.setTextSize(10f);
        lblFrom.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        lblFrom.setTextColor(COLOR_TEXT_MUTED);
        pathBox.addView(lblFrom);

        View sep = new View(this);
        sep.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams pSep = new LinearLayout.LayoutParams(-1, dp(1));
        pSep.topMargin = dp(6);
        pSep.bottomMargin = dp(6);
        pathBox.addView(sep, pSep);

        TextView lblTo = new TextView(this);
        lblTo.setTextSize(10.5f);
        lblTo.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lblTo.setTextColor(COLOR_ACCENT);
        pathBox.addView(lblTo);
        content.addView(pathBox);

        // Update Destination Label dynamically
        Runnable updateDestLabel = () -> {
            String t = editTitle.getText().toString().trim();
            if (t.isEmpty()) t = "Untitled";
            int y = 0;
            try { y = Integer.parseInt(editYear.getText().toString().trim()); } catch (Throwable ignored) {}

            String ext = getFileExtension(sourceFilename);
            if (isTvShow[0]) {
                int s = 1;
                int e = 1;
                try { s = Integer.parseInt(editSeason.getText().toString().trim()); } catch (Throwable ignored) {}
                try { e = Integer.parseInt(editEpisode.getText().toString().trim()); } catch (Throwable ignored) {}
                String folder = MovieFilenameParser.buildSanitizedTvFolder(t, y, s);
                String file = MovieFilenameParser.buildSanitizedTvFile(t, s, e, -1, ext);
                String fullPath = MediaStorageManager.getInstance(this).getTvDir().getAbsolutePath() + "/" + folder + "/" + file;
                lblTo.setText("TO (Jellyfin TV Shows):\n" + fullPath);
            } else {
                String folder = MovieFilenameParser.buildSanitizedName(t, y, "");
                String file = MovieFilenameParser.buildSanitizedName(t, y, ext);
                String fullPath = MediaStorageManager.getInstance(this).getMoviesDir().getAbsolutePath() + "/" + folder + "/" + file;
                lblTo.setText("TO (Jellyfin Movies):\n" + fullPath);
            }
        };

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int count, int after) { updateDestLabel.run(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        editTitle.addTextChangedListener(watcher);
        editYear.addTextChangedListener(watcher);
        editSeason.addTextChangedListener(watcher);
        editEpisode.addTextChangedListener(watcher);

        btnMovieTab.setOnClickListener(v -> {
            isTvShow[0] = false;
            btnMovieTab.setBackground(createRoundedBackground(Color.WHITE, 0, dp(8)));
            btnMovieTab.setTextColor(Color.BLACK);
            btnTvTab.setBackground(createRoundedBackground(COLOR_ELEVATED, 0, dp(8)));
            btnTvTab.setTextColor(COLOR_TEXT_MUTED);
            tvFieldsContainer.setVisibility(View.GONE);
            updateDestLabel.run();
        });

        btnTvTab.setOnClickListener(v -> {
            isTvShow[0] = true;
            btnTvTab.setBackground(createRoundedBackground(Color.WHITE, 0, dp(8)));
            btnTvTab.setTextColor(Color.BLACK);
            btnMovieTab.setBackground(createRoundedBackground(COLOR_ELEVATED, 0, dp(8)));
            btnMovieTab.setTextColor(COLOR_TEXT_MUTED);
            tvFieldsContainer.setVisibility(View.VISIBLE);
            updateDestLabel.run();
        });

        updateDestLabel.run();

        // Transfer Mode (MOVE vs COPY) - DEFAULT: MOVE!
        final boolean[] isMoveSelected = {true}; // Default MOVE per user request!
        LinearLayout opRow = new LinearLayout(this);
        opRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pOp = new LinearLayout.LayoutParams(-1, -2);
        pOp.topMargin = dp(14);
        opRow.setLayoutParams(pOp);

        Button btnMove = createButton("MOVE (CUT & DELETE ORIGINAL)", Color.WHITE, Color.BLACK);
        Button btnCopy = createButton("COPY (DUPLICATE)", COLOR_ELEVATED, COLOR_TEXT_MUTED);

        LinearLayout.LayoutParams pOp1 = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        pOp1.rightMargin = dp(6);
        opRow.addView(btnMove, pOp1);

        LinearLayout.LayoutParams pOp2 = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        pOp2.leftMargin = dp(6);
        opRow.addView(btnCopy, pOp2);
        content.addView(opRow);

        btnMove.setOnClickListener(v -> {
            isMoveSelected[0] = true;
            btnMove.setBackground(createRoundedBackground(Color.WHITE, 0, dp(8)));
            btnMove.setTextColor(Color.BLACK);
            btnCopy.setBackground(createRoundedBackground(COLOR_ELEVATED, 0, dp(8)));
            btnCopy.setTextColor(COLOR_TEXT_MUTED);
        });

        btnCopy.setOnClickListener(v -> {
            isMoveSelected[0] = false;
            btnCopy.setBackground(createRoundedBackground(Color.WHITE, 0, dp(8)));
            btnCopy.setTextColor(Color.BLACK);
            btnMove.setBackground(createRoundedBackground(COLOR_ELEVATED, 0, dp(8)));
            btnMove.setTextColor(COLOR_TEXT_MUTED);
        });

        // Conflict Choice
        TextView lblConflict = new TextView(this);
        lblConflict.setText("If destination file already exists:");
        lblConflict.setTextSize(10.5f);
        lblConflict.setTextColor(COLOR_TEXT_MUTED);
        lblConflict.setPadding(0, dp(12), 0, dp(4));
        content.addView(lblConflict);

        String[] conflictOptions = {"Keep Both (append number)", "Skip if exists", "Replace destination"};
        final int[] selectedConflict = {0};
        Button btnConflict = createOutlineButton(conflictOptions[0], COLOR_TEXT_PRIMARY);
        btnConflict.setOnClickListener(v -> {
            selectedConflict[0] = (selectedConflict[0] + 1) % conflictOptions.length;
            btnConflict.setText(conflictOptions[selectedConflict[0]]);
        });
        content.addView(btnConflict, new LinearLayout.LayoutParams(-1, dp(38)));

        // START IMPORT BUTTON
        Button btnExecute = createButton("CONFIRM & START IMPORT", COLOR_ACCENT, Color.BLACK);
        btnExecute.setOnClickListener(v -> {
            dialog.dismiss();

            String finalTitle = editTitle.getText().toString().trim();
            if (finalTitle.isEmpty()) finalTitle = "Untitled";
            int finalYear = 0;
            try { finalYear = Integer.parseInt(editYear.getText().toString().trim()); } catch (Throwable ignored) {}

            int finalSeason = -1;
            int finalEpisode = -1;
            if (isTvShow[0]) {
                try { finalSeason = Integer.parseInt(editSeason.getText().toString().trim()); } catch (Throwable ignored) {}
                try { finalEpisode = Integer.parseInt(editEpisode.getText().toString().trim()); } catch (Throwable ignored) {}
                if (finalSeason <= 0) finalSeason = 1;
                if (finalEpisode <= 0) finalEpisode = 1;
            }

            MovieMetadata finalMeta = new MovieMetadata(
                    isTvShow[0] ? MovieFilenameParser.MediaType.TV_SHOW : MovieFilenameParser.MediaType.MOVIE,
                    finalTitle,
                    finalYear,
                    finalSeason,
                    finalEpisode,
                    -1,
                    finalTitle,
                    "",
                    "",
                    0,
                    1.0,
                    "User Confirmed"
            );

            MovieImportManager.ConflictStrategy strategy;
            if (selectedConflict[0] == 1) strategy = MovieImportManager.ConflictStrategy.SKIP;
            else if (selectedConflict[0] == 2) strategy = MovieImportManager.ConflictStrategy.REPLACE;
            else strategy = MovieImportManager.ConflictStrategy.KEEP_BOTH;

            executeImportTransfer(sourceUri, sourceFilename, finalMeta, isMoveSelected[0], strategy, scannedCardViewToRemoveOnSuccess);
        });

        LinearLayout.LayoutParams pExec = new LinearLayout.LayoutParams(-1, dp(44));
        pExec.topMargin = dp(16);
        content.addView(btnExecute, pExec);

        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94), -2);
        }
    }

    // -- Live Transfer & Progress Dialog ---------------------------------------

    private void executeImportTransfer(Uri sourceUri,
                                       String sourceFilename,
                                       MovieMetadata metadata,
                                       boolean isMove,
                                       MovieImportManager.ConflictStrategy strategy,
                                       View cardToRemoveOnSuccess) {

        Dialog progressDialog = new Dialog(this);
        progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        progressDialog.setCancelable(false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(COLOR_SURFACE);
        layout.setPadding(dp(22), dp(22), dp(22), dp(22));

        TextView title = new TextView(this);
        String opName = isMove ? "MOVING MEDIA TO JELLYFIN" : "COPYING MEDIA TO JELLYFIN";
        title.setText(opName);
        title.setTextSize(13.5f);
        title.setLetterSpacing(0.08f);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(COLOR_TEXT_PRIMARY);
        layout.addView(title);

        TextView filenameText = new TextView(this);
        filenameText.setText(sourceFilename);
        filenameText.setTextSize(11);
        filenameText.setTextColor(COLOR_TEXT_MUTED);
        filenameText.setPadding(0, dp(4), 0, dp(14));
        layout.addView(filenameText);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        layout.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)));

        TextView statsText = new TextView(this);
        statsText.setText("Preparing transfer...");
        statsText.setTextSize(11);
        statsText.setTextColor(COLOR_TEXT_MUTED);
        statsText.setPadding(0, dp(10), 0, dp(14));
        layout.addView(statsText);

        Button btnCancel = createOutlineButton("CANCEL", COLOR_DANGER);
        btnCancel.setOnClickListener(v -> {
            importManager.cancelCurrentImport();
            statsText.setText("Cancelling...");
            btnCancel.setEnabled(false);
        });
        layout.addView(btnCancel, new LinearLayout.LayoutParams(-1, dp(38)));

        progressDialog.setContentView(layout);
        progressDialog.show();
        if (progressDialog.getWindow() != null) {
            progressDialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.90), -2);
        }

        String rootDest = metadata.mediaType == MovieFilenameParser.MediaType.TV_SHOW
                ? MediaStorageManager.getInstance(this).getTvDir().getAbsolutePath()
                : MediaStorageManager.getInstance(this).getMoviesDir().getAbsolutePath();

        importManager.startImport(sourceUri, sourceFilename, rootDest, metadata, isMove, strategy, new MovieImportManager.ImportListener() {
            @Override
            public void onProgress(MovieImportJournal.ImportRecord record, long bytesWritten, long totalBytes, int percent) {
                bar.setProgress(percent);
                String stat = String.format(Locale.ROOT, "%s / %s (%d%%)",
                        MediaStorageManager.formatBytes(bytesWritten),
                        MediaStorageManager.formatBytes(totalBytes),
                        percent);
                statsText.setText(stat);
            }

            @Override
            public void onSuccess(MovieImportJournal.ImportRecord record, boolean jellyfinRefreshed) {
                progressDialog.dismiss();

                // 1. Remove from scanned downloads list immediately
                if (cardToRemoveOnSuccess != null && scannedFilesContainer != null) {
                    scannedFilesContainer.removeView(cardToRemoveOnSuccess);
                    if (scannedFilesContainer.getChildCount() == 0) {
                        TextView empty = new TextView(MovieImportActivity.this);
                        empty.setText("All downloaded video files have already been imported!");
                        empty.setTextSize(11);
                        empty.setTextColor(COLOR_TEXT_MUTED);
                        empty.setPadding(dp(14), dp(16), dp(14), dp(16));
                        empty.setBackground(createRoundedBackground(COLOR_SURFACE, COLOR_BORDER, dp(12)));
                        scannedFilesContainer.addView(empty);
                    }
                }

                // 2. Refresh Recent Imports list
                refreshHistoryUI();

                String msg = (isMove ? "Moved to Jellyfin:\n" : "Copied to Jellyfin:\n") + record.destinationPath;
                if (jellyfinRefreshed) msg += "\n(Jellyfin library scan dispatched)";
                Toast.makeText(MovieImportActivity.this, msg, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(MovieImportJournal.ImportRecord record, String errorMessage) {
                progressDialog.dismiss();
                refreshHistoryUI();
                new androidx.appcompat.app.AlertDialog.Builder(MovieImportActivity.this)
                        .setTitle("Import Failed")
                        .setMessage(errorMessage)
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onCancelled(MovieImportJournal.ImportRecord record) {
                progressDialog.dismiss();
                refreshHistoryUI();
                Toast.makeText(MovieImportActivity.this, "Import cancelled.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "mkv";
        int dot = filename.lastIndexOf('.');
        if (dot > 0 && dot < filename.length() - 1) {
            return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "mkv";
    }

    // -- UI Factory Helpers ----------------------------------------------------

    private LinearLayout createCard(int paddingDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(paddingDp, paddingDp, paddingDp, paddingDp);
        card.setBackground(createRoundedBackground(COLOR_SURFACE, COLOR_BORDER, dp(14)));
        return card;
    }

    private GradientDrawable createRoundedBackground(int bgColor, int borderColor, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(radiusPx);
        if (borderColor != 0) {
            gd.setStroke(dp(1), borderColor);
        }
        return gd;
    }

    private RippleDrawable createButtonRippleDrawable(int normalColor, int radiusDp, int rippleColor) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(normalColor);
        content.setCornerRadius(dp(radiusDp));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    private Button createButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11.5f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(textColor);
        b.setBackground(createRoundedBackground(bgColor, 0, dp(10)));
        return b;
    }

    private Button createOutlineButton(String text, int strokeColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(strokeColor);
        b.setBackground(createRoundedBackground(Color.TRANSPARENT, strokeColor, dp(10)));
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
