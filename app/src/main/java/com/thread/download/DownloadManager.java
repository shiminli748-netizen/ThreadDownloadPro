package com.thread.download;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.thread.download.utils.FileUtils;
import com.thread.download.utils.NetworkUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadManager {
    private static final String TAG = "DownloadManager";

    public enum State { IDLE, CONNECTING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

    private State state = State.IDLE;
    private String url;
    private int threadCount;
    private long fileSize = -1;
    private File targetFile;
    private boolean supportsRange = false;

    private final ThreadPoolManager threadPool = new ThreadPoolManager();
    private final List<DownloadTask> tasks = new ArrayList<>();
    private final AtomicInteger completedThreads = new AtomicInteger(0);
    private final AtomicInteger failedThreads = new AtomicInteger(0);
    private final Map<Integer, Long> threadProgress = new HashMap<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastUpdateTime = 0;
    private long lastSpeedCalcTime = 0;
    private long lastDownloadedBytes = 0;
    private long currentSpeed = 0;

    private DownloadListener listener;

    public interface DownloadListener {
        void onStateChanged(State state);
        void onProgress(long downloadedBytes, long totalBytes, long speedBps, int activeThreads);
        void onComplete(File file);
        void onError(String message);
        void onNoRangeSupport();
    }

    public void setListener(DownloadListener listener) {
        this.listener = listener;
    }

    public State getState() { return state; }

    public void startDownload(String url, int threadCount) {
        if (state == State.DOWNLOADING || state == State.CONNECTING) return;

        this.url = url;
        this.threadCount = threadCount;
        this.tasks.clear();
        this.threadProgress.clear();
        this.completedThreads.set(0);
        this.failedThreads.set(0);
        this.currentSpeed = 0;
        this.lastDownloadedBytes = 0;
        this.lastSpeedCalcTime = System.currentTimeMillis();

        setState(State.CONNECTING);

        new Thread(() -> {
            try {
                fileSize = NetworkUtils.getFileSize(url);
                supportsRange = NetworkUtils.supportsRange(url);

                String fileName = FileUtils.getFileNameFromUrl(url);
                File dir = FileUtils.getDownloadDir();
                targetFile = new File(dir, fileName);

                if (targetFile.exists() && fileSize > 0 && targetFile.length() == fileSize) {
                    mainHandler.post(() -> {
                        setState(State.COMPLETED);
                        if (listener != null) listener.onComplete(targetFile);
                    });
                    return;
                }

                if (fileSize <= 0 || !supportsRange) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onNoRangeSupport();
                        startSingleThreadDownload();
                    });
                    return;
                }

                startMultiThreadDownload();

            } catch (Exception e) {
                mainHandler.post(() -> {
                    setState(State.FAILED);
                    if (listener != null) listener.onError(e.getMessage());
                });
            }
        }).start();
    }

    private void startSingleThreadDownload() {
        threadCount = 1;
        setState(State.DOWNLOADING);

        threadPool.createPool(1);

        DownloadTask task = new DownloadTask(url, targetFile, 0, fileSize - 1, 0, 0,
                new DownloadTask.DownloadCallback() {
                    @Override
                    public void onProgress(int threadIndex, long downloaded, long total) {
                        threadProgress.put(threadIndex, downloaded);
                        notifyProgress();
                    }
                    @Override
                    public void onThreadComplete(int threadIndex) {
                        completedThreads.incrementAndGet();
                        checkCompletion();
                    }
                    @Override
                    public void onThreadFailed(int threadIndex, String error) {
                        if ("Paused".equals(error)) {
                            mainHandler.post(() -> setState(State.PAUSED));
                        } else if ("Cancelled".equals(error)) {
                            mainHandler.post(() -> setState(State.CANCELLED));
                        } else {
                            failedThreads.incrementAndGet();
                            checkCompletion();
                        }
                    }
                });

        tasks.add(task);
        threadPool.execute(task);
    }

    private void startMultiThreadDownload() {
        setState(State.DOWNLOADING);
        threadPool.createPool(threadCount);

        Map<Integer, Long> savedProgress = loadProgress();

        long chunkSize = fileSize / threadCount;

        for (int i = 0; i < threadCount; i++) {
            long start = i * chunkSize;
            long end = (i == threadCount - 1) ? fileSize - 1 : (start + chunkSize - 1);
            long alreadyDownloaded = savedProgress.containsKey(i) ? savedProgress.get(i) : 0;

            DownloadTask task = new DownloadTask(url, targetFile, start, end, i, alreadyDownloaded,
                    new DownloadTask.DownloadCallback() {
                        @Override
                        public void onProgress(int threadIndex, long downloaded, long total) {
                            threadProgress.put(threadIndex, downloaded);
                            notifyProgress();
                        }
                        @Override
                        public void onThreadComplete(int threadIndex) {
                            completedThreads.incrementAndGet();
                            checkCompletion();
                        }
                        @Override
                        public void onThreadFailed(int threadIndex, String error) {
                            if ("Paused".equals(error)) {
                                mainHandler.post(() -> setState(State.PAUSED));
                            } else if ("Cancelled".equals(error)) {
                                mainHandler.post(() -> setState(State.CANCELLED));
                            } else {
                                failedThreads.incrementAndGet();
                                checkCompletion();
                            }
                        }
                    });

            tasks.add(task);
            threadProgress.put(i, alreadyDownloaded);
            threadPool.execute(task);
        }
    }

    private Map<Integer, Long> loadProgress() {
        Map<Integer, Long> progress = new HashMap<>();
        if (targetFile == null) return progress;
        File infoFile = FileUtils.createInfoFile(targetFile);
        if (!infoFile.exists()) return progress;
        try (BufferedReader reader = new BufferedReader(new FileReader(infoFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    progress.put(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
                }
            }
        } catch (Exception ignored) {}
        return progress;
    }

    private void notifyProgress() {
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime < 300) return;
        lastUpdateTime = now;

        long totalDownloaded = 0;
        for (Long bytes : threadProgress.values()) {
            totalDownloaded += bytes;
        }

        long timeDelta = now - lastSpeedCalcTime;
        if (timeDelta > 0) {
            long bytesDelta = totalDownloaded - lastDownloadedBytes;
            currentSpeed = (bytesDelta * 1000) / timeDelta;
        }
        lastDownloadedBytes = totalDownloaded;
        lastSpeedCalcTime = now;

        int activeThreads = threadCount - completedThreads.get() - failedThreads.get();
        final long finalSpeed = currentSpeed;
        final long finalDownloaded = totalDownloaded;

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onProgress(finalDownloaded, fileSize, finalSpeed, activeThreads);
            }
        });
    }

    private void checkCompletion() {
        if (completedThreads.get() + failedThreads.get() >= threadCount) {
            if (completedThreads.get() == threadCount) {
                try {
                    File[] partFiles = new File[threadCount];
                    for (int i = 0; i < threadCount; i++) {
                        partFiles[i] = FileUtils.createTempFile(targetFile, i);
                    }
                    FileUtils.mergeFiles(partFiles, targetFile, fileSize);
                    FileUtils.deletePartFiles(targetFile, threadCount);

                    mainHandler.post(() -> {
                        setState(State.COMPLETED);
                        if (listener != null) listener.onComplete(targetFile);
                    });
                } catch (IOException e) {
                    mainHandler.post(() -> {
                        setState(State.FAILED);
                        if (listener != null) listener.onError("合并文件失败: " + e.getMessage());
                    });
                }
            } else {
                mainHandler.post(() -> {
                    if (state != State.PAUSED && state != State.CANCELLED) {
                        setState(State.FAILED);
                        if (listener != null) listener.onError("部分线程下载失败");
                    }
                });
            }
        }
    }

    public void pause() {
        if (state != State.DOWNLOADING) return;
        for (DownloadTask task : tasks) {
            task.pause();
        }
        threadPool.shutdown();
    }

    public void resume() {
        if (state != State.PAUSED) return;
        tasks.clear();
        startDownload(url, threadCount);
    }

    public void cancel() {
        for (DownloadTask task : tasks) {
            task.cancel();
        }
        threadPool.shutdown();
        if (targetFile != null) {
            FileUtils.deletePartFiles(targetFile, threadCount);
        }
        setState(State.CANCELLED);
    }

    private void setState(State newState) {
        state = newState;
        mainHandler.post(() -> {
            if (listener != null) listener.onStateChanged(newState);
        });
    }

    public void release() {
        cancel();
    }
}
