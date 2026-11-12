package com.fishbowl.app.security;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Android Keystore-backed AES-256-GCM cryptographic manager.
 * Provides hardware-isolated, authenticated credential encryption to eliminate
 * plaintext password storage in SharedPreferences.
 *
 * Automatically provides an internal authenticated fallback for local JVM unit test runners
 * where the AndroidKeyStore SPI is unavailable.
 */
public final class FishbowlKeystoreCrypto {

    private static final String TAG = "FishbowlKeystoreCrypto";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "fishbowl_credential_master_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private static final Object sLock = new Object();
    private static volatile Boolean sUseKeystore = null;
    private static volatile SecretKey sFallbackKey = null;

    private FishbowlKeystoreCrypto() {}

    /**
     * Encrypts plaintext string using AES-256-GCM.
     * Output format: Base64([12-byte IV] || [Ciphertext with 16-byte GCM Tag])
     */
    public static String encrypt(Context context, String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        synchronized (sLock) {
            try {
                SecretKey secretKey = getOrCreateSecretKey(context);
                byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
                new SecureRandom().nextBytes(iv);

                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

                byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
                byte[] cipherBytes = cipher.doFinal(plainBytes);

                byte[] combined = new byte[iv.length + cipherBytes.length];
                System.arraycopy(iv, 0, combined, 0, iv.length);
                System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);

                return encodeBase64(combined);
            } catch (Throwable t) {
                Log.e(TAG, "Keystore encryption error: " + t.getMessage(), t);
                return null;
            }
        }
    }

    /**
     * Decrypts AES-256-GCM base64 ciphertext using the Keystore-backed key.
     */
    public static String decrypt(Context context, String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isEmpty()) {
            return null;
        }
        synchronized (sLock) {
            try {
                byte[] combined = decodeBase64(encryptedBase64);
                if (combined == null || combined.length < GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
                    Log.w(TAG, "Encrypted payload too short for valid GCM format");
                    return null;
                }

                byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
                byte[] cipherBytes = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);

                SecretKey secretKey = getOrCreateSecretKey(context);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

                byte[] plainBytes = cipher.doFinal(cipherBytes);
                return new String(plainBytes, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                Log.w(TAG, "Keystore decryption error: " + t.getMessage());
                return null;
            }
        }
    }

    private static SecretKey getOrCreateSecretKey(Context context) throws Exception {
        if (sUseKeystore == null) {
            try {
                KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
                ks.load(null);
                sUseKeystore = true;
            } catch (Throwable t) {
                sUseKeystore = false;
            }
        }

        if (sUseKeystore) {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
                KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(false); // We provide secure random IV explicitly

                keyGenerator.init(builder.build());
                keyGenerator.generateKey();
            }
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        } else {
            // JVM unit test / non-AndroidKeyStore environment
            if (sFallbackKey == null) {
                sFallbackKey = getOrCreateFallbackKey(context);
            }
            return sFallbackKey;
        }
    }

    private static SecretKey getOrCreateFallbackKey(Context context) throws Exception {
        File keyFile = null;
        if (context != null && context.getFilesDir() != null) {
            File secDir = new File(context.getFilesDir(), "security");
            if (!secDir.exists()) secDir.mkdirs();
            keyFile = new File(secDir, ".credential_master.key");
        } else {
            keyFile = new File(System.getProperty("java.io.tmpdir"), ".fb_test_master.key");
        }

        if (keyFile.exists() && keyFile.length() == 32) {
            byte[] keyBytes = new byte[32];
            try (FileInputStream fis = new FileInputStream(keyFile)) {
                int read = fis.read(keyBytes);
                if (read == 32) {
                    return new SecretKeySpec(keyBytes, "AES");
                }
            } catch (Throwable ignored) {}
        }

        byte[] rawKey = new byte[32];
        new SecureRandom().nextBytes(rawKey);
        try (FileOutputStream fos = new FileOutputStream(keyFile)) {
            fos.write(rawKey);
            fos.flush();
        } catch (Throwable ignored) {}

        return new SecretKeySpec(rawKey, "AES");
    }

    private static String encodeBase64(byte[] data) {
        if (data == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return java.util.Base64.getEncoder().encodeToString(data);
            }
        } catch (Throwable ignored) {}
        try {
            String res = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            if (res != null) return res;
        } catch (Throwable ignored) {}
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    private static byte[] decodeBase64(String base64Str) {
        if (base64Str == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return java.util.Base64.getDecoder().decode(base64Str);
            }
        } catch (Throwable ignored) {}
        try {
            byte[] res = android.util.Base64.decode(base64Str, android.util.Base64.NO_WRAP);
            if (res != null) return res;
        } catch (Throwable ignored) {}
        try {
            return java.util.Base64.getDecoder().decode(base64Str);
        } catch (Throwable t) {
            return null;
        }
    }
}
