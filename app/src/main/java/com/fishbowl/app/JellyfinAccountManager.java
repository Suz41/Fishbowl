package com.fishbowl.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Manages Jellyfin user account inspection, password security state verification,
 * and service identity and port security mappings.
 */
public final class JellyfinAccountManager {

    private static final String TAG = "JellyfinAccountManager";

    public static class JellyfinUser {
        public final String id;
        public final String username;
        public final boolean hasPassword;
        public final String passwordHash;
        public final String knownPlaintextPassword;
        public final boolean isAdmin;
        public final String lastLoginDate;
        public final String lastActivityDate;
        public final int invalidLoginAttempts;

        public JellyfinUser(String id, String username, boolean hasPassword, String passwordHash,
                            String knownPlaintextPassword, boolean isAdmin,
                            String lastLoginDate, String lastActivityDate, int invalidLoginAttempts) {
            this.id = id != null ? id : "";
            this.username = username != null ? username : "";
            this.hasPassword = hasPassword;
            this.passwordHash = passwordHash != null ? passwordHash : "";
            this.knownPlaintextPassword = knownPlaintextPassword != null ? knownPlaintextPassword : "";
            this.isAdmin = isAdmin;
            this.lastLoginDate = lastLoginDate != null ? lastLoginDate : "Never";
            this.lastActivityDate = lastActivityDate != null ? lastActivityDate : "Never";
            this.invalidLoginAttempts = invalidLoginAttempts;
        }

        public String getSecurityStatusLabel() {
            return hasPassword ? "Password Set (Secure Hash)" : "No Password Set (Blank / Unlocked)";
        }

        public String getRoleLabel() {
            return isAdmin ? "Administrator" : "Standard User";
        }
    }

    public static class ServiceSecurityMapping {
        public final String id;
        public final String displayName;
        public final int port;
        public final String roleDescription;
        public final String authMechanism;
        public final boolean isPublicExposed;

        public ServiceSecurityMapping(String id, String displayName, int port,
                                      String roleDescription, String authMechanism, boolean isPublicExposed) {
            this.id = id;
            this.displayName = displayName;
            this.port = port;
            this.roleDescription = roleDescription;
            this.authMechanism = authMechanism;
            this.isPublicExposed = isPublicExposed;
        }
    }

    private JellyfinAccountManager() {}

    /**
     * Resolves the primary active Jellyfin SQLite database location.
     */
    public static File getJellyfinDatabaseFile() {
        File internal = new File(TermuxConstants.TERMUX_HOME_DIR, ".local/share/jellyfin/data/jellyfin.db");
        if (internal.exists() && internal.canRead()) {
            return internal;
        }
        File mirrored = new File(MediaStorageManager.DEFAULT_MEDIA_ROOT, "data/jellyfin.db");
        if (mirrored.exists() && mirrored.canRead()) {
            return mirrored;
        }
        File legacyMirrored = new File(Environment.getExternalStorageDirectory(), "Fishbowl/data/jellyfin.db");
        if (legacyMirrored.exists() && legacyMirrored.canRead()) {
            return legacyMirrored;
        }
        File backupCore = new File(MediaStorageManager.DEFAULT_MEDIA_ROOT, "Backups/auto_jellyfin_backup.db");
        if (backupCore.exists() && backupCore.canRead()) {
            return backupCore;
        }
        File backupDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FishBowl_Backups/auto_jellyfin_backup.db");
        if (backupDir.exists() && backupDir.canRead()) {
            return backupDir;
        }
        return internal;
    }

    /**
     * Inspects registered Jellyfin users directly from the SQLite database.
     */
    public static List<JellyfinUser> getRegisteredUsers(Context context) {
        File dbFile = getJellyfinDatabaseFile();
        if (!dbFile.exists()) {
            return Collections.emptyList();
        }

        List<JellyfinUser> users = new ArrayList<>();
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

            String query = "SELECT Id, Username, Password, LastLoginDate, LastActivityDate, InvalidLoginAttemptCount FROM Users";
            try (Cursor cursor = db.rawQuery(query, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        String id = cursor.getString(0);
                        String username = cursor.getString(1);
                        String password = cursor.getString(2);
                        String lastLogin = cursor.getString(3);
                        String lastActivity = cursor.getString(4);
                        int invalidAttempts = cursor.getInt(5);

                        boolean hasPassword = password != null && !password.trim().isEmpty();
                        boolean isAdmin = false;

                        // Query Permissions table for Administrator status
                        try (Cursor permCursor = db.rawQuery(
                                "SELECT Value FROM Permissions WHERE UserId = ? AND (Kind = 0 OR Kind = 'IsAdministrator')",
                                new String[]{id})) {
                            if (permCursor != null && permCursor.moveToFirst()) {
                                isAdmin = permCursor.getInt(0) == 1;
                            }
                        } catch (Throwable ignored) {}

                        String knownPlain = null;
                        if (!hasPassword) {
                            // Database has no password (e.g. user reset via Forgot Password PIN on Web client).
                            removeKnownPassword(context, username);
                        } else {
                            String candidate = getKnownPassword(context, username);
                            if (candidate != null) {
                                if (verifyPasswordHash(candidate, password)) {
                                    knownPlain = candidate;
                                } else {
                                    // Password was changed or reset in Web client; cached plaintext is stale
                                    removeKnownPassword(context, username);
                                }
                            }
                        }

                        users.add(new JellyfinUser(id, username, hasPassword, password, knownPlain, isAdmin, lastLogin, lastActivity, invalidAttempts));
                    } while (cursor.moveToNext());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read Jellyfin users from db: " + t.getMessage());
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }

        return users;
    }

