package com.fishbowl.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JellyfinStorageActivity extends AppCompatActivity {

    private static final int COLOR_BG = Color.parseColor("#121316");
    private static final int COLOR_SURFACE = Color.parseColor("#1E2025");
    private static final int COLOR_ELEVATED = Color.parseColor("#272930");
    private static final int COLOR_ACCENT = Color.parseColor("#00A4DC");
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#E6E8EE");
    private static final int COLOR_TEXT_MUTED = Color.parseColor("#9AA0A6");
    private static final int COLOR_SUCCESS = Color.parseColor("#81C784");
    private static final int COLOR_WARNING = Color.parseColor("#FFB74D");
    private static final int COLOR_DANGER = Color.parseColor("#E57373");

    private SharedPreferences prefs;
    private String activeSelectedPath = "/storage/emulated/0/Movies";
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
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (Throwable ignored) {}

                        String resolved = StorageDriveHelper.resolveTreeUriToPath(this, treeUri);
                        if (resolved != null && !resolved.trim().isEmpty()) {
                            resolved = StorageDriveHelper.normalizeStoragePath(resolved);
                            setActivePath(resolved, true);
                            Toast.makeText(this, "SAF Path (Beta Testing): " + resolved, Toast.LENGTH_SHORT).show();
                        } else {
                            new androidx.appcompat.app.AlertDialog.Builder(this)
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

        prefs = getSharedPreferences("jellyfindroid_storage", MODE_PRIVATE);
        String savedLast = prefs.getString("last_selected_path", null);
        if (savedLast != null && !savedLast.trim().isEmpty()) {
            activeSelectedPath = StorageDriveHelper.normalizeStoragePath(savedLast);
            if (!activeSelectedPath.equals(savedLast)) {
                prefs.edit().putString("last_selected_path", activeSelectedPath).apply();
            }
        } else {
            activeSelectedPath = "/storage/emulated/0/Movies";
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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            androidx.core.graphics.Insets navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(dp(16), statusBarInset.top + dp(12), dp(16), navBarInset.bottom + dp(20));
            return insets;
        });

        // Top Header Bar
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, dp(14));

        TextView btnBack = new TextView(this);
        btnBack.setText("< BACK");
        btnBack.setTextSize(13);
        btnBack.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnBack.setTextColor(COLOR_ACCENT);
        btnBack.setPadding(0, dp(6), dp(14), dp(6));
        btnBack.setOnClickListener(v -> finish());
        headerRow.addView(btnBack);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Media Storage & Folders");
        title.setTextSize(20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(COLOR_TEXT_PRIMARY);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Select your media path and copy directly into Jellyfin");
        subtitle.setTextSize(12);
        subtitle.setTextColor(COLOR_TEXT_MUTED);
        titleCol.addView(subtitle);

        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));
        root.addView(headerRow);

        // Permission Status Banner
        permissionBanner = buildPermissionBanner();
        root.addView(permissionBanner);

        // Active Selected Path Card (Prominent at the top)
        root.addView(buildActivePathCard());

        // Detected Storage Drives Card
        TextView drivesHeader = new TextView(this);
        drivesHeader.setText("CONNECTED STORAGE DRIVES");
        drivesHeader.setTextSize(12);
        drivesHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        drivesHeader.setTextColor(COLOR_ACCENT);
        drivesHeader.setPadding(0, dp(18), 0, dp(8));
        root.addView(drivesHeader);

        drivesContainer = new LinearLayout(this);
        drivesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(drivesContainer);

        // Saved Folders Section
        TextView savedHeader = new TextView(this);
        savedHeader.setText("SAVED MEDIA PATHS");
        savedHeader.setTextSize(12);
        savedHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        savedHeader.setTextColor(COLOR_ACCENT);
        savedHeader.setPadding(0, dp(18), 0, dp(8));
        root.addView(savedHeader);

        savedListContainer = new LinearLayout(this);
        savedListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(savedListContainer);

        // Quick 3-Step Guide Card
        root.addView(buildGuideCard());

        scroll.addView(root);
        return scroll;
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
        card.setBackground(createCardDrawable(COLOR_SURFACE, dp(14)));

        LinearLayout activeHeaderRow = new LinearLayout(this);
        activeHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        activeHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView header = new TextView(this);
        header.setText("ACTIVE SELECTED MEDIA PATH");
        header.setTextSize(12);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setTextColor(COLOR_ACCENT);
        activeHeaderRow.addView(header, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnEnterPath = createPillSmallButton("ENTER PATH", COLOR_ELEVATED, COLOR_ACCENT);
        btnEnterPath.setOnClickListener(v -> showCustomPathDialog(activeSelectedPath));
        activeHeaderRow.addView(btnEnterPath);
        card.addView(activeHeaderRow);

        // Path Display Box
        LinearLayout pathBox = new LinearLayout(this);
        pathBox.setOrientation(LinearLayout.VERTICAL);
        pathBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        pathBox.setBackground(createCardDrawable(COLOR_ELEVATED, dp(10)));
        LinearLayout.LayoutParams pBox = new LinearLayout.LayoutParams(-1, -2);
        pBox.topMargin = dp(10);
        pBox.bottomMargin = dp(12);
        pathBox.setLayoutParams(pBox);

        activePathText = new TextView(this);
        activePathText.setText(activeSelectedPath);
        activePathText.setTextSize(14);
        activePathText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        activePathText.setTextColor(COLOR_TEXT_PRIMARY);
        activePathText.setTextIsSelectable(true);
        pathBox.addView(activePathText);

        activePathStatus = new TextView(this);
        activePathStatus.setTextSize(11);
        activePathStatus.setPadding(0, dp(4), 0, 0);
        pathBox.addView(activePathStatus);

        card.addView(pathBox);

        // Primary Prominent COPY Button
        btnCopyActivePath = new Button(this);
        btnCopyActivePath.setText("COPY PATH FOR JELLYFIN");
        btnCopyActivePath.setTextSize(14);
        btnCopyActivePath.setAllCaps(false);
        btnCopyActivePath.setTextColor(Color.WHITE);
        btnCopyActivePath.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnCopyActivePath.setBackground(createButtonRippleDrawable(COLOR_ACCENT, dp(10), Color.WHITE));
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(-1, dp(44));
        btnCopyActivePath.setLayoutParams(pBtn);
        btnCopyActivePath.setOnClickListener(v -> copyPathToClipboard(activeSelectedPath));
        card.addView(btnCopyActivePath);

        // Secondary Action: Open Jellyfin Web UI
        Button btnOpenJellyfin = new Button(this);
        btnOpenJellyfin.setText("OPEN JELLYFIN WEB UI");
        btnOpenJellyfin.setTextSize(13);
        btnOpenJellyfin.setAllCaps(false);
        btnOpenJellyfin.setTextColor(COLOR_TEXT_PRIMARY);
        btnOpenJellyfin.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnOpenJellyfin.setBackground(createButtonRippleDrawable(COLOR_ELEVATED, dp(10), COLOR_ACCENT));
        LinearLayout.LayoutParams pOpen = new LinearLayout.LayoutParams(-1, dp(40));
        pOpen.topMargin = dp(8);
        btnOpenJellyfin.setLayoutParams(pOpen);
        btnOpenJellyfin.setOnClickListener(v -> {
            Intent intent = new Intent(this, JellyfinWebActivity.class);
            startActivity(intent);
        });
        card.addView(btnOpenJellyfin);

        return card;
    }

    private View buildGuideCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(createCardDrawable(COLOR_SURFACE, dp(14)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(16);
        card.setLayoutParams(p);

        TextView guideTitle = new TextView(this);
        guideTitle.setText("How to Add in Jellyfin (3 Steps)");
        guideTitle.setTextSize(14);
        guideTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        guideTitle.setTextColor(COLOR_ACCENT);
        guideTitle.setPadding(0, 0, 0, dp(8));
        card.addView(guideTitle);

        TextView steps = new TextView(this);
        steps.setText(
                "1. Tap [ COPY PATH FOR JELLYFIN ] on your media folder above.\n" +
                "2. Open Jellyfin -> Dashboard -> Libraries -> Add Media Library.\n" +
                "3. Paste the path into the Folder field and tap Ok."
        );
        steps.setTextSize(12);
        steps.setTextColor(COLOR_TEXT_PRIMARY);
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
            StorageDriveHelper.PathVerification ver = StorageDriveHelper.verifyPath(activeSelectedPath);
            if (!ver.exists) {
                activePathStatus.setText("FOLDER NOT FOUND (Directory does not exist on disk)");
                activePathStatus.setTextColor(COLOR_WARNING);
            } else if (ver.hasPermissionError || !ver.canRead) {
                activePathStatus.setText("SELINUX PERMISSION BLOCKED (Jellyfin cannot read this path. Use App Data folder or grant permissions)");
                activePathStatus.setTextColor(Color.parseColor("#E06C75"));
            } else if (ver.mediaFileCount == 0 && ver.itemCount == 0) {
                activePathStatus.setText("EMPTY FOLDER DETECTED (0 media files found - Jellyfin library will be empty)");
                activePathStatus.setTextColor(COLOR_WARNING);
            } else if (ver.mediaFileCount == 0) {
                activePathStatus.setText("NO MEDIA FILES DETECTED (" + ver.itemCount + " items inside, but no video/audio files found)");
                activePathStatus.setTextColor(COLOR_WARNING);
            } else {
                activePathStatus.setText("VERIFIED FOR JELLYFIN (" + ver.mediaFileCount + " media files detected, " + ver.itemCount + " items)");
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
            driveName.setTextSize(15);
            driveName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            driveName.setTextColor(COLOR_TEXT_PRIMARY);
            topRow.addView(driveName, new LinearLayout.LayoutParams(0, -2, 1.0f));

            String typeStr = drive.isPrimary ? "INTERNAL" : (drive.isRemovable ? "USB / OTG" : "STORAGE");
            topRow.addView(createBadge(typeStr, COLOR_ELEVATED, COLOR_ACCENT));

            View spacer = new View(this);
            topRow.addView(spacer, new LinearLayout.LayoutParams(dp(6), 1));

            if (!drive.isPrimary) {
                topRow.addView(createBadge("BETA TESTING", Color.parseColor("#3A2E1A"), COLOR_WARNING));
                View spacerBeta = new View(this);
                topRow.addView(spacerBeta, new LinearLayout.LayoutParams(dp(6), 1));
            }

            topRow.addView(createBadge("ACTIVE", COLOR_ELEVATED, COLOR_SUCCESS));
            driveCard.addView(topRow);

            // Row 2: Mount Root Path
            TextView pathView = new TextView(this);
            pathView.setText(drive.rootPath);
            pathView.setTextSize(12);
            pathView.setTypeface(Typeface.MONOSPACE);
            pathView.setTextColor(COLOR_TEXT_MUTED);
            pathView.setPadding(0, dp(4), 0, dp(drive.isPrimary ? 6 : 2));
            driveCard.addView(pathView);

            if (!drive.isPrimary) {
                TextView betaNotice = new TextView(this);
                betaNotice.setText("Notice: External and OTG storage integration is currently in beta testing mode.");
                betaNotice.setTextSize(11);
                betaNotice.setTextColor(COLOR_WARNING);
                betaNotice.setPadding(0, 0, 0, dp(6));
                driveCard.addView(betaNotice);
            }

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
                    String displayLabel = folder.contains("com.fishbowl.app/files") ? "App Data (Guaranteed)" : folder;
                    TextView chip = createInteractiveChip(displayLabel, fullFolder.equalsIgnoreCase(activeSelectedPath));
                    chip.setOnClickListener(v -> setActivePath(fullFolder, true));
                    chipRow.addView(chip);
                }

                chipScroll.addView(chipRow);
                driveCard.addView(chipScroll);
            }

            // Row 5: Action Buttons
            LinearLayout actionsRow1 = new LinearLayout(this);
            actionsRow1.setOrientation(LinearLayout.HORIZONTAL);
            actionsRow1.setPadding(0, dp(12), 0, 0);

            Button btnBrowse = createSmallActionButton("BROWSE (SAF)");
            btnBrowse.setOnClickListener(v -> openFolderPicker());
            actionsRow1.addView(btnBrowse, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

            View btnSpacer1 = new View(this);
            actionsRow1.addView(btnSpacer1, new LinearLayout.LayoutParams(dp(8), 1));

            Button btnEnter = createSmallActionButton("ENTER PATH");
            btnEnter.setOnClickListener(v -> showCustomPathDialog(drive.rootPath + "/"));
            actionsRow1.addView(btnEnter, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

            driveCard.addView(actionsRow1);

            LinearLayout actionsRow2 = new LinearLayout(this);
            actionsRow2.setOrientation(LinearLayout.HORIZONTAL);
            actionsRow2.setPadding(0, dp(8), 0, 0);

            Button btnRoot = createSmallActionButton("USE DRIVE ROOT (" + drive.rootPath + ")");
            btnRoot.setOnClickListener(v -> setActivePath(drive.rootPath, true));
            actionsRow2.addView(btnRoot, new LinearLayout.LayoutParams(-1, dp(36)));

            driveCard.addView(actionsRow2);
            drivesContainer.addView(driveCard);
        }
    }

    private void renderSavedFolders() {
        if (savedListContainer == null) return;
        savedListContainer.removeAllViews();

        Set<String> rawSavedSet = prefs.getStringSet("saved_folders", new HashSet<>());
        if (rawSavedSet.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText("No custom folders saved yet. Use Browse Folder or tap a folder chip above.");
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
        java.util.Collections.sort(list);

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

            StorageDriveHelper.PathVerification vCheck = StorageDriveHelper.verifyPath(path);
            TextView statusTv = new TextView(this);
            statusTv.setTextSize(10);
            if (!vCheck.exists) {
                statusTv.setText("Not found on disk");
                statusTv.setTextColor(COLOR_WARNING);
            } else if (vCheck.hasPermissionError || !vCheck.canRead) {
                statusTv.setText("Permission blocked by SELinux");
                statusTv.setTextColor(Color.parseColor("#E06C75"));
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
        StorageDriveHelper.PathVerification ver = StorageDriveHelper.verifyPath(normalizedPath);

        if (!ver.exists) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Folder Not Found")
                    .setMessage("The directory:\n" + normalizedPath + "\ndoes not exist on disk. If you add this path to Jellyfin, library indexing will fail.\n\nDo you still want to copy this path?")
                    .setPositiveButton("COPY ANYWAY", (d, w) -> doCopy(normalizedPath))
                    .setNegativeButton("CANCEL", null)
                    .show();
            return;
        }

        if (ver.hasPermissionError || !ver.canRead) {
            List<StorageDriveHelper.DriveInfo> drives = StorageDriveHelper.getMountedDrives(this);
            String suggestedAppPath = null;
            for (StorageDriveHelper.DriveInfo drive : drives) {
                if (!drive.isPrimary && normalizedPath.startsWith(drive.rootPath)) {
                    suggestedAppPath = drive.rootPath + "/Android/data/" + getPackageName() + "/files";
                    break;
                }
            }

            final String finalAppPath = suggestedAppPath;
            androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(this);
            b.setTitle("SELinux Permission Warning");
            String msg = "Android security (SELinux) blocks native processes like Jellyfin from reading files at this path:\n\n" + normalizedPath + "\n\nIf you add this path to Jellyfin, your library will be EMPTY.";
            if (finalAppPath != null) {
                msg += "\n\nRecommended: Use the guaranteed App Data folder on external storage:\n" + finalAppPath;
                b.setNeutralButton("SWITCH & COPY APP DATA", (d, w) -> {
                    setActivePath(finalAppPath, true);
                    doCopy(finalAppPath);
                });
            }
            b.setMessage(msg);
            b.setPositiveButton("COPY ANYWAY", (d, w) -> doCopy(normalizedPath));
            b.setNegativeButton("CANCEL", null);
            b.show();
            return;
        }

        if (ver.mediaFileCount == 0) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
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
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
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
        tip.setText("Tip: To prevent empty libraries on external drives, you can use the guaranteed app folder:\n/storage/<UUID>/Android/data/com.fishbowl.app/files");
        tip.setTextSize(11);
        tip.setTextColor(COLOR_TEXT_MUTED);
        tip.setPadding(0, dp(10), 0, 0);
        layout.addView(tip);

        builder.setView(layout);

        builder.setPositiveButton("SELECT PATH", (dialog, which) -> {
            String entered = input.getText().toString().trim();
            if (!entered.isEmpty()) {
                String path = StorageDriveHelper.normalizeStoragePath(entered);
                boolean isAllowedPrefix = path.startsWith("/storage/") ||
                        path.startsWith("/sdcard") ||
                        path.startsWith("/data/data/com.fishbowl.app") ||
                        path.startsWith("/data/user/0/com.fishbowl.app");
                if (path.contains("..") || path.contains("\0") || !isAllowedPrefix) {
                    Toast.makeText(this, "Invalid storage path. Must be an absolute path under /storage/ without '..'", Toast.LENGTH_SHORT).show();
                    return;
                }
                File dir = new File(path);
                if (!dir.exists()) {
                    try {
                        dir.mkdirs();
                    } catch (Throwable ignored) {}
                }
                setActivePath(path, true);
                StorageDriveHelper.PathVerification ver = StorageDriveHelper.verifyPath(path);
                if (!ver.exists) {
                    Toast.makeText(this, "Notice: Directory does not exist on disk.", Toast.LENGTH_SHORT).show();
                } else if (ver.hasPermissionError || !ver.canRead) {
                    Toast.makeText(this, "Notice: Android SELinux may block Jellyfin from reading this path.", Toast.LENGTH_LONG).show();
                } else if (ver.mediaFileCount == 0) {
                    Toast.makeText(this, "Path selected: 0 media files detected in this folder.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Path selected (" + ver.mediaFileCount + " media files detected)", Toast.LENGTH_SHORT).show();
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

    // UI Component Helpers
    private GradientDrawable createCardDrawable(int bgColor, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    private android.graphics.drawable.Drawable createButtonRippleDrawable(int bgColor, int radiusPx, int rippleColor) {
        GradientDrawable shape = createCardDrawable(bgColor, radiusPx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rCol = Color.argb(60, Color.red(rippleColor), Color.green(rippleColor), Color.blue(rippleColor));
            return new android.graphics.drawable.RippleDrawable(
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
        tv.setBackground(createCardDrawable(bgColor, dp(6)));
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
        track.setBackground(createCardDrawable(COLOR_ELEVATED, dp(3)));
        LinearLayout.LayoutParams pTrack = new LinearLayout.LayoutParams(-1, dp(6));
        pTrack.topMargin = dp(6);
        pTrack.bottomMargin = dp(4);
        track.setLayoutParams(pTrack);

        long usedBytes = Math.max(0, totalBytes - freeBytes);
        float usedRatio = totalBytes > 0 ? (float) usedBytes / totalBytes : 0f;
        usedRatio = Math.max(0.02f, Math.min(1.0f, usedRatio));

        View fill = new View(this);
        fill.setBackground(createCardDrawable(COLOR_ACCENT, dp(3)));
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
        b.setBackground(createButtonRippleDrawable(COLOR_ELEVATED, dp(10), COLOR_ACCENT));
        return b;
    }

    private Button createPillButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(textColor);
        b.setBackground(createButtonRippleDrawable(bgColor, dp(10), Color.WHITE));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(40));
        p.topMargin = dp(4);
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
