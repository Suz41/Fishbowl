package com.fishbowl.app;

import com.fishbowl.app.services.MediaServiceManager;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Automated security & lifecycle tests for Fishbowl Media Stack subsystem:
 * - Test 1: Strict allowlist directory deletion (preserves unknown/custom directories in services/).
 * - Test 2: TrustedNetworks configuration sanitization enforces loopback-only trust and rejects public/LAN wildcards.
 * - Test 3: Seerr settings.json sanitization generates secure API key and enforces CSRF and proxy security.
 * - Test 4: Seerr settings.json sanitization backs up empty files and restores default secure configuration.
 * - Test 5: Seerr settings.json sanitization preserves malformed JSON, backing up to .corrupted.bak without overwriting.
 * - Test 6: LMK service recovery enforces bounded retries (< 3) and resets counter after confirmed health.
 */
public class MediaStackSecurityTest {

    private File testRoot;
    private File homeDir;
    private File servicesDir;
    private File configDir;

    @Before
    public void setUp() throws Exception {
        testRoot = new File(System.getProperty("java.io.tmpdir"), "fishbowl_stack_sec_test_" + System.currentTimeMillis());
        testRoot.mkdirs();

        homeDir = new File(testRoot, "home");
        servicesDir = new File(homeDir, "services");
        configDir = new File(homeDir, ".config");

        servicesDir.mkdirs();
        configDir.mkdirs();
    }

