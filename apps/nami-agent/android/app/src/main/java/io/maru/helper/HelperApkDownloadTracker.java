package io.maru.helper;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import java.util.HashMap;
import java.util.Map;

public final class HelperApkDownloadTracker {
    public static final String DOWNLOAD_TITLE = "Maru APK Download";

    private static final String PREFS_NAME = "maru_helper_apk_downloads";
    private static final String KEY_DOWNLOAD_ID_PREFIX = "download_id:";

    public static final class DownloadSnapshot {
        public final String filename;
        public final long downloadId;
        public final int progressPercent;
        public final boolean visible;
        public final boolean active;
        public final boolean indeterminate;
        public final boolean successful;
        public final boolean failed;
        public final String label;
        public final String note;

        DownloadSnapshot(
            String filename,
            long downloadId,
            int progressPercent,
            boolean visible,
            boolean active,
            boolean indeterminate,
            boolean successful,
            boolean failed,
            String label,
            String note
        ) {
            this.filename = filename == null ? "" : filename.trim();
            this.downloadId = downloadId;
            this.progressPercent = clampPercent(progressPercent);
            this.visible = visible;
            this.active = active;
            this.indeterminate = indeterminate;
            this.successful = successful;
            this.failed = failed;
            this.label = label == null ? "" : label.trim();
            this.note = note == null ? "" : note.trim();
        }
    }

    private HelperApkDownloadTracker() {
    }

    public static void trackDownload(Context context, String rawFilename, long downloadId) {
        String filename = normalizeFilename(rawFilename);
        if (context == null || filename.isEmpty() || downloadId <= 0L) {
            return;
        }

        prefs(context)
            .edit()
            .putLong(buildDownloadIdKey(filename), downloadId)
            .apply();
    }

    public static void clearDownload(Context context, String rawFilename) {
        String filename = normalizeFilename(rawFilename);
        if (context == null || filename.isEmpty()) {
            return;
        }

        prefs(context)
            .edit()
            .remove(buildDownloadIdKey(filename))
            .apply();
    }

    public static DownloadSnapshot getSnapshot(Context context, String rawFilename) {
        String filename = normalizeFilename(rawFilename);
        if (context == null || filename.isEmpty()) {
            return emptySnapshot(filename);
        }

        long downloadId = prefs(context).getLong(buildDownloadIdKey(filename), -1L);
        if (downloadId <= 0L) {
            return emptySnapshot(filename);
        }

        DownloadManager downloadManager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            return new DownloadSnapshot(
                filename,
                downloadId,
                0,
                true,
                false,
                false,
                false,
                true,
                "Download unavailable",
                "This phone could not check Android's download manager right now."
            );
        }

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        Cursor cursor = downloadManager.query(query);
        if (cursor == null) {
            return emptySnapshot(filename);
        }

        try {
            if (!cursor.moveToFirst()) {
                clearDownload(context, filename);
                return emptySnapshot(filename);
            }

            int status = getInt(cursor, DownloadManager.COLUMN_STATUS, DownloadManager.STATUS_FAILED);
            long bytesSoFar = getLong(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, 0L);
            long totalBytes = getLong(cursor, DownloadManager.COLUMN_TOTAL_SIZE_BYTES, -1L);
            int progressPercent = 0;
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                progressPercent = 100;
            } else if (totalBytes > 0L) {
                progressPercent = (int) Math.round((bytesSoFar * 100.0d) / totalBytes);
            }

            if (status == DownloadManager.STATUS_PENDING) {
                return new DownloadSnapshot(
                    filename,
                    downloadId,
                    progressPercent,
                    true,
                    true,
                    true,
                    false,
                    false,
                    "Waiting for Android to start the download",
                    "The APK is queued in Android's download manager."
                );
            }

            if (status == DownloadManager.STATUS_RUNNING) {
                boolean indeterminate = totalBytes <= 0L;
                return new DownloadSnapshot(
                    filename,
                    downloadId,
                    progressPercent,
                    true,
                    true,
                    indeterminate,
                    false,
                    false,
                    indeterminate
                        ? "Downloading..."
                        : "Downloading... " + clampPercent(progressPercent) + "%",
                    indeterminate
                        ? "Android has started the download."
                        : formatProgress(bytesSoFar, totalBytes)
                );
            }

