package com.thread.download;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ThreadPoolManager {
    private ExecutorService executor;
    private int threadCount;

    public void createPool(int threadCount) {
        shutdown();
        this.threadCount = threadCount;
        this.executor = Executors.newFixedThreadPool(threadCount);
    }

    public void execute(Runnable task) {
        if (executor != null && !executor.isShutdown()) {
            executor.execute(task);
        }
    }

    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
        executor = null;
    }

    public int getThreadCount() { return threadCount; }

    public boolean isShutdown() {
        return executor == null || executor.isShutdown();
    }
}
