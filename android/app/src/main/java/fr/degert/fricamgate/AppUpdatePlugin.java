package fr.degert.fricamgate;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(name = "AppUpdate")
public class AppUpdatePlugin extends Plugin {
    private final Map<Long, File> downloads = new ConcurrentHashMap<>();
    private BroadcastReceiver downloadReceiver;

    @Override
    public void load() {
        registerDownloadReceiver();
    }

    @PluginMethod
    public void downloadApk(PluginCall call) {
        String url = call.getString("url");
        String fileName = sanitizeFileName(call.getString("fileName"));
        String title = call.getString("title", "FriCamGate update");
        String description = call.getString("description", "Telechargement de la mise a jour FriCamGate");

        if (url == null || url.isEmpty()) {
            call.reject("Missing update url");
            return;
        }

        File downloadDirectory = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDirectory == null) {
            call.reject("Download directory unavailable");
            return;
        }

        if (!downloadDirectory.exists() && !downloadDirectory.mkdirs()) {
            call.reject("Download directory cannot be created");
            return;
        }

        File targetFile = new File(downloadDirectory, fileName);
        if (targetFile.exists() && !targetFile.delete()) {
            call.reject("Previous update file cannot be replaced");
            return;
        }

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setDescription(description)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .addRequestHeader("User-Agent", "FriCamGate");
            request.setDestinationUri(Uri.fromFile(targetFile));

            DownloadManager downloadManager = getDownloadManager();
            if (downloadManager == null) {
                call.reject("Download manager unavailable");
                return;
            }

            long downloadId = downloadManager.enqueue(request);
            downloads.put(downloadId, targetFile);

            JSObject result = new JSObject();
            result.put("downloadId", downloadId);
            call.resolve(result);
        } catch (IllegalArgumentException error) {
            call.reject("Invalid update url");
        } catch (SecurityException error) {
            call.reject("Update download not allowed");
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (downloadReceiver != null) {
            try {
                getContext().unregisterReceiver(downloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already unregistered by Android.
            }
            downloadReceiver = null;
        }

        downloads.clear();
        super.handleOnDestroy();
    }

    private void registerDownloadReceiver() {
        if (downloadReceiver != null) {
            return;
        }

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    return;
                }

                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                File file = downloads.remove(downloadId);
                if (file != null && isSuccessfulDownload(downloadId)) {
                    openApkInstaller(file);
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(downloadReceiver, filter);
        }
    }

    private boolean isSuccessfulDownload(long downloadId) {
        DownloadManager downloadManager = getDownloadManager();
        if (downloadManager == null) {
            return false;
        }

        try (Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return false;
            }

            int statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return statusColumn >= 0 && cursor.getInt(statusColumn) == DownloadManager.STATUS_SUCCESSFUL;
        }
    }

    private void openApkInstaller(File file) {
        Context context = getContext();
        Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        Intent installIntent = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(installIntent);
        } catch (RuntimeException ignored) {
            // The DownloadManager notification still points to the downloaded APK.
        }
    }

    private DownloadManager getDownloadManager() {
        return (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    private String sanitizeFileName(String value) {
        String fileName = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (!fileName.toLowerCase().endsWith(".apk")) {
            return "FriCamGate-update.apk";
        }

        return fileName;
    }
}
