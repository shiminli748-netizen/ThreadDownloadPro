package com.thread.download.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class FileUtils {

    public static final String DOWNLOAD_DIR = "/storage/emulated/0/ThreadDownload";

    public static File getDownloadDir() {
        File dir = new File(DOWNLOAD_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static String getFileNameFromUrl(String url) {
        try {
            String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8.name());
            int queryIndex = decoded.indexOf('?');
            if (queryIndex > 0) {
                decoded = decoded.substring(0, queryIndex);
            }
            int lastSlash = decoded.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < decoded.length() - 1) {
                String name = decoded.substring(lastSlash + 1);
                if (!name.isEmpty() && name.length() < 256) {
                    return name;
                }
            }
        } catch (Exception ignored) {}
        return "download_" + System.currentTimeMillis();
    }

    public static File createTempFile(File targetFile, int threadIndex) {
        return new File(targetFile.getParent(), targetFile.getName() + ".part" + threadIndex);
    }

    public static File createInfoFile(File targetFile) {
        return new File(targetFile.getParent(), targetFile.getName() + ".info");
    }

    public static void mergeFiles(File[] partFiles, File outputFile, long totalSize) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            raf.setLength(totalSize);
            for (File part : partFiles) {
                if (part != null && part.exists()) {
                    java.io.FileInputStream fis = new java.io.FileInputStream(part);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        raf.write(buffer, 0, len);
                    }
                    fis.close();
                    part.delete();
                }
            }
        }
    }

    public static void deletePartFiles(File targetFile, int threadCount) {
        for (int i = 0; i < threadCount; i++) {
            File part = createTempFile(targetFile, i);
            if (part.exists()) part.delete();
        }
        File info = createInfoFile(targetFile);
        if (info.exists()) info.delete();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024));
    }

    public static String formatTime(long seconds) {
        if (seconds < 0) return "--:--";
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分" + (seconds % 60) + "秒";
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        return hours + "时" + mins + "分";
    }
}
