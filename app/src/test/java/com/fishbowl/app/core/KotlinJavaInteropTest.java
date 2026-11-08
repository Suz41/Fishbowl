package com.fishbowl.app.core;

import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies bidirectional interoperability between Java and Kotlin components.
 */
public class KotlinJavaInteropTest {

    @Test
    public void testJavaCallingKotlinStaticAndInstanceMethods() {
        String version = KotlinInteropBridge.getKotlinRuntimeVersion();
        Assert.assertEquals("2.1.20", version);

        KotlinInteropBridge bridge = new KotlinInteropBridge();
        String echo = bridge.echo("Hello from Java");
        Assert.assertEquals("Echo: Hello from Java", echo);
    }

    @Test
    public void testKotlinCallingJavaUtilities() {
        KotlinInteropBridge bridge = new KotlinInteropBridge();
        boolean isTailscale = bridge.callJavaUtil("100.65.0.1");
        Assert.assertTrue("100.65.0.1 is in CGNAT Tailscale range", isTailscale);

        boolean isNotTailscale = bridge.callJavaUtil("192.168.1.50");
        Assert.assertFalse("192.168.1.50 is not Tailscale", isNotTailscale);
    }
}