            if (status == DownloadManager.STATUS_PAUSED) {
                return new DownloadSnapshot(
                    filename,
                    downloadId,
                    progressPercent,
                    true,
                    true,
                    totalBytes <= 0L,
                    false,
                    false,
                    "Download paused",
                    firstNonEmpty(
                        resolvePausedReason(getInt(cursor, DownloadManager.COLUMN_REASON, 0)),
                        "Android paused the download for a moment."
                    )
                );
            }

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                return new DownloadSnapshot(
                    filename,
                    downloadId,
                    100,
                    true,
                    false,
                    false,
                    true,
                    false,
                    "Download finished",
                    "Finish the Android install prompt to apply this APK."
                );
            }

            return new DownloadSnapshot(
                filename,
                downloadId,
                progressPercent,
                true,
                false,
                false,
                false,
                true,
                "Download failed",
                firstNonEmpty(
                    resolveFailureReason(getInt(cursor, DownloadManager.COLUMN_REASON, 0)),
                    "Android could not finish that APK download."
                )
            );
        } finally {
            cursor.close();
        }
    }

    public static String findTrackedFilenameByDownloadId(Context context, long downloadId) {
        if (context == null || downloadId <= 0L) {
            return "";
        }

        SharedPreferences preferences = prefs(context);
        Map<String, ?> values = new HashMap<>(preferences.getAll());
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(KEY_DOWNLOAD_ID_PREFIX)) {
                continue;
            }

            Object rawValue = entry.getValue();
            long trackedId =
                rawValue instanceof Number
                    ? ((Number) rawValue).longValue()
                    : -1L;
            if (trackedId != downloadId) {
                continue;
            }

            return key.substring(KEY_DOWNLOAD_ID_PREFIX.length());
        }
        return "";
    }

    private static DownloadSnapshot emptySnapshot(String rawFilename) {
        return new DownloadSnapshot(
            normalizeFilename(rawFilename),
            -1L,
            0,
            false,
            false,
            false,
            false,
            false,
            "",
            ""
        );
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String buildDownloadIdKey(String filename) {
        return KEY_DOWNLOAD_ID_PREFIX + filename;
    }

    private static String normalizeFilename(String rawFilename) {
        return rawFilename == null ? "" : rawFilename.trim().toLowerCase();
    }

    private static int clampPercent(int rawPercent) {
        if (rawPercent < 0) {
            return 0;
        }
        if (rawPercent > 100) {
            return 100;
        }
        return rawPercent;
    }

    private static int getInt(Cursor cursor, String columnName, int fallback) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0) {
            return fallback;
        }
        return cursor.getInt(index);
    }

    private static long getLong(Cursor cursor, String columnName, long fallback) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0) {
            return fallback;
        }
        return cursor.getLong(index);
    }

    private static String formatProgress(long bytesSoFar, long totalBytes) {
        if (bytesSoFar <= 0L || totalBytes <= 0L) {
            return "Android is downloading the APK.";
        }
        return formatBytes(bytesSoFar) + " of " + formatBytes(totalBytes);
    }

    private static String formatBytes(long rawBytes) {
        long bytes = Math.max(0L, rawBytes);
        if (bytes < 1024L * 1024L) {
            long kilobytes = Math.max(1L, Math.round(bytes / 1024.0d));
            return kilobytes + " KB";
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0d * 1024.0d));
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String resolvePausedReason(int reason) {
        switch (reason) {
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return "Waiting for a network connection.";
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return "Waiting for Wi-Fi before continuing.";
            case DownloadManager.PAUSED_WAITING_TO_RETRY:
                return "Android will retry the download shortly.";
            case DownloadManager.PAUSED_UNKNOWN:
            default:
                return "";
        }
    }

    private static String resolveFailureReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "Android could not resume that download. Try again.";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Android could not access the download storage.";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "That APK file already exists in Downloads.";
            case DownloadManager.ERROR_FILE_ERROR:
                return "Android hit a file error while saving the APK.";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "The download stream broke before the APK finished.";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "This phone does not have enough storage for that APK.";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "The download link redirected too many times.";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "The server answered with an unexpected download response.";
            case DownloadManager.ERROR_UNKNOWN:
            default:
                return "";
        }
    }
}
