package com.thread.download;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;

import com.thread.download.databinding.ActivityMainBinding;
import com.thread.download.utils.FileUtils;
import com.thread.download.utils.NetworkUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DownloadManager.DownloadListener {

    private ActivityMainBinding binding;
    private DownloadManager downloadManager;
    private DownloadService downloadService;
    private boolean isServiceBound = false;

    private final List<HistoryItem> historyItems = new ArrayList<>();

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean granted = result.get(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (granted == null || !granted) {
                    Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        downloadManager = new DownloadManager();
        downloadManager.setListener(this);

        checkPermissions();
        setupUI();
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT <= 28) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT <= 32) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            permissionLauncher.launch(perms.toArray(new String[0]));
        }
    }

    private void setupUI() {
        binding.inputLayoutUrl.setEndIconOnClickListener(v -> pasteFromClipboard());

        binding.btnStart.setOnClickListener(v -> startDownload());

        binding.btnPause.setOnClickListener(v -> {
            DownloadManager.State state = downloadManager.getState();
            if (state == DownloadManager.State.DOWNLOADING) {
                downloadManager.pause();
                binding.btnPause.setText(R.string.btn_resume);
            } else if (state == DownloadManager.State.PAUSED) {
                downloadManager.resume();
                binding.btnPause.setText(R.string.btn_pause);
            }
        });

        binding.btnCancel.setOnClickListener(v -> {
            downloadManager.cancel();
            hideProgress();
        });
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
            if (text != null) {
                binding.editUrl.setText(text.toString().trim());
            }
        }
    }

    private int getSelectedThreadCount() {
        int checkedId = binding.radioGroupThreads.getCheckedRadioButtonId();
        if (checkedId == R.id.radio_64) return 64;
        if (checkedId == R.id.radio_256) return 256;
        return 128;
    }

    private void startDownload() {
        String url = binding.editUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_url, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!NetworkUtils.isValidUrl(url)) {
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show();
            return;
        }

        int threads = getSelectedThreadCount();
        showProgress();
        downloadManager.startDownload(url, threads);

        Intent serviceIntent = new Intent(this, DownloadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void showProgress() {
        binding.labelProgress.setVisibility(View.VISIBLE);
        binding.cardProgress.setVisibility(View.VISIBLE);
        binding.btnStart.setEnabled(false);
        binding.btnPause.setText(R.string.btn_pause);
        binding.btnPause.setEnabled(true);
        binding.btnCancel.setEnabled(true);
    }

    private void hideProgress() {
        binding.btnStart.setEnabled(true);
        binding.btnPause.setEnabled(false);
        binding.btnCancel.setEnabled(false);
    }

    @Override
    public void onStateChanged(DownloadManager.State state) {
        runOnUiThread(() -> {
            switch (state) {
                case CONNECTING:
                    binding.textStatus.setText(R.string.status_connecting);
                    break;
                case DOWNLOADING:
                    binding.textStatus.setText(R.string.status_downloading);
                    break;
                case PAUSED:
                    binding.textStatus.setText(R.string.status_paused);
                    binding.btnPause.setText(R.string.btn_resume);
                    break;
                case CANCELLED:
                    binding.textStatus.setText(R.string.status_cancelled);
                    hideProgress();
                    addHistory(binding.editUrl.getText().toString().trim(), getSelectedThreadCount(), "cancelled");
                    stopDownloadService();
                    break;
                case FAILED:
                    binding.textStatus.setText(R.string.status_failed);
                    hideProgress();
                    addHistory(binding.editUrl.getText().toString().trim(), getSelectedThreadCount(), "failed");
                    stopDownloadService();
                    break;
                case COMPLETED:
                    binding.textStatus.setText(R.string.status_completed);
                    hideProgress();
                    stopDownloadService();
                    break;
            }
        });
    }

    @Override
    public void onProgress(long downloadedBytes, long totalBytes, long speedBps, int activeThreads) {
        runOnUiThread(() -> {
            int threads = getSelectedThreadCount();
            if (totalBytes > 0) {
                int percent = (int) ((downloadedBytes * 100) / totalBytes);
                binding.progressBar.setProgress(percent);
                binding.textPercent.setText(percent + "%");
                binding.textSize.setText(FileUtils.formatSize(downloadedBytes) + " / " + FileUtils.formatSize(totalBytes));

                if (speedBps > 0) {
                    long remaining = (totalBytes - downloadedBytes) / speedBps;
                    binding.textRemaining.setText(getString(R.string.time_remaining, FileUtils.formatTime(remaining)));
                } else {
                    binding.textRemaining.setText(getString(R.string.time_remaining, FileUtils.formatTime(-1)));
                }
            } else {
                binding.progressBar.setProgress(0);
                binding.textPercent.setText("--%");
                binding.textSize.setText(FileUtils.formatSize(downloadedBytes));
                binding.textRemaining.setText("");
            }

            binding.textSpeed.setText(FileUtils.formatSpeed(speedBps));
            binding.textActiveThreads.setText(getString(R.string.active_threads, activeThreads, threads));
        });
    }

    @Override
    public void onComplete(File file) {
        runOnUiThread(() -> {
            binding.progressBar.setProgress(100);
            binding.textPercent.setText("100%");
            binding.textStatus.setText(R.string.status_completed);
            hideProgress();
            addHistory(file.getName(), getSelectedThreadCount(), "completed");

            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.status_completed)
                    .setMessage("文件已保存到: " + file.getAbsolutePath())
                    .setPositiveButton(R.string.btn_open, (d, w) -> openFile(file))
                    .setNegativeButton(R.string.btn_share, (d, w) -> shareFile(file))
                    .setNeutralButton(android.R.string.ok, null)
                    .show();
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onNoRangeSupport() {
        runOnUiThread(() -> {
            Toast.makeText(this, R.string.status_no_range, Toast.LENGTH_LONG).show();
        });
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMimeType(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent shareIntent = ShareCompat.IntentBuilder.from(this)
                    .setType(getMimeType(file.getName()))
                    .setStream(uri)
                    .createChooserIntent()
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(shareIntent);
        } catch (Exception e) {
            Toast.makeText(this, "无法分享文件", Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            case "apk": return "application/vnd.android.package-archive";
            case "zip": return "application/zip";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "pdf": return "application/pdf";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            default: return "application/octet-stream";
        }
    }

    private void addHistory(String name, int threads, String status) {
        historyItems.add(0, new HistoryItem(name, threads, status));
        updateHistoryUI();
    }

    private void updateHistoryUI() {
        if (historyItems.isEmpty()) {
            binding.labelHistory.setVisibility(View.GONE);
            return;
        }
        binding.labelHistory.setVisibility(View.VISIBLE);
        binding.layoutHistory.removeAllViews();

        for (HistoryItem item : historyItems) {
            View itemView = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, binding.layoutHistory, false);
            TextView text1 = itemView.findViewById(android.R.id.text1);
            TextView text2 = itemView.findViewById(android.R.id.text2);

            text1.setText(item.name + " (" + item.threads + "线程)");
            text1.setTextColor(getColor(R.color.md_theme_onSurface));
            text1.setTextSize(14);

            String statusText;
            int statusColor;
            switch (item.status) {
                case "completed":
                    statusText = getString(R.string.history_completed);
                    statusColor = getColor(R.color.download_success);
                    break;
                case "failed":
                    statusText = getString(R.string.history_failed);
                    statusColor = getColor(R.color.download_error);
                    break;
                default:
                    statusText = getString(R.string.history_cancelled);
                    statusColor = getColor(R.color.md_theme_onSurfaceVariant);
                    break;
            }
            text2.setText(statusText);
            text2.setTextColor(statusColor);
            text2.setTextSize(12);

            binding.layoutHistory.addView(itemView);
        }
    }

    private void stopDownloadService() {
        Intent serviceIntent = new Intent(this, DownloadService.class);
        stopService(serviceIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloadManager.release();
        stopDownloadService();
    }

    private static class HistoryItem {
        String name;
        int threads;
        String status;

        HistoryItem(String name, int threads, String status) {
            this.name = name;
            this.threads = threads;
            this.status = status;
        }
    }
}
