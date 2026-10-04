package com.fishbowl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class JellyfinEncodingConfig {
    private static final String TAG = "JellyfinEncodingConfig";
    private static final String PREF_NAME = "jellyfindroid_transcoding";

    public static final String MODE_AUTO = "auto";
    public static final String HW_ACCEL_NONE = "none";
    public static final String HW_ACCEL_MEDIACODEC = "mediacodec";
    public static final String HW_ACCEL_V4L2M2M = "v4l2m2m";

    public static final class EncodingOptions {
        public String hardwareAccelerationType = HW_ACCEL_NONE;
        public int threadCount = -1; // -1 = Auto
        public String transcodingTempPath = "";
        public String encoderAppPath = "";
        public boolean enableHardwareEncoding = true;
        public boolean enableDecodingColorDepth10Hevc = true;
        public boolean enableDecodingColorDepth10Vp9 = true;
        public boolean enableDecodingColorDepth10HevcRemux = true;
        public List<String> hardwareDecodingCodecs = new ArrayList<>(Arrays.asList("h264", "hevc", "vp9", "av1"));
        public boolean allowHevcEncoding = false;
        public boolean allowAv1Encoding = false;
        public boolean enableThrottling = false;
        public int throttleDelaySeconds = 180;
        public int downMixAudioBoost = 1;
        public int maxMuxingQueueSize = 2048;
        public boolean enableSegmentDeletion = true;
        public int segmentKeepSeconds = 720;
        public String fallbackFontPath = detectSystemFallbackFont();
        public boolean enableFallbackFont = !fallbackFontPath.isEmpty();
        public boolean enableSubtitleExtraction = true;
    }

    private JellyfinEncodingConfig() {}

    public static File getEncodingXmlFile() {
        File homeDir = TermuxConstants.TERMUX_HOME_DIR;
        File configDir = new File(homeDir, ".config/jellyfin");
        if (!configDir.exists()) configDir.mkdirs();
        return new File(configDir, "encoding.xml");
    }

    public static String getDefaultFFmpegPath() {
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        File optBin = new File(prefix, "opt/jellyfin/bin/ffmpeg");
        if (optBin.exists()) return optBin.getAbsolutePath();
        File usrBin = new File(prefix, "bin/ffmpeg");
        if (usrBin.exists()) return usrBin.getAbsolutePath();
        return optBin.getAbsolutePath();
    }

    public static String getDefaultTranscodePath() {
        File homeDir = TermuxConstants.TERMUX_HOME_DIR;
        File cacheDir = new File(homeDir, ".cache/jellyfin/transcodes");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        return cacheDir.getAbsolutePath();
    }

    public static synchronized void ensureDefaultEncodingXml(Context context) {
        File xmlFile = getEncodingXmlFile();
        if (!xmlFile.exists() || xmlFile.length() == 0) {
            EncodingOptions opts = new EncodingOptions();
            opts.encoderAppPath = getDefaultFFmpegPath();
            opts.transcodingTempPath = getDefaultTranscodePath();
            saveEncodingXml(opts);
            Log.i(TAG, "Initialized default encoding.xml with software NEON SIMD transcoding.");
        } else {
            EncodingOptions existing = loadEncodingConfig(context);
            if (existing.fallbackFontPath == null || existing.fallbackFontPath.isEmpty()) {
                existing.fallbackFontPath = detectSystemFallbackFont();
                existing.enableFallbackFont = !existing.fallbackFontPath.isEmpty();
                saveEncodingXml(existing);
                Log.i(TAG, "Updated existing encoding.xml with fallback font: " + existing.fallbackFontPath);
            }
        }
    }

    public static synchronized EncodingOptions loadEncodingConfig(Context context) {
        EncodingOptions opts = new EncodingOptions();
        opts.encoderAppPath = getDefaultFFmpegPath();
        opts.transcodingTempPath = getDefaultTranscodePath();

        File xmlFile = getEncodingXmlFile();
        if (!xmlFile.exists() || xmlFile.length() == 0) {
            return opts;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(xmlFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            String xml = sb.toString();

            opts.hardwareAccelerationType = extractTagValue(xml, "HardwareAccelerationType", HW_ACCEL_NONE);
            try {
                opts.threadCount = Integer.parseInt(extractTagValue(xml, "EncodingThreadCount", "-1"));
            } catch (Throwable ignored) {}
            opts.transcodingTempPath = extractTagValue(xml, "TranscodingTempPath", opts.transcodingTempPath);
            opts.encoderAppPath = extractTagValue(xml, "EncoderAppPath", opts.encoderAppPath);
            opts.enableHardwareEncoding = Boolean.parseBoolean(extractTagValue(xml, "EnableHardwareEncoding", "true"));
            opts.enableDecodingColorDepth10Hevc = Boolean.parseBoolean(extractTagValue(xml, "EnableDecodingColorDepth10Hevc", "true"));
            opts.allowHevcEncoding = Boolean.parseBoolean(extractTagValue(xml, "AllowHevcEncoding", "false"));
            opts.allowAv1Encoding = Boolean.parseBoolean(extractTagValue(xml, "AllowAv1Encoding", "false"));
            opts.enableThrottling = Boolean.parseBoolean(extractTagValue(xml, "EnableThrottling", "false"));
            opts.fallbackFontPath = extractTagValue(xml, "FallbackFontPath", opts.fallbackFontPath);
            opts.enableFallbackFont = Boolean.parseBoolean(extractTagValue(xml, "EnableFallbackFont", String.valueOf(opts.enableFallbackFont)));
            opts.enableSubtitleExtraction = Boolean.parseBoolean(extractTagValue(xml, "EnableSubtitleExtraction", "true"));
            try {
                opts.downMixAudioBoost = Integer.parseInt(extractTagValue(xml, "DownMixAudioBoost", "1"));
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.e(TAG, "Failed to parse encoding.xml: " + t.getMessage(), t);
        }

        return opts;
    }

    public static synchronized boolean saveEncodingXml(EncodingOptions opts) {
        if (opts == null) return false;
        File xmlFile = getEncodingXmlFile();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<EncodingOptions xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n");
        sb.append("  <EncodingThreadCount>").append(opts.threadCount).append("</EncodingThreadCount>\n");
        sb.append("  <TranscodingTempPath>").append(escapeXml(opts.transcodingTempPath)).append("</TranscodingTempPath>\n");
        if (opts.fallbackFontPath != null && !opts.fallbackFontPath.isEmpty()) {
            sb.append("  <FallbackFontPath>").append(escapeXml(opts.fallbackFontPath)).append("</FallbackFontPath>\n");
            sb.append("  <EnableFallbackFont>").append(opts.enableFallbackFont).append("</EnableFallbackFont>\n");
        } else {
            sb.append("  <FallbackFontPath />\n");
            sb.append("  <EnableFallbackFont>false</EnableFallbackFont>\n");
        }
        sb.append("  <DownMixAudioBoost>").append(opts.downMixAudioBoost).append("</DownMixAudioBoost>\n");
        sb.append("  <MaxMuxingQueueSize>").append(opts.maxMuxingQueueSize).append("</MaxMuxingQueueSize>\n");
        sb.append("  <EnableThrottling>").append(opts.enableThrottling).append("</EnableThrottling>\n");
        sb.append("  <ThrottleDelaySeconds>").append(opts.throttleDelaySeconds).append("</ThrottleDelaySeconds>\n");
        sb.append("  <EnableSegmentDeletion>").append(opts.enableSegmentDeletion).append("</EnableSegmentDeletion>\n");
        sb.append("  <SegmentKeepSeconds>").append(opts.segmentKeepSeconds).append("</SegmentKeepSeconds>\n");
        sb.append("  <HardwareAccelerationType>").append(escapeXml(opts.hardwareAccelerationType)).append("</HardwareAccelerationType>\n");
        sb.append("  <EncoderAppPath>").append(escapeXml(opts.encoderAppPath.isEmpty() ? getDefaultFFmpegPath() : opts.encoderAppPath)).append("</EncoderAppPath>\n");
        sb.append("  <EnableHardwareEncoding>").append(opts.enableHardwareEncoding).append("</EnableHardwareEncoding>\n");
        sb.append("  <EnableDecodingColorDepth10Hevc>").append(opts.enableDecodingColorDepth10Hevc).append("</EnableDecodingColorDepth10Hevc>\n");
        sb.append("  <EnableDecodingColorDepth10Vp9>").append(opts.enableDecodingColorDepth10Vp9).append("</EnableDecodingColorDepth10Vp9>\n");
        sb.append("  <EnableDecodingColorDepth10HevcRemux>").append(opts.enableDecodingColorDepth10HevcRemux).append("</EnableDecodingColorDepth10HevcRemux>\n");
        sb.append("  <HardwareDecodingCodecs>\n");
        for (String codec : opts.hardwareDecodingCodecs) {
            sb.append("    <string>").append(escapeXml(codec)).append("</string>\n");
        }
        sb.append("  </HardwareDecodingCodecs>\n");
        sb.append("  <AllowHevcEncoding>").append(opts.allowHevcEncoding).append("</AllowHevcEncoding>\n");
        sb.append("  <AllowAv1Encoding>").append(opts.allowAv1Encoding).append("</AllowAv1Encoding>\n");
        sb.append("  <EnableSubtitleExtraction>").append(opts.enableSubtitleExtraction).append("</EnableSubtitleExtraction>\n");
        sb.append("</EncodingOptions>\n");

        File tempFile = new File(xmlFile.getParentFile(), "encoding.xml.tmp");
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (xmlFile.exists()) xmlFile.delete();
            return tempFile.renameTo(xmlFile);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to write encoding.xml: " + t.getMessage(), t);
            if (tempFile.exists()) tempFile.delete();
            return false;
        }
    }

    public static synchronized void updateTranscodingMode(Context context, String mode, int threadCount) {
        EncodingOptions opts = loadEncodingConfig(context);
        if (MODE_AUTO.equalsIgnoreCase(mode)) {
            opts.hardwareAccelerationType = HW_ACCEL_NONE;
            opts.threadCount = threadCount > 0 ? threadCount : -1;
            opts.enableHardwareEncoding = false;
        } else {
            opts.hardwareAccelerationType = mode;
            opts.threadCount = threadCount;
            opts.enableHardwareEncoding = !HW_ACCEL_NONE.equalsIgnoreCase(mode);
        }
        saveEncodingXml(opts);

        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("hw_accel_mode", mode)
                    .putInt("thread_count", threadCount)
                    .apply();
        }
    }

    public static String getSavedMode(Context context) {
        if (context == null) return MODE_AUTO;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString("hw_accel_mode", MODE_AUTO);
    }

    public static int getSavedThreads(Context context) {
        if (context == null) return -1;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt("thread_count", -1);
    }

    private static String extractTagValue(String xml, String tag, String fallback) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start == -1) return fallback;
        int end = xml.indexOf(close, start + open.length());
        if (end == -1) return fallback;
        return xml.substring(start + open.length(), end).trim();
    }

    public static String detectSystemFallbackFont() {
        String[] candidates = {
                "/system/fonts/Roboto-Regular.ttf",
                "/system/fonts/RobotoFlex-Regular.ttf",
                "/system/fonts/RobotoStatic-Regular.ttf",
                "/system/fonts/NotoSans-Regular.ttf",
                "/system/fonts/DroidSansFallback.ttf"
        };
        for (String p : candidates) {
            File f = new File(p);
            if (f.exists() && f.canRead()) {
                return f.getAbsolutePath();
            }
        }
        return "";
    }

    public static synchronized void updateAudioSubtitleOptions(Context context, int downMixBoost, boolean extractSubtitles) {
        EncodingOptions opts = loadEncodingConfig(context);
        opts.downMixAudioBoost = downMixBoost;
        opts.enableSubtitleExtraction = extractSubtitles;
        if (opts.fallbackFontPath == null || opts.fallbackFontPath.isEmpty()) {
            opts.fallbackFontPath = detectSystemFallbackFont();
            opts.enableFallbackFont = !opts.fallbackFontPath.isEmpty();
        }
        saveEncodingXml(opts);
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
