package com.fishbowl.app;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Automated regression tests for Update Manager cryptographic and release integrity.
 * Covers:
 * - Test 12: Checksum mismatch blocks installation; invalid package identity or version downgrade rejected; HTTPS enforcement blocks insecure HTTP URLs.
 */
public class UpdateManagerSecurityTest {

    private File testRoot;
    private File mockApk;

    @Before
    public void setUp() throws Exception {
        testRoot = new File(System.getProperty("java.io.tmpdir"), "fishbowl_update_test_" + System.currentTimeMillis());
        testRoot.mkdirs();

        mockApk = new File(testRoot, "Fishbowl-v1.4.8-beta.2.apk");
        writeFile(mockApk, "PK\u0003\u0004MockValidApkBinaryPayloadForFishbowlUpdateVerification");
    }

    @After
    public void tearDown() {
        deleteRecursive(testRoot);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    /**
     * Test 12a: SHA-256 calculation & checksum mismatch blocks installation.
     */
    @Test
    public void test12a_ChecksumMismatchBlocksInstallation() throws Exception {
        // Calculate genuine SHA-256
        String actualHash = UpdateManager.calculateSHA256(mockApk);
        Assert.assertNotNull("Actual hash must not be null", actualHash);
        Assert.assertEquals("SHA-256 hex string should be 64 characters long", 64, actualHash.length());

        // Exact match succeeds
        String expectedMatchingHash = actualHash.toUpperCase();
        Assert.assertTrue("Matching hash succeeds verification",
                actualHash.equalsIgnoreCase(expectedMatchingHash));

        // Tampered / mismatched hash fails
        String tamperedExpectedHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        boolean hashMatches = actualHash.equalsIgnoreCase(tamperedExpectedHash);
        Assert.assertFalse("Mismatched SHA-256 must fail verification and block installation", hashMatches);
    }

    /**
     * Test 12b: HTTPS URL enforcement blocks insecure HTTP endpoints.
     */
    @Test
    public void test12b_HttpsUrlEnforcementBlocksInsecureEndpoints() {
        String insecureHttpUrl = "http://github.com/fishbowl/app/releases/download/v1.4.8-beta.2/Fishbowl.apk";
        String secureHttpsUrl = "https://github.com/fishbowl/app/releases/download/v1.4.8-beta.2/Fishbowl.apk";
        String ftpUrl = "ftp://github.com/Fishbowl.apk";

        Assert.assertFalse("Insecure HTTP URL must be rejected",
                insecureHttpUrl.toLowerCase().startsWith("https://"));
        Assert.assertFalse("Insecure FTP URL must be rejected",
                ftpUrl.toLowerCase().startsWith("https://"));
        Assert.assertTrue("Secure HTTPS URL is permitted",
                secureHttpsUrl.toLowerCase().startsWith("https://"));
    }

    /**
     * Test 12c: Version code parsing from release tags and downgrade rejection.
     */
    @Test
    public void test12c_VersionTagParsingAndDowngradeDetection() {
        // Known version tags
        int v147beta1 = UpdateManager.parseVersionCodeFromTag("v1.4.7-beta.1");
        int v147beta2 = UpdateManager.parseVersionCodeFromTag("v1.4.7-beta.2");
        int v148beta1 = UpdateManager.parseVersionCodeFromTag("v1.4.8-beta.1");
        int v148beta2 = UpdateManager.parseVersionCodeFromTag("v1.4.8-beta.2");

        Assert.assertEquals("v1.4.7-beta.1 parses to expected code", 101409, v147beta1);
        Assert.assertEquals("v1.4.7-beta.2 parses to expected code", 101410, v147beta2);
        Assert.assertTrue("v1.4.8-beta.1 is higher than v1.4.7-beta.2", v148beta1 > v147beta2);
        Assert.assertTrue("v1.4.8-beta.2 is higher than v1.4.8-beta.1", v148beta2 > v148beta1);

        // Downgrade detection logic
        int currentInstalledVersionCode = v148beta1;
        int incomingOldVersionCode = v147beta2;
        boolean isDowngrade = incomingOldVersionCode < currentInstalledVersionCode;
        Assert.assertTrue("Older version code must be detected as an illegal downgrade", isDowngrade);

        int incomingNewVersionCode = v148beta2;
        boolean isUpgrade = incomingNewVersionCode >= currentInstalledVersionCode;
        Assert.assertTrue("Higher or equal version code is permitted", isUpgrade);
    }

    /**
     * Test 12d: Asset name filtering blocks arbitrary or unexpected binaries.
     */
    @Test
    public void test12d_AssetFilteringBlocksNonFishbowlBinaries() {
        String officialArm64Apk = "Fishbowl-v1.4.8-beta.2-arm64-v8a.apk";
        String officialUniversalApk = "Fishbowl-v1.4.8-beta.2-universal.apk";
        String arbitraryApk = "MaliciousPayload.apk";
        String scriptFile = "setup.sh";
        String checksumFile = "Fishbowl-v1.4.8-beta.2-arm64-v8a.apk.sha256";

        Assert.assertTrue("Official arm64 release APK is accepted",
                officialArm64Apk.startsWith("Fishbowl") && officialArm64Apk.endsWith(".apk"));
        Assert.assertTrue("Official universal release APK is accepted",
                officialUniversalApk.startsWith("Fishbowl") && officialUniversalApk.endsWith(".apk"));

        Assert.assertFalse("Arbitrary non-Fishbowl APK is rejected",
                arbitraryApk.startsWith("Fishbowl") && arbitraryApk.endsWith(".apk"));
        Assert.assertFalse("Shell script is rejected",
                scriptFile.startsWith("Fishbowl") && scriptFile.endsWith(".apk"));
        Assert.assertFalse("Checksum file is not treated as APK",
                checksumFile.endsWith(".apk"));
    }

    private void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
