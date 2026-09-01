package com.fishbowl.app;

import android.Manifest;
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
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public final class JellyfinStorageActivity extends AppCompatActivity {
    private TextView permissionStatus;
    private LinearLayout folderListContainer;
    private SharedPreferences prefs;

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
                        } catch (Exception ignored) {}
                        saveSelectedFolder(treeUri);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupSystemBars();
        prefs = getSharedPreferences("jellyfindroid_storage", MODE_PRIVATE);
        setContentView(createView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controllerCompat = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        boolean isDark = isDarkTheme();
        controllerCompat.setAppearanceLightStatusBars(!isDark);
        controllerCompat.setAppearanceLightNavigationBars(!isDark);
        int color = isDark ? Color.parseColor("#121316") : Color.parseColor("#F8F9FA");
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
    }

    private boolean isDarkTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private View createView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(isDarkTheme() ? Color.parseColor("#121316") : Color.parseColor("#F8F9FA"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            androidx.core.graphics.Insets navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(dp(20), statusBarInset.top + dp(16), dp(20), navBarInset.bottom + dp(16));
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("Media Storage & Folders");
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(isDarkTheme() ? Color.parseColor("#1E2025") : Color.parseColor("#FFFFFF"));
        gd.setCornerRadius(dp(20));
        card.setBackground(gd);

        permissionStatus = new TextView(this);
        permissionStatus.setTextSize(15);
        permissionStatus.setTypeface(Typeface.DEFAULT_BOLD);
        permissionStatus.setPadding(0, 0, 0, dp(16));
        card.addView(permissionStatus);

        Button grantBtn = createPixelButton("GRANT STORAGE PERMISSION", Color.parseColor("#00A4DC"), Color.WHITE);
        grantBtn.setOnClickListener(v -> requestStoragePermission());
        card.addView(grantBtn);

        Button pickFolderBtn = createPixelButton("SELECT MEDIA FOLDER (SAF)", isDarkTheme() ? Color.parseColor("#272930") : Color.parseColor("#F1F3F9"), isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        pickFolderBtn.setOnClickListener(v -> openFolderPicker());
        card.addView(pickFolderBtn);

        TextView folderHeader = new TextView(this);
        folderHeader.setText("ACCESSIBLE FOLDERS:");
        folderHeader.setTextSize(14);
        folderHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        folderHeader.setTextColor(Color.parseColor("#00A4DC"));
        folderHeader.setPadding(0, dp(20), 0, dp(8));
        card.addView(folderHeader);

        folderListContainer = new LinearLayout(this);
        folderListContainer.setOrientation(LinearLayout.VERTICAL);
        folderListContainer.setPadding(0, 0, 0, dp(12));
        card.addView(folderListContainer);

        Button clearBtn = createPixelButton("CLEAR SAVED FOLDERS", isDarkTheme() ? Color.parseColor("#272930") : Color.parseColor("#F1F3F9"), isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        clearBtn.setOnClickListener(v -> {
            prefs.edit().remove("folders").apply();
            updateUI();
        });
        card.addView(clearBtn);

        root.addView(card);

        // Simple Step-by-Step Tutorial Card
        LinearLayout tutorialCard = new LinearLayout(this);
        tutorialCard.setOrientation(LinearLayout.VERTICAL);
        tutorialCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable tutGd = new GradientDrawable();
        tutGd.setColor(isDarkTheme() ? Color.parseColor("#1E1F24") : Color.WHITE);
        tutGd.setCornerRadius(dp(18));
        tutorialCard.setBackground(tutGd);
        LinearLayout.LayoutParams pTutorial = new LinearLayout.LayoutParams(-1, -2);
        pTutorial.topMargin = dp(14);
        tutorialCard.setLayoutParams(pTutorial);

        TextView tutorialHeader = new TextView(this);
        tutorialHeader.setText("📖 How to Add Media Folders (Simple Guide)");
        tutorialHeader.setTextSize(16);
        tutorialHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tutorialHeader.setTextColor(Color.parseColor("#00A4DC"));
        tutorialHeader.setPadding(0, 0, 0, dp(10));
        tutorialCard.addView(tutorialHeader);

        TextView tutorialText = new TextView(this);
        tutorialText.setTextSize(13);
        tutorialText.setTextColor(isDarkTheme() ? Color.parseColor("#D1D5DB") : Color.parseColor("#374151"));
        tutorialText.setLineSpacing(dp(3), 1.1f);
        String guide = "Follow these 3 easy steps to add movies or TV shows to your Jellyfin library:\n\n"
                + "Step 1: Pick Your Folder\n"
                + "Tap 'SELECT MEDIA FOLDER (SAF)' above and choose your movie or music folder. Tap 'ALLOW ACCESS'.\n\n"
                + "Step 2: Copy the POSIX Path\n"
                + "Tap the 'COPY PATH' button next to your folder below.\n\n"
                + "Step 3: Add Path in Jellyfin Web UI\n"
                + "Open Jellyfin ➔ Settings (⚙️) ➔ Dashboard ➔ Libraries ➔ Add Media Library. Paste the path into the Folder field!";
        tutorialText.setText(guide);
        tutorialCard.addView(tutorialText);

        root.addView(tutorialCard);
        scroll.addView(root);
        return scroll;
    }

    private void updateUI() {
        boolean hasPermission = checkStoragePermission();
        if (hasPermission) {
            permissionStatus.setText("FULL ALL-FILES STORAGE ACCESS GRANTED (RECOMMENDED)");
            permissionStatus.setTextColor(Color.parseColor("#81C784"));
        } else {
            permissionStatus.setText("ALL-FILES ACCESS NOT GRANTED (RECOMMENDED FOR DIRECT READS)");
            permissionStatus.setTextColor(Color.parseColor("#E57373"));
        }

        folderListContainer.removeAllViews();

        // 1. Default Storage Paths with COPY buttons
        folderListContainer.addView(createPathRow("Shared Internal Storage", "/storage/emulated/0"));
        folderListContainer.addView(createPathRow("External SD Cards / USB", "/storage"));

        Set<String> folders = prefs.getStringSet("folders", new HashSet<>());
        if (!folders.isEmpty()) {
            TextView safHeader = new TextView(this);
            safHeader.setText("SAF PICKED FOLDERS:");
            safHeader.setTextSize(12);
            safHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            safHeader.setTextColor(Color.parseColor("#00A4DC"));
            safHeader.setPadding(0, dp(12), 0, dp(6));
            folderListContainer.addView(safHeader);

            for (String f : folders) {
                String cleanPath = extractCleanPath(f);
                folderListContainer.addView(createPathRow(f, cleanPath));
            }
        }

        TextView noteText = new TextView(this);
        noteText.setText("💡 Simple Note: Jellyfin requires clean file paths (like /storage/emulated/0/Movies) to scan your media files. Use the COPY PATH buttons above to easily copy paths for Jellyfin Library setup!");
        noteText.setTextSize(12);
        noteText.setTextColor(isDarkTheme() ? Color.parseColor("#9AA0A6") : Color.parseColor("#5F6368"));
        noteText.setPadding(0, dp(12), 0, 0);
        folderListContainer.addView(noteText);
    }

    private String extractCleanPath(String entry) {
        if (entry.contains("(POSIX: ")) {
            int start = entry.indexOf("(POSIX: ") + 8;
            int end = entry.indexOf(")", start);
            if (end > start) {
                return entry.substring(start, end);
            }
        }
        return entry;
    }

    private View createPathRow(String labelText, String pathText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText(labelText);
        lbl.setTextSize(11);
        lbl.setTextColor(isDarkTheme() ? Color.parseColor("#9AA0A6") : Color.parseColor("#5F6368"));
        info.addView(lbl);

        TextView val = new TextView(this);
        val.setText(pathText);
        val.setTextSize(13);
        val.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        val.setTextColor(isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        val.setPadding(0, dp(2), 0, 0);
        info.addView(val);

        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnCopy = new Button(this);
        btnCopy.setText("COPY PATH");
        btnCopy.setTextSize(11);
        btnCopy.setAllCaps(false);
        btnCopy.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnCopy.setTextColor(Color.WHITE);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#00A4DC"));
        gd.setCornerRadius(dp(14));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255)),
                    gd,
                    null);
            btnCopy.setBackground(ripple);
        } else {
            btnCopy.setBackground(gd);
        }
        btnCopy.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start();
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
            }
            return false;
        });
        btnCopy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Folder Path", pathText);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Copied: " + pathText, Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(90), dp(32));
        btnParams.leftMargin = dp(8);
        row.addView(btnCopy, btnParams);

        return row;
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
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 101);
        }
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private void saveSelectedFolder(Uri treeUri) {
        String pathOrUri = treeUri.toString();
        // Try parsing raw POSIX path from SAF Document Tree URI (e.g. content://com.android.externalstorage.documents/tree/primary%3AMovies)
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            if (docId != null) {
                String[] parts = docId.split(":");
                if (parts.length >= 2) {
                    String type = parts[0];
                    String relativePath = parts[1];
                    if ("primary".equalsIgnoreCase(type)) {
                        pathOrUri = "/storage/emulated/0/" + relativePath + " (POSIX: /storage/emulated/0/" + relativePath + ")";
                    } else {
                        pathOrUri = "/storage/" + type + "/" + relativePath + " (POSIX: /storage/" + type + "/" + relativePath + ")";
                    }
                }
            }
        } catch (Exception ignored) {}

        Set<String> set = new HashSet<>(prefs.getStringSet("folders", new HashSet<>()));
        set.add(pathOrUri);
        prefs.edit().putStringSet("folders", set).apply();
        Toast.makeText(this, "Folder path added to Fishbowl storage bridge", Toast.LENGTH_SHORT).show();
        updateUI();
    }

    private Button createPixelButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(20));
        b.setBackground(gd);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44));
        p.topMargin = dp(6);
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
