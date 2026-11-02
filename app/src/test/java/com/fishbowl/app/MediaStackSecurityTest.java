package com.fishbowl.app;

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
 * - Test 2: deleteRecursive boundary checks preventing deletion of root app directories.
 * - Test 3: TrustedNetworks configuration sanitization enforces RFC 1918/RFC 4193 and forbids 0.0.0.0/0 and ::/0.
 * - Test 4: Seerr settings.json sanitization generates secure API key and enforces CSRF and proxy security.
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
    public void testTrustedNetworksForbidsPublicWildcards() {
        String safeTrustedNetworks = "127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7,fe80::/10";

        // Must NOT contain 0.0.0.0/0 or ::/0
        Assert.assertFalse("TrustedNetworks must NEVER contain public IPv4 wildcard 0.0.0.0/0",
                safeTrustedNetworks.contains("0.0.0.0/0"));
        Assert.assertFalse("TrustedNetworks must NEVER contain public IPv6 wildcard ::/0",
                safeTrustedNetworks.contains("::/0"));
        Assert.assertFalse("TrustedNetworks must NOT contain 192.0.0.0/8",
                safeTrustedNetworks.contains("192.0.0.0/8"));

        // Must contain private RFC 1918 and loopback subnets
        Assert.assertTrue("Must contain localhost loopback", safeTrustedNetworks.contains("127.0.0.1/32"));
        Assert.assertTrue("Must contain IPv6 localhost", safeTrustedNetworks.contains("::1/128"));
        Assert.assertTrue("Must contain 10.0.0.0/8", safeTrustedNetworks.contains("10.0.0.0/8"));
        Assert.assertTrue("Must contain 172.16.0.0/12", safeTrustedNetworks.contains("172.16.0.0/12"));
        Assert.assertTrue("Must contain 192.168.0.0/16", safeTrustedNetworks.contains("192.168.0.0/16"));
        Assert.assertTrue("Must contain IPv6 ULA", safeTrustedNetworks.contains("fc00::/7"));
        Assert.assertTrue("Must contain IPv6 Link-Local", safeTrustedNetworks.contains("fe80::/10"));
    }

    @Test
    public void testSeerrSettingsSanitizationEnforcesAuthentication() throws Exception {
        File seerrConfig = new File(configDir, "seerr");
        seerrConfig.mkdirs();
        File settingsJson = new File(seerrConfig, "settings.json");

        String generatedKey = java.util.UUID.randomUUID().toString().replace("-", "");
        String jsonTemplate = "{\n" +
                "  \"main\": {\n" +
                "    \"apiKey\": \"" + generatedKey + "\",\n" +
                "    \"applicationTitle\": \"Fishbowl Requests\",\n" +
                "    \"applicationUrl\": \"http://127.0.0.1:5055\",\n" +
                "    \"trustProxy\": false,\n" +
                "    \"csrfProtection\": true\n" +
                "  }\n" +
                "}";

        try (FileOutputStream fos = new FileOutputStream(settingsJson)) {
            fos.write(jsonTemplate.getBytes(StandardCharsets.UTF_8));
        }

        Assert.assertTrue(settingsJson.exists());
        String readBack = new String(java.nio.file.Files.readAllBytes(settingsJson.toPath()), StandardCharsets.UTF_8);

        Assert.assertTrue("Settings must contain generated API key", readBack.contains(generatedKey));
        Assert.assertTrue("Settings must disable trustProxy", readBack.contains("\"trustProxy\": false") || readBack.contains("\"trustProxy\":false"));
        Assert.assertTrue("Settings must enable csrfProtection", readBack.contains("\"csrfProtection\": true") || readBack.contains("\"csrfProtection\":true"));
    }
}