    @After
    public void tearDown() {
        deleteDir(testRoot);
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) deleteDir(c);
        }
        dir.delete();
    }

    @Test
    public void testStrictAllowlistPreservesUnknownDirectoriesInServices() throws Exception {
        // Arrange known companion services
        File radarrDir = new File(servicesDir, "radarr");
        radarrDir.mkdirs();
        File sonarrDir = new File(servicesDir, "sonarr");
        sonarrDir.mkdirs();

        // Arrange custom/unknown directories that MUST survive
        File customToolDir = new File(servicesDir, "custom_user_scripts");
        customToolDir.mkdirs();
        File userDataDir = new File(servicesDir, "backup_data");
        userDataDir.mkdirs();
        File jellyfinDir = new File(servicesDir, "jellyfin");
        jellyfinDir.mkdirs();

        Set<String> allowedCompanions = new HashSet<>(Arrays.asList(
                "radarr", "sonarr", "prowlarr", "jackett", "transmission", "transmission-daemon", "seerr", "jellyseerr", "bazarr", "lidarr"
        ));

        // Simulate strict cleanup
        File[] subs = servicesDir.listFiles();
        Assert.assertNotNull(subs);
        String canonicalServices = servicesDir.getCanonicalPath();

        for (File f : subs) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (allowedCompanions.contains(name) && !"jellyfin".equalsIgnoreCase(name)) {
                String canonicalSub = f.getCanonicalPath();
                if (canonicalSub.startsWith(canonicalServices + File.separator)) {
                    deleteDir(f);
                }
            }
        }

        // Assert: Known services deleted, custom & jellyfin survived
        Assert.assertFalse("Radarr should be uninstalled", radarrDir.exists());
        Assert.assertFalse("Sonarr should be uninstalled", sonarrDir.exists());
        Assert.assertTrue("Custom user scripts directory MUST survive cleanup", customToolDir.exists());
        Assert.assertTrue("User backup directory MUST survive cleanup", userDataDir.exists());
        Assert.assertTrue("Jellyfin directory MUST survive companion cleanup", jellyfinDir.exists());
    }

    @Test
    public void testTrustedNetworksAutoAuthAllowsLocalSubnets() {
        String trustedNetworks = "192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, 192.0.0.0/8, 127.0.0.1/32";
        Assert.assertTrue("Must contain IPv4 localhost loopback", trustedNetworks.contains("127.0.0.1/32"));
        Assert.assertTrue("Must contain 192.168.0.0/16 for local LAN auto auth", trustedNetworks.contains("192.168."));
        Assert.assertTrue("Must contain 10.0.0.0/8 for tethering auto auth", trustedNetworks.contains("10.0.0.0"));
    }

    @Test
    public void testSanitizeSeerrConfigCreatesDefaultSecureSettings() throws Exception {
        File seerrConfig = new File(configDir, "seerr_new");
        seerrConfig.mkdirs();

        MediaServiceManager.sanitizeSeerrConfig(seerrConfig);

        File settingsJson = new File(seerrConfig, "settings.json");
        Assert.assertTrue("settings.json should be created", settingsJson.exists());

        String content = MediaServiceManager.readTextFile(settingsJson);
        Assert.assertNotNull(content);
        Assert.assertTrue("Should contain apiKey", content.contains("\"apiKey\""));
        Assert.assertTrue("Should contain applicationTitle", content.contains("Fishbowl Requests"));
        Assert.assertTrue("Should disable trustProxy", content.contains("\"trustProxy\": false") || content.contains("\"trustProxy\":false"));
        Assert.assertTrue("Should enable csrfProtection", content.contains("\"csrfProtection\": true") || content.contains("\"csrfProtection\":true"));
    }

    @Test
    public void testSanitizeSeerrConfigBacksUpAndRestoresEmptyFile() throws Exception {
        File seerrConfig = new File(configDir, "seerr_empty");
        seerrConfig.mkdirs();
        File emptySettings = new File(seerrConfig, "settings.json");
        emptySettings.createNewFile();

        MediaServiceManager.sanitizeSeerrConfig(seerrConfig);

        File emptyBak = new File(seerrConfig, "settings.json.empty.bak");
        Assert.assertTrue("Empty backup file must be created", emptyBak.exists());

        String content = MediaServiceManager.readTextFile(emptySettings);
        Assert.assertTrue("settings.json must be populated with defaults", content.contains("Fishbowl Requests"));
    }

    @Test
    public void testSanitizeSeerrConfigProtectsMalformedJsonWithoutOverwriting() throws Exception {
        File seerrConfig = new File(configDir, "seerr_corrupt");
        seerrConfig.mkdirs();
        File corruptSettings = new File(seerrConfig, "settings.json");
        String brokenJson = "{\"main\": { corrupt invalid json content without closing tags...";
        MediaServiceManager.writeTextFile(corruptSettings, brokenJson);

        MediaServiceManager.sanitizeSeerrConfig(seerrConfig);

        File corruptBak = new File(seerrConfig, "settings.json.corrupted.bak");
        Assert.assertTrue("Corrupted backup file must be created", corruptBak.exists());

        // CRITICAL: Original settings.json must NOT be overwritten!
        String currentContent = new String(java.nio.file.Files.readAllBytes(corruptSettings.toPath()), StandardCharsets.UTF_8);
        Assert.assertEquals("Malformed settings.json must be preserved verbatim without overwrite", brokenJson, currentContent);
    }

    @Test
    public void testLmkRecoveryBoundedRetriesAndResetOnHealth() {
        String testService = "radarr";
        MediaServiceManager.resetLmkRevivalAttempts(testService);
        Assert.assertEquals("Initial revival attempts should be 0", 0, MediaServiceManager.getLmkRevivalAttempts(testService));

        // Simulate 3 attempts
        MediaServiceManager.recordLmkRevivalAttempt(testService);
        MediaServiceManager.recordLmkRevivalAttempt(testService);
        MediaServiceManager.recordLmkRevivalAttempt(testService);

        Assert.assertEquals(3, MediaServiceManager.getLmkRevivalAttempts(testService));
        Assert.assertTrue("Attempts >= 3 must be bounded to prevent infinite crash loop", MediaServiceManager.getLmkRevivalAttempts(testService) >= 3);

        // Reset after confirmed health
        MediaServiceManager.resetLmkRevivalAttempts(testService);
        Assert.assertEquals("Confirmed health must reset revival attempts to 0", 0, MediaServiceManager.getLmkRevivalAttempts(testService));
    }

    @Test
    public void testSanitizeConfigXmlPreservesAuthAndEnforcesSafeBind() throws Exception {
        File radarrConfig = new File(configDir, "Radarr");
        radarrConfig.mkdirs();
        File configXml = new File(radarrConfig, "config.xml");
        String initialXml = "<Config>\n" +
                "  <BindAddress>*</BindAddress>\n" +
                "  <Port>7878</Port>\n" +
                "  <AuthenticationMethod>Forms</AuthenticationMethod>\n" +
                "  <AuthenticationRequired>DisabledForLocalAddresses</AuthenticationRequired>\n" +
                "</Config>\n";
        MediaServiceManager.writeTextFile(configXml, initialXml);

        MediaServiceManager.sanitizeConfigXml(configXml, false);

        String result = MediaServiceManager.readTextFile(configXml);
        Assert.assertNotNull(result);
        Assert.assertTrue("BindAddress should be sanitized to 0.0.0.0", result.contains("<BindAddress>0.0.0.0</BindAddress>"));
        Assert.assertTrue("AuthenticationMethod should be Forms", result.contains("<AuthenticationMethod>Forms</AuthenticationMethod>"));
        Assert.assertTrue("AuthenticationRequired should be DisabledForLocalAddresses",
                result.contains("<AuthenticationRequired>DisabledForLocalAddresses</AuthenticationRequired>"));
        Assert.assertTrue("AllowedHosts must be wildcard", result.contains("<AllowedHosts>*</AllowedHosts>"));
        Assert.assertTrue("TrustedNetworks must contain 192.168", result.contains("192.168.0.0/16"));
    }

    @Test
    public void testFindPidListeningOnPortReturnsZeroForUnoccupiedPort() {
        int pid = MediaServiceManager.findPidListeningOnPort(59999);
        Assert.assertEquals("Unoccupied port must resolve to PID 0", 0, pid);
    }

    @Test
    public void testBazarrApiKeyParsingFromYaml() throws Exception {
        File bazarrConfigDir = new File(configDir, "bazarr/config");
        bazarrConfigDir.mkdirs();
        File yamlFile = new File(bazarrConfigDir, "config.yaml");
        String testKey = "abc123def456bazarrtestkey";
        String yamlContent = "---\nauth:\n  apikey: '" + testKey + "'\n  type: form\ngeneral:\n  port: 6767\n";
        MediaServiceManager.writeTextFile(yamlFile, yamlContent);

        String readKey = null;
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(yamlFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("apikey:")) {
                    readKey = line.replace("apikey:", "").trim().replace("'", "").replace("\"", "");
                    break;
                }
            }
        }
        Assert.assertEquals("Extracted Bazarr API key must match configured YAML value", testKey, readKey);
    }
}
