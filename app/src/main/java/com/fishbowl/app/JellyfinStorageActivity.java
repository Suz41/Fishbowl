package com.fishbowl.app;

import com.termux.R;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JellyfinStorageActivity extends AppCompatActivity {

    // Color tokens synchronized with FishbowlActivity design tokens
    private static final int COLOR_BG = Color.parseColor("#000000");
    private static final int COLOR_SURFACE = Color.parseColor("#0C0C0E");
    private static final int COLOR_ELEVATED = Color.parseColor("#16171B");
    private static final int COLOR_BORDER = Color.argb(20, 255, 255, 255);
    private static final int COLOR_ACCENT = Color.parseColor("#38BDF8");
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#F4F4F5");
    private static final int COLOR_TEXT_MUTED = Color.parseColor("#A1A1AA");
    private static final int COLOR_SUCCESS = Color.parseColor("#34D399");
    private static final int COLOR_WARNING = Color.parseColor("#FFD51E");
    private static final int COLOR_DANGER = Color.parseColor("#FF1744");

    private SharedPreferences prefs;
    private String activeSelectedPath = "/storage/emulated/0/FishbowlMedia/Movies";
    private LinearLayout drivesContainer;
    private LinearLayout savedListContainer;
    private TextView activePathText;
    private TextView activePathStatus;
    private Button btnCopyActivePath;
    private View permissionBanner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );
                        } catch (Throwable ignored) {}

                        String resolved = StorageDriveHelper.resolveTreeUriToPath(this, treeUri);
                        if (resolved != null && !resolved.trim().isEmpty()) {
                            resolved = StorageDriveHelper.normalizeStoragePath(resolved);
                            setActivePath(resolved, true);
                            refreshUI();
                            StorageDriveHelper.PathVerification ver = StorageDriveHelper.verifyPath(this, resolved);
                            if (ver.isUsbStorage && !ver.posixAccessible) {
                                new AlertDialog.Builder(this)
                                        .setTitle("External USB Storage Notice")
                                        .setMessage("Storage Access Framework (SAF) permission granted for:\n\n" + resolved +
                                                "\n\nImportant: On standard Android 11+ devices, Android's security sandbox (SELinux/Scoped Storage) allows Java apps to view external USB drives via SAF, but blocks native Linux processes like Jellyfin and FFmpeg from accessing USB paths directly via POSIX.\n\nRecommended: For seamless playback, store media in Internal Storage (/storage/emulated/0/FishbowlMedia/Movies).")
                                        .setPositiveButton("SWITCH TO INTERNAL", (d, w) -> {
                                            String internalPath = "/storage/emulated/0/FishbowlMedia/Movies";
                                            setActivePath(internalPath, true);
                                            refreshUI();
                                        })
                                        .setNegativeButton("KEEP USB PATH", null)
                                        .show();
                            } else {
                                Toast.makeText(this, "SAF Granted: " + resolved, Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            new AlertDialog.Builder(this)
                                    .setTitle("Virtual Cloud Storage (On Hold)")
                                    .setMessage("The selected location (" + treeUri.getLastPathSegment() + ") is hosted by a virtual DocumentProvider (e.g. Google Drive, Nextcloud, or RSAF). Jellyfin requires direct POSIX filesystem paths. Cloud and drive root mapping are currently in beta testing mode and on hold.")
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setupSystemBars();

        prefs = getSharedPreferences("fishbowl_storage", MODE_PRIVATE);
        String savedLast = prefs.getString("last_selected_path", null);
        if (savedLast != null && !savedLast.trim().isEmpty()) {
            activeSelectedPath = StorageDriveHelper.normalizeStoragePath(savedLast);
            if (!activeSelectedPath.equals(savedLast)) {
                prefs.edit().putString("last_selected_path", activeSelectedPath).apply();
            }
        } else {
            activeSelectedPath = "/storage/emulated/0/FishbowlMedia/Movies";
        }

        setContentView(buildView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUI();
    }

    private void setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controllerCompat = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controllerCompat.setAppearanceLightStatusBars(false);
        controllerCompat.setAppearanceLightNavigationBars(false);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
    }

    private View buildView() {
        LinearLayout outerRoot = new LinearLayout(this);
        outerRoot.setOrientation(LinearLayout.VERTICAL);
        outerRoot.setBackgroundColor(COLOR_BG);

        // Apply system bar insets
        ViewCompat.setOnApplyWindowInsetsListener(outerRoot, (v, insets) -> {
            androidx.core.graphics.Insets statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            androidx.core.graphics.Insets navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(0, statusBarInset.top, 0, navBarInset.bottom);
            return insets;
        });

        // 1. Pinned Header Bar (matching FishbowlActivity)
        outerRoot.addView(buildHeaderBar());

        // 2. Scrollable Body
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(28));

        // Permission Status Banner
        permissionBanner = buildPermissionBanner();
        root.addView(permissionBanner);

        // Active Selected Path Card (Prominent at the top)
        root.addView(buildActivePathCard());

        // Connected Storage Drives Header & Container
        TextView drivesHeader = new TextView(this);
        drivesHeader.setText("CONNECTED STORAGE DRIVES");
        drivesHeader.setTextSize(11);
        drivesHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        drivesHeader.setTextColor(COLOR_TEXT_MUTED);
        drivesHeader.setLetterSpacing(0.08f);
        drivesHeader.setPadding(0, dp(16), 0, dp(8));
        root.addView(drivesHeader);

        drivesContainer = new LinearLayout(this);
        drivesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(drivesContainer);

        // Saved Folders Section
        TextView savedHeader = new TextView(this);
        savedHeader.setText("SAVED MEDIA PATHS");
        savedHeader.setTextSize(11);
        savedHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        savedHeader.setTextColor(COLOR_TEXT_MUTED);
        savedHeader.setLetterSpacing(0.08f);
        savedHeader.setPadding(0, dp(16), 0, dp(8));
        root.addView(savedHeader);

        savedListContainer = new LinearLayout(this);
        savedListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(savedListContainer);

        // Quick 3-Step Guide Card
        root.addView(buildGuideCard());

        scroll.addView(root);
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

        // Back Button
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
        title.setText("MEDIA STORAGE");
        title.setTextSize(13.5f);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(COLOR_TEXT_PRIMARY);
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText("DRIVES & LIBRARY PATHS");
        sub.setTextSize(9.5f);
        sub.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        sub.setTextColor(COLOR_TEXT_MUTED);
        titleCol.addView(sub);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tag = new TextView(this);
        tag.setText("STORAGE HUB");
        tag.setTextSize(9.5f);
        tag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tag.setTextColor(COLOR_ACCENT);
        header.addView(tag);

        rootHeader.addView(header);

        // Hairline divider
        View border = new View(this);
        border.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams pDiv = new LinearLayout.LayoutParams(-1, dp(1));
        pDiv.topMargin = dp(8);
        rootHeader.addView(border, pDiv);

        return rootHeader;
    }

    private View buildPermissionBanner() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(14), dp(12), dp(14), dp(12));
        banner.setBackground(createCardDrawable(COLOR_SURFACE, dp(14)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(14);
        banner.setLayoutParams(p);

        boolean hasPermission = checkStoragePermission();

        if (hasPermission) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView statusPill = createBadge("ALL-FILES ACCESS", COLOR_ELEVATED, COLOR_SUCCESS);
            row.addView(statusPill);

            TextView lbl = new TextView(this);
            lbl.setText(" Full storage access granted for media indexing");
            lbl.setTextSize(12);
            lbl.setTextColor(COLOR_TEXT_MUTED);
            lbl.setPadding(dp(8), 0, 0, 0);
            row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1.0f));

            banner.addView(row);
        } else {
            TextView warnTitle = new TextView(this);
            warnTitle.setText("Storage Access Restricted");
            warnTitle.setTextSize(14);
            warnTitle.setTypeface(Typeface.DEFAULT_BOLD);
            warnTitle.setTextColor(COLOR_WARNING);
            banner.addView(warnTitle);

            TextView warnDesc = new TextView(this);
            warnDesc.setText("Grant All-Files Storage Access so Jellyfin can index movies and music on internal and external drives.");
            warnDesc.setTextSize(12);
            warnDesc.setTextColor(COLOR_TEXT_MUTED);
            warnDesc.setPadding(0, dp(4), 0, dp(10));
            banner.addView(warnDesc);

            Button grantBtn = createPillButton("GRANT ALL-FILES ACCESS", COLOR_ACCENT, Color.WHITE);
            grantBtn.setOnClickListener(v -> requestStoragePermission());
            banner.addView(grantBtn);
        }

        return banner;
    }

    private View buildActivePathCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(createCardDrawable(COLOR_SURFACE, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);

        LinearLayout activeHeaderRow = new LinearLayout(this);
        activeHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        activeHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView header = new TextView(this);
        header.setText("ACTIVE JELLYFIN MEDIA PATH");
        header.setTextSize(10.5f);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setTextColor(COLOR_TEXT_MUTED);
        header.setLetterSpacing(0.08f);
        activeHeaderRow.addView(header, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnEnterPath = createPillSmallButton("ENTER PATH", COLOR_ELEVATED, COLOR_TEXT_PRIMARY);
        btnEnterPath.setOnClickListener(v -> showCustomPathDialog(activeSelectedPath));
        activeHeaderRow.addView(btnEnterPath);
        card.addView(activeHeaderRow);

        activePathText = new TextView(this);
        activePathText.setText(activeSelectedPath);
        activePathText.setTextSize(13f);
        activePathText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        activePathText.setTextColor(COLOR_TEXT_PRIMARY);
        activePathText.setPadding(0, dp(10), 0, dp(2));
        activePathText.setTextIsSelectable(true);
        card.addView(activePathText);

        activePathStatus = new TextView(this);
        activePathStatus.setTextSize(11f);
        activePathStatus.setTextColor(COLOR_SUCCESS);
        activePathStatus.setPadding(0, dp(2), 0, dp(12));
        card.addView(activePathStatus);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        btnCopyActivePath = new Button(this);
        btnCopyActivePath.setText("COPY PATH FOR JELLYFIN");
        btnCopyActivePath.setTextSize(12);
        btnCopyActivePath.setAllCaps(false);
        btnCopyActivePath.setTextColor(Color.WHITE);
        btnCopyActivePath.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnCopyActivePath.setBackground(createButtonRippleDrawable(COLOR_ACCENT, dp(8), Color.WHITE));
        btnCopyActivePath.setOnClickListener(v -> copyPathToClipboard(activeSelectedPath));
        actionRow.addView(btnCopyActivePath, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        View aSp = new View(this);
        actionRow.addView(aSp, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnOpenJellyfin = new Button(this);
        btnOpenJellyfin.setText("OPEN WEB UI");
        btnOpenJellyfin.setTextSize(12);
        btnOpenJellyfin.setAllCaps(false);
        btnOpenJellyfin.setTextColor(COLOR_TEXT_PRIMARY);
        btnOpenJellyfin.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnOpenJellyfin.setBackground(createButtonRippleDrawable(COLOR_ELEVATED, dp(8), COLOR_TEXT_PRIMARY));
        btnOpenJellyfin.setOnClickListener(v -> startActivity(new Intent(this, JellyfinWebActivity.class)));
        actionRow.addView(btnOpenJellyfin, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        card.addView(actionRow);

        // Row for Library Orphan Cleaner
        LinearLayout cleanRow = new LinearLayout(this);
        cleanRow.setOrientation(LinearLayout.HORIZONTAL);
        cleanRow.setPadding(0, dp(8), 0, 0);

        Button btnCleanOrphans = new Button(this);
        btnCleanOrphans.setText("PURGE ORPHAN MOVIE FOLDERS");
        btnCleanOrphans.setTextSize(11);
        btnCleanOrphans.setAllCaps(false);
        btnCleanOrphans.setTextColor(COLOR_TEXT_PRIMARY);
        btnCleanOrphans.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnCleanOrphans.setBackground(createButtonRippleDrawable(COLOR_ELEVATED, dp(8), COLOR_ACCENT));
        btnCleanOrphans.setOnClickListener(v -> {
            Toast.makeText(this, "Scanning and purging orphan folders...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                MediaStorageManager.OrphanFolderReport rep = MediaStorageManager.getInstance(this).cleanupOrphanMediaFolders();
                runOnUiThread(() -> {
                    String msg = "Purged " + rep.foldersDeleted + " empty folder(s) (" + rep.filesDeleted + " orphan metadata files removed)";
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    refreshUI();
                });
            }).start();
        });
        cleanRow.addView(btnCleanOrphans, new LinearLayout.LayoutParams(-1, dp(34)));
        card.addView(cleanRow);

        return card;
    }

    private View buildGuideCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(createCardDrawable(COLOR_SURFACE, dp(12)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(16);
        card.setLayoutParams(p);

        TextView guideTitle = new TextView(this);
        guideTitle.setText("How to Add in Jellyfin (3 Steps)");
        guideTitle.setTextSize(13);
        guideTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        guideTitle.setTextColor(COLOR_TEXT_PRIMARY);
        guideTitle.setPadding(0, 0, 0, dp(8));
        card.addView(guideTitle);

        TextView steps = new TextView(this);
        steps.setText(
                "1. Tap [ COPY PATH FOR JELLYFIN ] on your media folder above.\n" +
                "2. Open Jellyfin -> Dashboard -> Libraries -> Add Media Library.\n" +
                "3. Paste the path into the Folder field and tap Ok."
        );
        steps.setTextSize(12);
        steps.setTextColor(COLOR_TEXT_MUTED);
        steps.setLineSpacing(dp(4), 1.15f);
        card.addView(steps);

        return card;
    }

    private void refreshUI() {
        // Update Permission Banner
        if (permissionBanner != null) {
            LinearLayout parent = (LinearLayout) permissionBanner.getParent();
            if (parent != null) {
                int idx = parent.indexOfChild(permissionBanner);
                parent.removeViewAt(idx);
                permissionBanner = buildPermissionBanner();
                parent.addView(permissionBanner, idx);
            }
        }

        updateActivePathVerification();
        renderDrives();
        renderSavedFolders();
    }

    private void updateActivePathVerification() {
        if (activePathText != null) {
            activePathText.setText(activeSelectedPath);
        }

        if (activePathStatus != null) {
            StorageDriveHelper.PathVerification ver = StorageDriveHelper.verifyPath(this, activeSelectedPath);
            if (ver.isSafVerified && !ver.posixAccessible) {
                activePathStatus.setText("SAF GRANTED BUT POSIX BLOCKED (" + ver.mediaFileCount + " media files detected via SAF)\n" +
                        "Warning: Android Scoped Storage blocks native Jellyfin from reading external USB paths directly. Media library will fail to scan.");
                activePathStatus.setTextColor(COLOR_DANGER);
            } else if (ver.isAppSpecific && ver.posixAccessible) {
                activePathStatus.setText("VERIFIED APP DATA STORAGE (" + ver.mediaFileCount + " media files, " + ver.itemCount + " items) - 100% Native Jellyfin Access");
                activePathStatus.setTextColor(COLOR_SUCCESS);
            } else if (!ver.exists) {
                activePathStatus.setText("FOLDER NOT DETECTED (Directory not found on disk. Grant access via BROWSE SAF or use Internal Storage)");
                activePathStatus.setTextColor(COLOR_WARNING);
            } else if (ver.hasPermissionError || !ver.canRead || !ver.posixAccessible) {
                activePathStatus.setText("SELINUX PERMISSION BLOCKED (Android blocks native Jellyfin from reading this path. Use Internal Storage /storage/emulated/0/FishbowlMedia/)");
                activePathStatus.setTextColor(COLOR_DANGER);
            } else if (ver.mediaFileCount == 0 && ver.itemCount == 0) {
                activePathStatus.setText("EMPTY FOLDER DETECTED (0 media files found - Jellyfin library will be empty)");
                activePathStatus.setTextColor(COLOR_WARNING);
            } else if (ver.mediaFileCount == 0) {
                activePathStatus.setText("NO MEDIA FILES DETECTED (" + ver.itemCount + " items inside, but no video/audio files found)");
                activePathStatus.setTextColor(COLOR_WARNING);
            } else {
                activePathStatus.setText("VERIFIED FOR JELLYFIN (" + ver.mediaFileCount + " media files detected, " + ver.itemCount + " items - Native POSIX Ready)");
                activePathStatus.setTextColor(COLOR_SUCCESS);
            }
        }
    }

    private void renderDrives() {
        if (drivesContainer == null) return;
        drivesContainer.removeAllViews();

        List<StorageDriveHelper.DriveInfo> drives = StorageDriveHelper.getMountedDrives(this);

        for (StorageDriveHelper.DriveInfo drive : drives) {
            LinearLayout driveCard = new LinearLayout(this);
            driveCard.setOrientation(LinearLayout.VERTICAL);
            driveCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            driveCard.setBackground(createCardDrawable(COLOR_SURFACE, dp(14)));
            LinearLayout.LayoutParams pCard = new LinearLayout.LayoutParams(-1, -2);
            pCard.bottomMargin = dp(12);
            driveCard.setLayoutParams(pCard);

            // Row 1: Title, Type Badge, Active Status
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView driveName = new TextView(this);
            driveName.setText(drive.name);
            driveName.setTextSize(14.5f);
            driveName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            driveName.setTextColor(COLOR_TEXT_PRIMARY);
            topRow.addView(driveName, new LinearLayout.LayoutParams(0, -2, 1.0f));

            String typeStr = drive.isPrimary ? "INTERNAL" : (drive.isRemovable ? "USB / OTG" : "STORAGE");
            topRow.addView(createBadge(typeStr, COLOR_ELEVATED, COLOR_ACCENT));

            View spacer = new View(this);
            topRow.addView(spacer, new LinearLayout.LayoutParams(dp(6), 1));

            boolean isInUse = activeSelectedPath != null && activeSelectedPath.startsWith(drive.rootPath);
            if (isInUse) {
                topRow.addView(createBadge("IN USE", COLOR_ELEVATED, COLOR_SUCCESS));
            } else {
                topRow.addView(createBadge("MOUNTED", COLOR_ELEVATED, COLOR_TEXT_MUTED));
            }
            driveCard.addView(topRow);

            // Row 2: Mount Root Path
            TextView pathView = new TextView(this);
            pathView.setText(drive.rootPath);
            pathView.setTextSize(11.5f);
            pathView.setTypeface(Typeface.MONOSPACE);
            pathView.setTextColor(COLOR_TEXT_MUTED);
            pathView.setPadding(0, dp(4), 0, dp(4));
            driveCard.addView(pathView);

            // Row 3: Space Bar & Info
            if (drive.totalBytes > 0) {
                String capText = StorageDriveHelper.formatCapacity(drive.freeBytes, drive.totalBytes);
                TextView capView = new TextView(this);
                capView.setText(capText);
                capView.setTextSize(11);
                capView.setTextColor(COLOR_TEXT_MUTED);
                driveCard.addView(capView);

                View progressBar = createStorageProgressBar(drive.freeBytes, drive.totalBytes);
                driveCard.addView(progressBar);
            }

            // Row 4: Quick Media Folders Chips
            if (!drive.mediaFolders.isEmpty()) {
                TextView chipHeader = new TextView(this);
                chipHeader.setText("Quick Media Folders:");
                chipHeader.setTextSize(11);
                chipHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                chipHeader.setTextColor(COLOR_TEXT_MUTED);
                chipHeader.setPadding(0, dp(10), 0, dp(6));
                driveCard.addView(chipHeader);

                HorizontalScrollView chipScroll = new HorizontalScrollView(this);
                chipScroll.setHorizontalScrollBarEnabled(false);
                LinearLayout chipRow = new LinearLayout(this);
                chipRow.setOrientation(LinearLayout.HORIZONTAL);

                for (String folder : drive.mediaFolders) {
                    String fullFolder = drive.rootPath + "/" + folder;
                    String displayLabel;
                    if ("FishbowlMedia".equalsIgnoreCase(folder)) {
                        displayLabel = "All Media";
                    } else if (folder.startsWith("FishbowlMedia/")) {
                        displayLabel = folder.substring("FishbowlMedia/".length());
                    } else if (folder.contains("com.fishbowl.app/files")) {
                        displayLabel = "App Data";
                    } else {
                        displayLabel = folder;
                    }
                    TextView chip = createInteractiveChip(displayLabel, fullFolder.equalsIgnoreCase(activeSelectedPath));
                    chip.setOnClickListener(v -> setActivePath(fullFolder, true));
                    chipRow.addView(chip);
                }

                chipScroll.addView(chipRow);
                driveCard.addView(chipScroll);
            }

            // Row 5: Action Buttons (Minimal, clean layout)
            LinearLayout actionsRow = new LinearLayout(this);
            actionsRow.setOrientation(LinearLayout.HORIZONTAL);
            actionsRow.setPadding(0, dp(12), 0, 0);

            Button btnBrowse = createSmallActionButton("BROWSE FOLDER (SAF)");
            btnBrowse.setOnClickListener(v -> openFolderPicker());
            actionsRow.addView(btnBrowse, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

            View btnSpacer = new View(this);
            actionsRow.addView(btnSpacer, new LinearLayout.LayoutParams(dp(8), 1));

            Button btnRoot = createSmallActionButton("USE DRIVE ROOT");
            btnRoot.setOnClickListener(v -> setActivePath(drive.rootPath, true));
            actionsRow.addView(btnRoot, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

            driveCard.addView(actionsRow);
            drivesContainer.addView(driveCard);
        }
    }

    private void renderSavedFolders() {
        if (savedListContainer == null) return;
        savedListContainer.removeAllViews();

        Set<String> rawSavedSet = prefs.getStringSet("saved_folders", new HashSet<>());
        if (rawSavedSet.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText("No custom folders saved yet. Tap a folder chip or browse above.");
            emptyText.setTextSize(12);
            emptyText.setTextColor(COLOR_TEXT_MUTED);
            emptyText.setPadding(0, dp(4), 0, dp(8));
            savedListContainer.addView(emptyText);
            return;
        }

        Set<String> normalizedSet = new LinkedHashSet<>();
        boolean migrationNeeded = false;
        for (String s : rawSavedSet) {
            String norm = StorageDriveHelper.normalizeStoragePath(s);
            normalizedSet.add(norm);
            if (!norm.equals(s)) migrationNeeded = true;
        }
        if (migrationNeeded) {
            prefs.edit().putStringSet("saved_folders", normalizedSet).apply();
        }

        List<String> list = new ArrayList<>(normalizedSet);
        Collections.sort(list);

        for (String path : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(createCardDrawable(COLOR_SURFACE, dp(10)));
            LinearLayout.LayoutParams pRow = new LinearLayout.LayoutParams(-1, -2);
            pRow.bottomMargin = dp(8);
            row.setLayoutParams(pRow);

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);

            TextView pathTv = new TextView(this);
            pathTv.setText(path);
            pathTv.setTextSize(12);
            pathTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            pathTv.setTextColor(COLOR_TEXT_PRIMARY);
            infoCol.addView(pathTv);

            StorageDriveHelper.PathVerification vCheck = StorageDriveHelper.verifyPath(this, path);
            TextView statusTv = new TextView(this);
            statusTv.setTextSize(10);
            if (vCheck.isSafVerified && !vCheck.posixAccessible) {
                statusTv.setText(vCheck.mediaFileCount + " media files (SAF only - POSIX blocked for Jellyfin)");
                statusTv.setTextColor(COLOR_WARNING);
            } else if (vCheck.isAppSpecific && vCheck.posixAccessible) {
                statusTv.setText(vCheck.mediaFileCount + " media files (App Data - Native Access)");
                statusTv.setTextColor(COLOR_SUCCESS);
            } else if (!vCheck.exists) {
                statusTv.setText("Not detected on disk (Grant SAF or verify path)");
                statusTv.setTextColor(COLOR_WARNING);
            } else if (vCheck.hasPermissionError || !vCheck.canRead || !vCheck.posixAccessible) {
                statusTv.setText("Permission blocked by SELinux / Scoped Storage");
                statusTv.setTextColor(COLOR_DANGER);
            } else if (vCheck.mediaFileCount == 0) {
                statusTv.setText("0 media files detected");
                statusTv.setTextColor(COLOR_WARNING);
            } else {
                statusTv.setText(vCheck.mediaFileCount + " media files verified (" + vCheck.itemCount + " items)");
                statusTv.setTextColor(COLOR_SUCCESS);
            }
            infoCol.addView(statusTv);

            row.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

            Button btnUse = createPillSmallButton("USE", COLOR_ACCENT, Color.WHITE);
            btnUse.setOnClickListener(v -> setActivePath(path, true));
            row.addView(btnUse);

            View s1 = new View(this);
            row.addView(s1, new LinearLayout.LayoutParams(dp(6), 1));

            Button btnDel = createPillSmallButton("X", COLOR_ELEVATED, COLOR_DANGER);
            btnDel.setOnClickListener(v -> removeSavedFolder(path));
            row.addView(btnDel);

            savedListContainer.addView(row);
        }
    }

    private void setActivePath(String path, boolean saveHistory) {
        if (path == null || path.trim().isEmpty()) return;
        activeSelectedPath = StorageDriveHelper.normalizeStoragePath(path.trim());
        prefs.edit().putString("last_selected_path", activeSelectedPath).apply();

        if (saveHistory) {
            Set<String> rawSet = prefs.getStringSet("saved_folders", new HashSet<>());
            Set<String> set = new LinkedHashSet<>();
            for (String s : rawSet) {
                set.add(StorageDriveHelper.normalizeStoragePath(s));
            }
            set.add(activeSelectedPath);
            prefs.edit().putStringSet("saved_folders", set).apply();
        }

        updateActivePathVerification();
        renderDrives();
        renderSavedFolders();
        Toast.makeText(this, "Active path updated", Toast.LENGTH_SHORT).show();
    }

    private void removeSavedFolder(String path) {
        String normPath = StorageDriveHelper.normalizeStoragePath(path);
        Set<String> rawSet = prefs.getStringSet("saved_folders", new HashSet<>());
        Set<String> set = new LinkedHashSet<>();
        for (String s : rawSet) {
            String norm = StorageDriveHelper.normalizeStoragePath(s);
            if (!norm.equalsIgnoreCase(normPath)) {
                set.add(norm);
            }
        }
        prefs.edit().putStringSet("saved_folders", set).apply();
        renderSavedFolders();
    }

    private void copyPathToClipboard(String path) {
        final String normalizedPath = StorageDriveHelper.normalizeStoragePath(path);
        StorageDriveHelper.PathVerification ver = StorageDriveHelper.verifyPath(this, normalizedPath);

        if (!ver.exists) {
            new AlertDialog.Builder(this)
                    .setTitle("Folder Not Found")
                    .setMessage("The directory:\n" + normalizedPath + "\ndoes not exist on disk. If you add this path to Jellyfin, library indexing will fail.\n\nDo you still want to copy this path?")
                    .setPositiveButton("COPY ANYWAY", (d, w) -> doCopy(normalizedPath))
                    .setNegativeButton("CANCEL", null)
                    .show();
            return;
        }

        if (ver.isSafVerified && !ver.posixAccessible) {
            new AlertDialog.Builder(this)
                    .setTitle("External USB Storage - POSIX Blocked")
                    .setMessage("This folder is verified via Android Storage Access Framework (SAF), but Android Scoped Storage blocks native Linux processes like Jellyfin from reading files directly at:\n\n" + normalizedPath +
                            "\n\nIf you paste this path into Jellyfin, Jellyfin will report:\n'Could not find a part of the path'.\n\nRecommended: Use Internal Storage (/storage/emulated/0/FishbowlMedia/Movies) for full native POSIX compatibility.")
                    .setNeutralButton("SWITCH TO INTERNAL", (d, w) -> {
                        String internalPath = "/storage/emulated/0/FishbowlMedia/Movies";
                        setActivePath(internalPath, true);
                        doCopy(internalPath);
                        refreshUI();
                    })
                    .setPositiveButton("COPY ANYWAY", (d, w) -> doCopy(normalizedPath))
                    .setNegativeButton("CANCEL", null)
                    .show();
            return;
        }

        if (ver.hasPermissionError || !ver.canRead || !ver.posixAccessible) {
            new AlertDialog.Builder(this)
                    .setTitle("SELinux Permission Warning")
                    .setMessage("Android security (SELinux / Scoped Storage) blocks native processes like Jellyfin from reading files at this path:\n\n" + normalizedPath + "\n\nIf you add this path to Jellyfin, your library will be EMPTY.\n\nRecommended: Use Internal Storage (/storage/emulated/0/FishbowlMedia/Movies).")
                    .setNeutralButton("SWITCH TO INTERNAL", (d, w) -> {
                        String internalPath = "/storage/emulated/0/FishbowlMedia/Movies";
                        setActivePath(internalPath, true);
                        doCopy(internalPath);
                        refreshUI();
                    })
                    .setPositiveButton("COPY ANYWAY", (d, w) -> doCopy(normalizedPath))
                    .setNegativeButton("CANCEL", null)
                    .show();
            return;
        }

        if (ver.mediaFileCount == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Empty Folder Warning")
                    .setMessage("No video or audio files (.mp4, .mkv, .mp3, etc.) were detected in:\n\n" + normalizedPath + "\n\nIf you add this folder to Jellyfin now, the library will be EMPTY.\n\nPlease place your media files inside this folder before scanning in Jellyfin.\n\nDo you want to copy the path anyway?")
                    .setPositiveButton("COPY ANYWAY", (d, w) -> doCopy(normalizedPath))
                    .setNegativeButton("CANCEL", null)
                    .show();
            return;
        }

        doCopy(normalizedPath);
    }

    private void doCopy(String path) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Media Path", path);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
        }

        if (btnCopyActivePath != null) {
            btnCopyActivePath.setText("COPIED TO CLIPBOARD!");
            mainHandler.removeCallbacksAndMessages(null);
            mainHandler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed() && btnCopyActivePath != null) {
                    btnCopyActivePath.setText("COPY PATH FOR JELLYFIN");
                }
            }, 2000);
        }

        Toast.makeText(this, "Copied for Jellyfin: " + path, Toast.LENGTH_SHORT).show();
    }

    private void showCustomPathDialog(String initialPath) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Media Path");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(12), dp(20), dp(8));

        TextView hint = new TextView(this);
        hint.setText("Enter or paste any internal or external storage directory path for Jellyfin:");
        hint.setTextSize(12);
        hint.setTextColor(COLOR_TEXT_MUTED);
        hint.setPadding(0, 0, 0, dp(10));
        layout.addView(hint);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        String prefilled = initialPath != null ? StorageDriveHelper.normalizeStoragePath(initialPath) : "/storage/emulated/0/";
        input.setText(prefilled);
        input.setSelection(input.getText().length());
        input.setTextColor(COLOR_TEXT_PRIMARY);
        input.setHintTextColor(COLOR_TEXT_MUTED);
        input.setBackground(createCardDrawable(COLOR_ELEVATED, dp(8)));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(13);
        layout.addView(input);

        TextView tip = new TextView(this);
        tip.setText("Tip: Example internal media path:\n/storage/emulated/0/FishbowlMedia/Movies");
        tip.setTextSize(11);
        tip.setTextColor(COLOR_TEXT_MUTED);
        tip.setPadding(0, dp(10), 0, 0);
        layout.addView(tip);

        builder.setView(layout);

        builder.setPositiveButton("SELECT PATH", (dialog, which) -> {
            String entered = input.getText().toString().trim();
            if (!entered.isEmpty()) {
                String path = StorageDriveHelper.normalizeStoragePath(entered);
                if (path.contains("..") || path.contains("\0") || !path.startsWith("/")) {
                    Toast.makeText(this, "Invalid storage path. Must be an absolute path under /storage/ without '..'", Toast.LENGTH_SHORT).show();
                    return;
                }
                File rawDir = new File(path);
                File dir;
                String canonicalPath;
                try {
                    dir = rawDir.getCanonicalFile();
                    canonicalPath = dir.getCanonicalPath();
                } catch (Throwable t) {
                    Toast.makeText(this, "Invalid storage path resolution", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean isAllowedCanonical = canonicalPath.startsWith("/storage/") ||
                        canonicalPath.startsWith("/sdcard") ||
                        canonicalPath.startsWith("/data/data/com.fishbowl.app") ||
                        canonicalPath.startsWith("/data/user/0/com.fishbowl.app");
                if (!isAllowedCanonical || canonicalPath.contains("\0") || path.contains("..")) {
                    Toast.makeText(this, "Invalid storage path. Must be an absolute path under /storage/ without '..'", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!dir.exists()) {
                    try {
                        dir.mkdirs();
                    } catch (Throwable ignored) {}
                }
                setActivePath(canonicalPath, true);
                refreshUI();
                StorageDriveHelper.PathVerification ver = StorageDriveHelper.verifyPath(this, canonicalPath);
                if (!ver.exists) {
                    Toast.makeText(this, "Notice: Directory does not exist on disk.", Toast.LENGTH_SHORT).show();
                } else if (ver.isSafVerified && !ver.posixAccessible) {
                    Toast.makeText(this, "Notice: SAF granted, but Android Scoped Storage blocks native Jellyfin from reading external USB paths.", Toast.LENGTH_LONG).show();
                } else if (ver.hasPermissionError || !ver.canRead || !ver.posixAccessible) {
                    Toast.makeText(this, "Notice: Android SELinux blocks native Jellyfin from reading this path.", Toast.LENGTH_LONG).show();
                } else if (ver.mediaFileCount == 0) {
                    Toast.makeText(this, "Path selected: 0 media files detected in this folder.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Path selected (" + ver.mediaFileCount + " media files detected - Native POSIX Ready)", Toast.LENGTH_SHORT).show();
                }
            }
        });

        builder.setNegativeButton("CANCEL", null);
        builder.show();
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                } catch (Throwable ignored) {}
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 101);
        }
    }

    // UI Component Helpers synchronized with Fishbowl design language
    private GradientDrawable createCardDrawable(int bgColor, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(radiusPx > 0 ? radiusPx : dp(8));
        gd.setStroke(dp(1), COLOR_BORDER);
        return gd;
    }

    private GradientDrawable createRoundedFillDrawable(int bgColor, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(radiusPx > 0 ? radiusPx : dp(8));
        return gd;
    }

    private android.graphics.drawable.Drawable createButtonRippleDrawable(int bgColor, int radiusPx, int rippleColor) {
        GradientDrawable shape = createCardDrawable(bgColor, radiusPx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rCol = Color.argb(60, Color.red(rippleColor), Color.green(rippleColor), Color.blue(rippleColor));
            return new RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rCol),
                    shape,
                    null);
        }
        return shape;
    }

    private TextView createBadge(String text, int bgColor, int textColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setTextColor(textColor);
        tv.setBackground(createCardDrawable(bgColor, dp(8)));
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        return tv;
    }

    private TextView createInteractiveChip(String folderName, boolean isSelected) {
        TextView chip = new TextView(this);
        chip.setText(folderName);
        chip.setTextSize(12);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        int bg = isSelected ? COLOR_ACCENT : COLOR_ELEVATED;
        int txt = isSelected ? Color.WHITE : COLOR_TEXT_PRIMARY;
        chip.setBackground(createCardDrawable(bg, dp(8)));
        chip.setTextColor(txt);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = dp(8);
        chip.setLayoutParams(p);
        return chip;
    }

    private View createStorageProgressBar(long freeBytes, long totalBytes) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(createRoundedFillDrawable(COLOR_ELEVATED, dp(3)));
        LinearLayout.LayoutParams pTrack = new LinearLayout.LayoutParams(-1, dp(6));
        pTrack.topMargin = dp(6);
        pTrack.bottomMargin = dp(4);
        track.setLayoutParams(pTrack);

        long usedBytes = Math.max(0, totalBytes - freeBytes);
        float usedRatio = totalBytes > 0 ? (float) usedBytes / totalBytes : 0f;
        usedRatio = Math.max(0.02f, Math.min(1.0f, usedRatio));

        View fill = new View(this);
        fill.setBackground(createRoundedFillDrawable(COLOR_ACCENT, dp(3)));
        LinearLayout.LayoutParams pFill = new LinearLayout.LayoutParams(0, -1, usedRatio);
        fill.setLayoutParams(pFill);
        track.addView(fill);

        View empty = new View(this);
        LinearLayout.LayoutParams pEmpty = new LinearLayout.LayoutParams(0, -1, 1.0f - usedRatio);
        empty.setLayoutParams(pEmpty);
        track.addView(empty);

        return track;
    }

    private Button createSmallActionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(COLOR_TEXT_PRIMARY);
        b.setBackground(createButtonRippleDrawable(COLOR_ELEVATED, dp(8), COLOR_ACCENT));
        return b;
    }

    private Button createPillButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(textColor);
        b.setBackground(createButtonRippleDrawable(bgColor, dp(8), Color.WHITE));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(40));
        p.topMargin = dp(6);
        b.setLayoutParams(p);
        return b;
    }

    private Button createPillSmallButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(textColor);
        b.setBackground(createButtonRippleDrawable(bgColor, dp(8), textColor));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(32));
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
