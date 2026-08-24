package com.jellyfin.droid;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

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
                        } catch (Exception e) {
                            // Non-fatal if takePersistableUriPermission fails
                        }
                        saveSelectedFolder(treeUri.toString());
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jellyfindroid_storage", MODE_PRIVATE);
        setContentView(createView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private View createView() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(18, 24, 31));

        TextView title = new TextView(this);
        title.setText("MEDIA STORAGE");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        permissionStatus = new TextView(this);
        permissionStatus.setTextSize(16);
        permissionStatus.setPadding(0, 0, 0, dp(16));
        root.addView(permissionStatus);

        Button grantBtn = new Button(this);
        grantBtn.setText("GRANT STORAGE PERMISSION");
        grantBtn.setOnClickListener(v -> requestStoragePermission());
        root.addView(grantBtn, buttonParams());

        Button pickFolderBtn = new Button(this);
        pickFolderBtn.setText("SELECT MEDIA FOLDER (SAF)");
        pickFolderBtn.setOnClickListener(v -> openFolderPicker());
        root.addView(pickFolderBtn, buttonParams());

        TextView folderHeader = new TextView(this);
        folderHeader.setText("ACCESSIBLE FOLDERS:");
        folderHeader.setTextSize(16);
        folderHeader.setTextColor(Color.WHITE);
        folderHeader.setPadding(0, dp(20), 0, dp(8));
        root.addView(folderHeader);

        folderListText = new TextView(this);
        folderListText.setTextSize(14);
        folderListText.setTextColor(Color.LTGRAY);
        folderListText.setPadding(0, 0, 0, dp(16));
        root.addView(folderListText);

        Button clearBtn = new Button(this);
        clearBtn.setText("CLEAR SAVED FOLDERS");
        clearBtn.setOnClickListener(v -> {
            prefs.edit().remove("folders").apply();
            updateUI();
        });
        root.addView(clearBtn, buttonParams());

        return root;
    }

    private void updateUI() {
        boolean hasPermission = checkStoragePermission();
        if (hasPermission) {
            permissionStatus.setText("Media Access: ● GRANTED");
            permissionStatus.setTextColor(Color.rgb(76, 175, 80));
        } else {
            permissionStatus.setText("Media Access: ● MISSING (Action Required)");
            permissionStatus.setTextColor(Color.rgb(244, 67, 54));
        }

        Set<String> folders = prefs.getStringSet("folders", new HashSet<>());
        StringBuilder sb = new StringBuilder();
        sb.append("1. Linux /storage/emulated/0 (Shared Storage)\n");
        for (String f : folders) {
            sb.append("• ").append(f).append("\n");
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
        Toast.makeText(this, "Folder added to JellyfinDroid storage bridge", Toast.LENGTH_SHORT).show();
        updateUI();
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        return p;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
