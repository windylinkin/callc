package com.example.callrecorderuploader.utils;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import java.io.File;

public class FileUtils {
    private static final String TAG = "FileUtils";

    public static String getFileNameFromUri(Context context, Uri uri) {
        String fileName = null;
        if (uri == null) return "unknown_file";

        if ("content".equalsIgnoreCase(uri.getScheme())) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                        fileName = cursor.getString(displayNameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting filename from content URI: " + uri, e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (TextUtils.isEmpty(fileName)) {
            fileName = uri.getLastPathSegment();
            // Sanitize if it's a path segment that might not be a good filename
            if (fileName != null && fileName.contains("/")) {
                fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
            }
        }
        return TextUtils.isEmpty(fileName) ? "unknown_file_" + System.currentTimeMillis() : fileName;
    }

    public static String getFileNameFromPath(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return "unknown_file";
        }
        return new File(filePath).getName();
    }


    public static String determineMimeType(String fileName) {
        if (TextUtils.isEmpty(fileName)) return "application/octet-stream";
        String lowerFileName = fileName.toLowerCase();
        if (lowerFileName.endsWith(".mp3")) return "audio/mpeg";
        if (lowerFileName.endsWith(".amr")) return "audio/amr";
        if (lowerFileName.endsWith(".wav")) return "audio/wav";
        if (lowerFileName.endsWith(".m4a") || lowerFileName.endsWith(".mp4")) return "audio/mp4"; // mp4 can be audio too
        if (lowerFileName.endsWith(".ogg") || lowerFileName.endsWith(".oga")) return "audio/ogg";
        if (lowerFileName.endsWith(".aac")) return "audio/aac";
        if (lowerFileName.endsWith(".3gp") || lowerFileName.endsWith(".3gpp")) return "audio/3gpp"; // Or video/3gpp

        // Add more MIME types as needed
        Log.w(TAG, "Could not determine specific MIME type for: " + fileName + ", using generic.");
        return "application/octet-stream"; // Default MIME type
    }

    /**
     * 规范化电话号码，移除常见前缀和非数字字符，用于比较。
     * 例如 "+8613812345678" -> "13812345678"
     * "013812345678" -> "13812345678" (如果国内0开头)
     */
    public static String normalizePhoneNumber(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            return "Unknown";
        }
        String normalized = phoneNumber.replaceAll("[^0-9]", ""); // 只保留数字
        if (normalized.startsWith("86") && normalized.length() > 11) { // 移除中国区号86
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("0") && normalized.length() > 10) { // 移除国内长途前缀0
            // 这个逻辑可能需要根据实际号码格式调整，例如有些手机号本身就可能以0开头（固话）
            // normalized = normalized.substring(1);
        }
        return normalized.isEmpty() ? "Unknown" : normalized;
    }

    /**
     * 将 SystemClock.elapsedRealtime() 转换为近似的实际日历时间戳。
     * 注意：这只是一个估算，因为它依赖于设备启动后的时间。
     * 更可靠的方式是在事件发生时直接使用 System.currentTimeMillis()。
     */
    public static long convertElapsedTimestampToActual(long elapsedTimestampMs) {
        long uptimeDelta = SystemClock.elapsedRealtime() - elapsedTimestampMs;
        return System.currentTimeMillis() - uptimeDelta;
    }
}