    /**
     * Verifies if a given plaintext password matches a PBKDF2-SHA512 hash from jellyfin.db.
     */
    public static boolean verifyPasswordHash(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null || storedHash.trim().isEmpty()) return false;
        try {
            String[] parts = storedHash.split("\\$");
            // Format: $PBKDF2-SHA512$iterations=210000$<saltHex>$<hashHex>
            if (parts.length < 5) return false;
            String iterPart = parts[2];
            int iterations = 210000;
            if (iterPart.startsWith("iterations=")) {
                iterations = Integer.parseInt(iterPart.substring("iterations=".length()));
            }
            byte[] salt = hexToBytes(parts[3]);
            byte[] expectedHash = hexToBytes(parts[4]);

            javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(plainPassword.toCharArray(), salt, iterations, expectedHash.length * 8);
            byte[] computedHash = skf.generateSecret(spec).getEncoded();
            return java.security.MessageDigest.isEqual(expectedHash, computedHash);
        } catch (Throwable t) {
            Log.w(TAG, "verifyPasswordHash failed: " + t.getMessage());
            return false;
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static final String PREF_KNOWN_PASSWORDS = "fishbowl_jellyfin_known_passwords";
    private static final String PREF_SECURE_CREDENTIALS = "fishbowl_jellyfin_secure_credentials";

    /**
     * Retrieves the decrypted password for a given username using Android Keystore.
     * Automatically migrates and purges legacy plaintext entries from ordinary SharedPreferences.
     */
    public static String getKnownPassword(Context context, String username) {
        if (context == null || username == null) return null;
        String key = username.trim().toLowerCase(Locale.ROOT);
        try {
            // 1. Check encrypted credentials
            String encrypted = context.getSharedPreferences(PREF_SECURE_CREDENTIALS, Context.MODE_PRIVATE)
                    .getString(key, null);
            if (encrypted != null && !encrypted.isEmpty()) {
                String decrypted = com.fishbowl.app.security.FishbowlKeystoreCrypto.decrypt(context, encrypted);
                if (decrypted != null) {
                    return decrypted;
                }
            }

            // 2. Legacy migration check: if legacy plaintext entry exists, migrate it to Keystore encryption
            SharedPreferences legacyPrefs = context.getSharedPreferences(PREF_KNOWN_PASSWORDS, Context.MODE_PRIVATE);
            String legacyPlain = legacyPrefs.getString(key, null);
            if (legacyPlain != null && !legacyPlain.isEmpty()) {
                storeKnownPassword(context, username, legacyPlain);
                legacyPrefs.edit().remove(key).apply();
                return legacyPlain;
            }
        } catch (Throwable t) {
            Log.w(TAG, "getKnownPassword error: " + t.getMessage());
        }
        return null;
    }

    /**
     * Encrypts and stores the password using hardware-backed Android Keystore AES-256-GCM.
     * Purges any unencrypted legacy plaintext entries immediately.
     */
    public static void storeKnownPassword(Context context, String username, String plainPassword) {
        if (context == null || username == null || plainPassword == null || plainPassword.isEmpty()) return;
        String key = username.trim().toLowerCase(Locale.ROOT);
        try {
            String encrypted = com.fishbowl.app.security.FishbowlKeystoreCrypto.encrypt(context, plainPassword);
            if (encrypted != null) {
                context.getSharedPreferences(PREF_SECURE_CREDENTIALS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(key, encrypted)
                        .apply();
            }
            // Ensure no plaintext remains in legacy prefs
            context.getSharedPreferences(PREF_KNOWN_PASSWORDS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(key)
                    .apply();
        } catch (Throwable t) {
            Log.e(TAG, "storeKnownPassword error: " + t.getMessage());
        }
    }

    public static void removeKnownPassword(Context context, String username) {
        if (context == null || username == null) return;
        String key = username.trim().toLowerCase(Locale.ROOT);
        try {
            context.getSharedPreferences(PREF_SECURE_CREDENTIALS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(key)
                    .apply();
            context.getSharedPreferences(PREF_KNOWN_PASSWORDS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(key)
                    .apply();
        } catch (Throwable ignored) {}
    }

    /**
     * Purges all cached encrypted credentials from device storage.
     */
    public static void purgeAllKnownPasswords(Context context) {
        if (context == null) return;
        try {
            context.getSharedPreferences(PREF_SECURE_CREDENTIALS, Context.MODE_PRIVATE).edit().clear().apply();
            context.getSharedPreferences(PREF_KNOWN_PASSWORDS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) {}
    }

    public static String hashJellyfinPassword(String password) {
        if (password == null) return null;
        try {
            java.security.SecureRandom rng = new java.security.SecureRandom();
            byte[] salt = new byte[16];
            rng.nextBytes(salt);
            javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 210000, 512);
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return String.format(Locale.ROOT, "$PBKDF2-SHA512$iterations=210000$%s$%s", bytesToHex(salt), bytesToHex(hash));
        } catch (Throwable t) {
            Log.e(TAG, "PBKDF2-SHA512 generation failed: " + t.getMessage(), t);
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02X", b));
        }
        return sb.toString();
    }

    /**
     * Sets a user's password directly in jellyfin.db using PBKDF2-SHA512 and caches the known plaintext.
     */
    public static boolean setPassword(Context context, String username, String newPassword) {
        if (username == null || username.trim().isEmpty()) return false;
        File dbFile = getJellyfinDatabaseFile();
        if (!dbFile.exists()) return false;

        String hash = null;
        if (newPassword != null && !newPassword.trim().isEmpty()) {
            hash = hashJellyfinPassword(newPassword.trim());
            if (hash == null) return false;
        }

        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            ContentValues cv = new ContentValues();
            if (hash != null) {
                cv.put("Password", hash);
            } else {
                cv.putNull("Password");
            }
            cv.put("MustUpdatePassword", 0);
            cv.put("InvalidLoginAttemptCount", 0);

            int updated = db.update("Users", cv, "Username = ?", new String[]{username.trim()});
            if (updated > 0) {
                Log.i(TAG, "Direct password set successful for user: " + username);
                if (newPassword != null && !newPassword.trim().isEmpty()) {
                    storeKnownPassword(context, username.trim(), newPassword.trim());
                } else {
                    removeKnownPassword(context, username.trim());
                }
                BackupRestoreManager.autoBackupJellyfinDatabase(context);
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to set password: " + t.getMessage(), t);
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    /**
     * Emergency password reset tool: resets user's password directly to empty/blank.
     */
    public static boolean resetPasswordToBlank(Context context, String username) {
        if (username == null || username.trim().isEmpty()) return false;
        File dbFile = getJellyfinDatabaseFile();
        if (!dbFile.exists()) return false;

        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            ContentValues cv = new ContentValues();
            cv.putNull("Password");
            cv.put("MustUpdatePassword", 0);
            cv.put("InvalidLoginAttemptCount", 0);

            int updated = db.update("Users", cv, "Username = ?", new String[]{username.trim()});
            if (updated > 0) {
                Log.i(TAG, "Direct blank-password reset successful for user: " + username);
                removeKnownPassword(context, username.trim());
                // Also mirror change to persistent storage
                BackupRestoreManager.autoBackupJellyfinDatabase(context);
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed direct password reset: " + t.getMessage(), t);
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    /**
     * Returns the comprehensive list of service ID to port, role, and security access mappings.
     */
    public static List<ServiceSecurityMapping> getServiceSecurityMappings() {
        List<ServiceSecurityMapping> list = new ArrayList<>();
        list.add(new ServiceSecurityMapping("jellyfin", "Jellyfin Media Server", 8096, "Core Media Engine, Streaming & Transcoding", "Jellyfin User Account / Tokens", true));
        list.add(new ServiceSecurityMapping("radarr", "Radarr (Movies)", 7878, "Movie Library Automation & Quality Scoring", "API Key / Forms (Disabled for Local)", false));
        list.add(new ServiceSecurityMapping("sonarr", "Sonarr (TV Shows)", 8989, "TV Series PVR Automation & Season Tracking", "API Key / Forms (Disabled for Local)", false));
        list.add(new ServiceSecurityMapping("prowlarr", "Prowlarr (Indexers)", 9696, "Unified Torrent & Usenet Indexer Proxy", "API Key / Forms (Disabled for Local)", false));
        list.add(new ServiceSecurityMapping("transmission", "Transmission Daemon", 9091, "Headless BitTorrent Download Client", "RPC Loopback Whitelist / No Auth", false));
        list.add(new ServiceSecurityMapping("seerr", "Seerr Request Engine", 5055, "Media Requests, User Discovery & Approvals", "Jellyfin OAuth / API Key", true));
        list.add(new ServiceSecurityMapping("bazarr", "Bazarr (Subtitles)", 6767, "Multi-language Subtitle Fetcher", "API Key / Local Loopback Access", false));
        list.add(new ServiceSecurityMapping("jackett", "Jackett Proxy", 9117, "Torrent Tracker Proxy & Torznab Provider", "API Key / Loopback Access", false));
        list.add(new ServiceSecurityMapping("lidarr", "Lidarr (Music)", 8686, "Music Collection Manager & Grabber", "API Key / Forms (Disabled for Local)", false));
        return list;
    }
}
