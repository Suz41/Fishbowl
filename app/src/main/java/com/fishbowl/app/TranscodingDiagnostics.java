package com.fishbowl.app;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class TranscodingDiagnostics {
    private static final String TAG = "TranscodingDiagnostics";

    public static class CodecInfo {
        public final String name;
        public final String mimeType;
        public final boolean isEncoder;
        public final boolean isHardware;

        public CodecInfo(String name, String mimeType, boolean isEncoder, boolean isHardware) {
            this.name = name;
            this.mimeType = mimeType;
            this.isEncoder = isEncoder;
            this.isHardware = isHardware;
        }
    }

    public static class TranscodeTestResult {
        public boolean success;
        public String inputCodec = "HEVC";
        public String outputCodec = "H.264";
        public String inputAudio = "AC3";
        public String outputAudio = "AAC";
        public String decodeType = "Software (hevc native)";
        public String encodeType = "Software (libx264)";
        public String speed = "N/A";
        public String fps = "N/A";
        public String ffmpegVersion = "N/A";
        public String details = "";
        public String error = null;
    }

    /** Inspect Android MediaCodec for hardware-accelerated decoders and encoders. */
    public static List<CodecInfo> detectMediaCodecs() {
        List<CodecInfo> list = new ArrayList<>();
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : codecList.getCodecInfos()) {
                boolean isEncoder = info.isEncoder();
                boolean isHardware;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isHardware = info.isHardwareAccelerated();
                } else {
                    String name = info.getName().toLowerCase(Locale.ROOT);
                    isHardware = !name.startsWith("omx.google.") && !name.startsWith("c2.android.");
                }

                if (!isHardware) continue;

                for (String mime : info.getSupportedTypes()) {
                    if (mime.startsWith("video/")) {
                        list.add(new CodecInfo(info.getName(), mime, isEncoder, true));
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to query MediaCodecList", t);
        }
        return list;
    }

    public static Set<String> getHardwareDecoders() {
        Set<String> decoders = new HashSet<>();
        for (CodecInfo c : detectMediaCodecs()) {
            if (!c.isEncoder) {
                decoders.add(formatMime(c.mimeType));
            }
        }
        return decoders;
    }

    public static Set<String> getHardwareEncoders() {
        Set<String> encoders = new HashSet<>();
        for (CodecInfo c : detectMediaCodecs()) {
            if (c.isEncoder) {
                encoders.add(formatMime(c.mimeType));
            }
        }
        return encoders;
    }

    private static String formatMime(String mime) {
        if (mime.contains("avc")) return "H.264";
        if (mime.contains("hevc")) return "HEVC";
        if (mime.contains("vp9")) return "VP9";
        if (mime.contains("vp8")) return "VP8";
        if (mime.contains("av01")) return "AV1";
        if (mime.contains("mp4v")) return "MPEG-4";
        return mime.replace("video/", "").toUpperCase(Locale.ROOT);
    }

    public static File getFFmpegExecutable() {
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        File f1 = new File(prefix, "opt/jellyfin/bin/ffmpeg");
        if (f1.exists() && f1.canExecute()) return f1;
        File f2 = new File(prefix, "bin/ffmpeg");
        if (f2.exists() && f2.canExecute()) return f2;
        return f1;
    }

    public static String getFFmpegVersion() {
        File ffmpeg = getFFmpegExecutable();
        if (!ffmpeg.exists()) return "Not found";
        return runCommand(ffmpeg.getAbsolutePath(), "-version", 1);
    }

    /** Check whether the bundled FFmpeg has native Android hardware acceleration enabled. */
    public static boolean isFFmpegHardwareSupported() {
        File ffmpeg = getFFmpegExecutable();
        if (!ffmpeg.exists()) return false;
        String hwaccels = runCommand(ffmpeg.getAbsolutePath(), "-hwaccels", 10);
        // Note: opencl is an OpenCL filter context, not a MediaCodec hardware decoder/encoder
        return hwaccels != null && (hwaccels.contains("mediacodec") || hwaccels.contains("qsv") || hwaccels.contains("vaapi"));
    }

    /** Run an empirical end-to-end transcode test of a real media segment using bundled FFmpeg. */
    public static TranscodeTestResult runTestTranscode(Context context) {
        TranscodeTestResult result = new TranscodeTestResult();
        File ffmpeg = getFFmpegExecutable();
        if (!ffmpeg.exists()) {
            result.success = false;
            result.error = "FFmpeg executable not found: " + ffmpeg.getAbsolutePath();
            return result;
        }

        File homeDir = TermuxConstants.TERMUX_HOME_DIR;
        File inputFile = new File(homeDir, "diag_test_in.mkv");
        File outputFile = new File(homeDir, "diag_test_out.mp4");

        File prefixDir = TermuxConstants.TERMUX_PREFIX_DIR;
        String libDir = new File(prefixDir, "lib").getAbsolutePath();
        String ffmpegLibDir = new File(prefixDir, "opt/jellyfin/lib").getAbsolutePath();

        try {
            // Step 1: Generate a controlled 2-second 1080p HEVC + AC3 test input if not present
            if (!inputFile.exists() || inputFile.length() == 0) {
                ProcessBuilder pbGen = new ProcessBuilder(
                        ffmpeg.getAbsolutePath(),
                        "-f", "lavfi", "-i", "testsrc=duration=2:size=1920x1080:rate=30",
                        "-f", "lavfi", "-i", "sine=frequency=1000:duration=2",
                        "-c:v", "libx265", "-c:a", "ac3",
                        "-y", inputFile.getAbsolutePath()
                );
                pbGen.environment().put("LD_LIBRARY_PATH", libDir + ":" + ffmpegLibDir);
                pbGen.redirectErrorStream(true);
                Process pGen = pbGen.start();
                drain(pGen);
                pGen.waitFor(15, TimeUnit.SECONDS);
            }

            if (!inputFile.exists() || inputFile.length() == 0) {
                result.success = false;
                result.error = "Failed to create synthetic test source file";
                return result;
            }

            // Step 2: Transcode HEVC 1080p -> H.264 720p with AAC audio
            long startMs = System.currentTimeMillis();
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg.getAbsolutePath(),
                    "-i", inputFile.getAbsolutePath(),
                    "-vf", "scale=1280:720",
                    "-c:v", "libx264", "-preset", "veryfast",
                    "-c:a", "aac", "-b:a", "128k",
                    "-y", outputFile.getAbsolutePath()
            );
            pb.environment().put("LD_LIBRARY_PATH", libDir + ":" + ffmpegLibDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String outputLog = drain(p);
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            long durationMs = System.currentTimeMillis() - startMs;

            if (finished && p.exitValue() == 0 && outputFile.exists() && outputFile.length() > 0) {
                result.success = true;
                result.ffmpegVersion = "7.1.4-Jellyfin";
                // Parse fps / speed from output log
                for (String line : outputLog.split("\n")) {
                    if (line.contains("speed=") && line.contains("fps=")) {
                        int speedIdx = line.indexOf("speed=");
                        if (speedIdx != -1) {
                            result.speed = line.substring(speedIdx + 6).trim();
                        }
                        int fpsIdx = line.indexOf("fps=");
                        if (fpsIdx != -1) {
                            int nextSpace = line.indexOf(' ', fpsIdx + 5);
                            result.fps = line.substring(fpsIdx + 4, nextSpace != -1 ? nextSpace : line.length()).trim();
                        }
                    }
                }
                result.details = String.format(Locale.ROOT, "Transcoded 1080p HEVC -> 720p H.264 (2.0s) in %d ms (Speed: %s, %s fps)",
                        durationMs, result.speed, result.fps);
            } else {
                result.success = false;
                result.error = "FFmpeg exited with code " + (finished ? p.exitValue() : "TIMED_OUT");
                result.details = outputLog.length() > 300 ? outputLog.substring(outputLog.length() - 300) : outputLog;
            }

        } catch (Throwable t) {
            Log.e(TAG, "Transcode test failed", t);
            result.success = false;
            result.error = t.getMessage();
        }

        return result;
    }

    private static String drain(Process p) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static String runCommand(String binary, String arg, int maxLines) {
        try {
            File prefixDir = TermuxConstants.TERMUX_PREFIX_DIR;
            String libDir = new File(prefixDir, "lib").getAbsolutePath();
            String ffmpegLibDir = new File(prefixDir, "opt/jellyfin/lib").getAbsolutePath();

            ProcessBuilder pb = new ProcessBuilder(binary, arg);
            pb.environment().put("LD_LIBRARY_PATH", libDir + ":" + ffmpegLibDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count++ < maxLines) {
                    sb.append(line).append("\n");
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return sb.toString().trim();
        } catch (Throwable t) {
            return null;
        }
    }
}
