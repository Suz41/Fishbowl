package com.fishbowl.app;

import org.junit.Assert;
import org.junit.Test;

public class PasswordResetAndNetworkTest {

    @Test
    public void testTailscaleIpDetection() {
        Assert.assertTrue(NetworkUtils.isTailscaleIp("100.64.0.1"));
        Assert.assertTrue(NetworkUtils.isTailscaleIp("100.127.255.254"));
        Assert.assertTrue(NetworkUtils.isTailscaleIp("100.85.12.34"));
        Assert.assertFalse(NetworkUtils.isTailscaleIp("100.63.255.255"));
        Assert.assertFalse(NetworkUtils.isTailscaleIp("100.128.0.1"));
        Assert.assertFalse(NetworkUtils.isTailscaleIp("192.168.1.1"));
        Assert.assertFalse(NetworkUtils.isTailscaleIp("10.0.0.1"));
        Assert.assertFalse(NetworkUtils.isTailscaleIp(null));
        Assert.assertFalse(NetworkUtils.isTailscaleIp(""));
    }

    @Test
    public void testLanEndpointUrlGeneration() {
        NetworkUtils.LanEndpoint ep = new NetworkUtils.LanEndpoint("wlan0", "192.168.1.100", "Wi-Fi");
        Assert.assertEquals("http://192.168.1.100:8096", ep.getUrl(8096, "/"));
        Assert.assertEquals("http://192.168.1.100:5055/login", ep.getUrl(5055, "/login"));
        Assert.assertEquals("[Wi-Fi] 192.168.1.100", ep.getDisplayLabel());
    }

    @Test
    public void testPasswordResetExpirationConstants() {
        Assert.assertEquals(5 * 60 * 1000L, PasswordResetManager.EXPIRATION_DURATION_MS);
        Assert.assertEquals("fishbowl_password_reset_channel", PasswordResetManager.CHANNEL_ID);
        Assert.assertEquals(2048, PasswordResetManager.NOTIFICATION_ID);
    }

    @Test
    public void testKeystoreCryptoRoundtripAndTamperResistance() {
        String originalPassword = "FishBowl_Super_Secret_Password_2026!";
        String encrypted = com.fishbowl.app.security.FishbowlKeystoreCrypto.encrypt(null, originalPassword);
        Assert.assertNotNull("Encrypted string should not be null", encrypted);
        Assert.assertNotEquals("Encrypted ciphertext must not match plaintext", originalPassword, encrypted);

        String decrypted = com.fishbowl.app.security.FishbowlKeystoreCrypto.decrypt(null, encrypted);
        Assert.assertEquals("Decrypted password must match original plaintext", originalPassword, decrypted);

        // Tamper resistance: corrupted ciphertext should fail decryption gracefully without crash
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";
        String tamperedDecrypted = com.fishbowl.app.security.FishbowlKeystoreCrypto.decrypt(null, tampered);
        Assert.assertNull("Tampered ciphertext should fail decryption and return null", tamperedDecrypted);
    }
}
