package com.fishbowl.app;

import com.fishbowl.app.diagnostics.BugReportHelper;

import org.junit.Assert;
import org.junit.Test;

public class BugReportHelperTest {

    @Test
    public void testCollectDeviceInfoWithNullContext() {
        BugReportHelper.DeviceInfo info = BugReportHelper.collectDeviceInfo(null);
        Assert.assertNotNull("DeviceInfo must not be null", info);
        Assert.assertNotNull("Manufacturer must not be null", info.getManufacturer());
        Assert.assertNotNull("Model must not be null", info.getModel());
        Assert.assertNotNull("Android version must not be null", info.getAndroidVersion());
        Assert.assertNotNull("Architecture must not be null", info.getArchitecture());
        Assert.assertNotNull("Version name must not be null", info.getVersionName());
    }

    @Test
    public void testFormatDeviceInfoMarkdown() {
        BugReportHelper.DeviceInfo info = new BugReportHelper.DeviceInfo(
                "vivo",
                "I2219",
                "16",
                36,
                "1.4.10-beta.2",
                101418L,
                "arm64-v8a"
        );

        String md = BugReportHelper.formatDeviceInfoMarkdown(info);
        Assert.assertTrue(md.contains("## Device Information"));
        Assert.assertTrue(md.contains("- Manufacturer: vivo"));
        Assert.assertTrue(md.contains("- Model: I2219"));
        Assert.assertTrue(md.contains("- Android Version: 16"));
        Assert.assertTrue(md.contains("- API Level: 36"));
        Assert.assertTrue(md.contains("- Fishbowl Version: 1.4.10-beta.2"));
        Assert.assertTrue(md.contains("- Version Code: 101418"));
        Assert.assertTrue(md.contains("- Architecture: arm64-v8a"));
    }

    @Test
    public void testBuildBugReportMarkdownSections() {
        String report = BugReportHelper.buildBugReportMarkdown(null, "Server unexpected exit", "sample stack trace log");

        Assert.assertTrue(report.contains("## Problem"));
        Assert.assertTrue(report.contains("Server unexpected exit"));
        Assert.assertTrue(report.contains("## Steps to Reproduce"));
        Assert.assertTrue(report.contains("1."));
        Assert.assertTrue(report.contains("## Expected vs Actual Behavior"));
        Assert.assertTrue(report.contains("## Device Information"));
        Assert.assertTrue(report.contains("## Logs / Screenshots"));
        Assert.assertTrue(report.contains("sample stack trace log"));
        Assert.assertTrue(report.contains("## Additional Information"));
    }

    @Test
    public void testBuildGitHubIssueUrlParameters() {
        String url = BugReportHelper.buildGitHubIssueUrl(null, "Crash on Launch", "Exception detail");

        Assert.assertTrue(url.startsWith(BugReportHelper.GITHUB_NEW_ISSUE_URL));
        Assert.assertTrue(url.contains("template=bug_report.md"));
        Assert.assertTrue(url.contains("labels=bug"));
        Assert.assertTrue(url.contains("title=%5BBUG%5D%20Crash%20on%20Launch") || url.contains("title=%5BBUG%5D+Crash+on+Launch"));
        Assert.assertTrue(url.contains("body="));
        Assert.assertTrue("Generated URL length must be within safe boundary", url.length() <= BugReportHelper.MAX_SAFE_URL_LENGTH);
    }

    @Test
    public void testBuildGitHubIssueUrlLongLogsTruncationSafeguard() {
        StringBuilder bigLog = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            bigLog.append("DEBUG line ").append(i).append(": system stack trace frame details here\n");
        }

        String url = BugReportHelper.buildGitHubIssueUrl(null, "Heavy Crash", bigLog.toString());
        Assert.assertTrue(url.startsWith(BugReportHelper.GITHUB_NEW_ISSUE_URL));
        Assert.assertTrue("URL must be <= 1800 chars even with massive logs", url.length() <= BugReportHelper.MAX_SAFE_URL_LENGTH);
    }
}
