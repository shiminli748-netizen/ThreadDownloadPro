package com.thread.download.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.net.HttpURLConnection;
import java.net.URL;

public class NetworkUtils {

    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    public static boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            new URL(url);
            return url.startsWith("http://") || url.startsWith("https://");
        } catch (Exception e) {
            return false;
        }
    }

    public static long getFileSize(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                long size = conn.getContentLengthLong();
                conn.disconnect();
                return size;
            }
            conn.disconnect();
        } catch (Exception ignored) {}
        return -1;
    }

    public static boolean supportsRange(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();
            String acceptRanges = conn.getHeaderField("Accept-Ranges");
            conn.disconnect();
            return acceptRanges != null && acceptRanges.contains("bytes");
        } catch (Exception ignored) {}
        return false;
    }
}
