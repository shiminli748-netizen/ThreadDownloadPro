package com.thread.download;

import com.thread.download.utils.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

public class DownloadTask implements Runnable {
    private final String url;
    private final File targetFile;
    private final long startByte;
    private final long endByte;
    private final int threadIndex;
    private final DownloadCallback callback;

    private volatile boolean paused = false;
    private volatile boolean cancelled = false;
    private long downloadedBytes = 0;
    private long lastSpeedBytes = 0;
    private long lastSpeedTime = 0;
    private long currentSpeed = 0;

    public interface DownloadCallback {
        void onProgress(int threadIndex, long downloaded, long total);
        void onThreadComplete(int threadIndex);
        void onThreadFailed(int threadIndex, String error);
    }

    public DownloadTask(String url, File targetFile, long startByte, long endByte,
                        int threadIndex, long alreadyDownloaded, DownloadCallback callback) {
        this.url = url;
        this.targetFile = targetFile;
        this.startByte = startByte;
        this.endByte = endByte;
        this.threadIndex = threadIndex;
        this.downloadedBytes = alreadyDownloaded;
        this.callback = callback;
    }

    public void pause() { paused = true; }
    public void cancel() { cancelled = true; }

    public long getDownloadedBytes() { return downloadedBytes; }
    public long getCurrentSpeed() { return currentSpeed; }

    public long getTotalBytes() { return endByte - startByte + 1; }

    @Override
    public void run() {
        HttpURLConnection conn = null;
        InputStream is = null;
        RandomAccessFile raf = null;

        try {
            long currentStart = startByte + downloadedBytes;
            if (currentStart > endByte) {
                callback.onThreadComplete(threadIndex);
                return;
            }

            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Range", "bytes=" + currentStart + "-" + endByte);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200 && responseCode != 206) {
                callback.onThreadFailed(threadIndex, "Server returned " + responseCode);
                return;
            }

            File partFile = FileUtils.createTempFile(targetFile, threadIndex);
            raf = new RandomAccessFile(partFile, "rw");
            raf.seek(downloadedBytes);

            is = conn.getInputStream();
            byte[] buffer = new byte[65536];
            int len;
            long lastCallbackTime = System.currentTimeMillis();
            long lastCallbackBytes = downloadedBytes;

            while ((len = is.read(buffer)) != -1) {
                if (cancelled) {
                    callback.onThreadFailed(threadIndex, "Cancelled");
                    return;
                }
                if (paused) {
                    saveProgress();
                    callback.onThreadFailed(threadIndex, "Paused");
                    return;
                }

                raf.write(buffer, 0, len);
                downloadedBytes += len;

                long now = System.currentTimeMillis();
                if (now - lastCallbackTime >= 200) {
                    long bytesDelta = downloadedBytes - lastCallbackBytes;
                    long timeDelta = now - lastCallbackTime;
                    if (timeDelta > 0) {
                        currentSpeed = (bytesDelta * 1000) / timeDelta;
                    }
                    callback.onProgress(threadIndex, downloadedBytes, getTotalBytes());
                    lastCallbackTime = now;
                    lastCallbackBytes = downloadedBytes;
                }
            }

            currentSpeed = 0;
            callback.onProgress(threadIndex, downloadedBytes, getTotalBytes());
            callback.onThreadComplete(threadIndex);

        } catch (IOException e) {
            saveProgress();
            callback.onThreadFailed(threadIndex, e.getMessage());
        } finally {
            try { if (is != null) is.close(); } catch (IOException ignored) {}
            try { if (raf != null) raf.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private void saveProgress() {
        File infoFile = FileUtils.createInfoFile(targetFile);
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new FileOutputStream(infoFile, true))) {
            pw.println(threadIndex + "=" + downloadedBytes);
        } catch (IOException ignored) {}
    }
}
