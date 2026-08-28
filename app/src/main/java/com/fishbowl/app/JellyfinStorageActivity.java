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
    private TextView folderListText;
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
                        saveSelectedFolder(treeUri.toString());
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

        folderListText = new TextView(this);
        folderListText.setTextSize(13);
        folderListText.setTextColor(isDarkTheme() ? Color.parseColor("#9AA0A6") : Color.parseColor("#5F6368"));
        folderListText.setPadding(0, 0, 0, dp(16));
        card.addView(folderListText);

        Button clearBtn = createPixelButton("CLEAR SAVED FOLDERS", isDarkTheme() ? Color.parseColor("#272930") : Color.parseColor("#F1F3F9"), isDarkTheme() ? Color.parseColor("#E6E8EE") : Color.parseColor("#1F2024"));
        clearBtn.setOnClickListener(v -> {
            prefs.edit().remove("folders").apply();
            updateUI();
        });
        card.addView(clearBtn);

        root.addView(card);
        scroll.addView(root);
        return scroll;
    }

    private void updateUI() {
        boolean hasPermission = checkStoragePermission();
        if (hasPermission) {
            permissionStatus.setText("MEDIA ACCESS GRANTED");
            permissionStatus.setTextColor(Color.parseColor("#81C784"));
        } else {
            permissionStatus.setText("MEDIA ACCESS MISSING (ACTION REQUIRED)");
            permissionStatus.setTextColor(Color.parseColor("#E57373"));
        }

        Set<String> folders = prefs.getStringSet("folders", new HashSet<>());
        StringBuilder sb = new StringBuilder();
        sb.append("1. /storage/emulated/0 (Shared Internal Storage)\n");
        for (String f : folders) {
            sb.append("- ").append(f).append("\n");
        }
        folderListText.setText(sb.toString());
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

    private void saveSelectedFolder(String folderUri) {
        Set<String> set = new HashSet<>(prefs.getStringSet("folders", new HashSet<>()));
        set.add(folderUri);
        prefs.edit().putStringSet("folders", set).apply();
        Toast.makeText(this, "Folder added to Fishbowl storage bridge", Toast.LENGTH_SHORT).show();
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
