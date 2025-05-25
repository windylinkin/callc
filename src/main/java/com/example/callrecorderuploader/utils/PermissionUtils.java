package com.example.callrecorderuploader.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionUtils {

    public static String[] getBasePermissions(int sdkVersion) {
        if (sdkVersion >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            return new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS, // For notifications
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE
                    // READ_MEDIA_AUDIO is for accessing OTHER apps' audio.
                    // For app's own recordings in its own directory, or MANAGE_EXTERNAL_STORAGE, it's different.
            };
        } else { // Android 12 and below
            return new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE
                    // WRITE_EXTERNAL_STORAGE needed for < API 29 if not using app-specific dir or SAF
            };
        }
    }

    public static boolean hasBasePermissions(Context context, int sdkVersion) {
        for (String perm : getBasePermissions(sdkVersion)) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasReadExternalStoragePermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
     public static void requestReadExternalStoragePermission(Activity activity, int requestCode) {
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, requestCode);
    }


    public static boolean hasWriteExternalStoragePermission(Context context, int sdkVersion) {
        if (sdkVersion <= Build.VERSION_CODES.P) { // Android 9 and below
            return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Not explicitly needed for app-specific dirs or MANAGE_EXTERNAL_STORAGE on Q+
    }

    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; // Not applicable for older versions
    }

    public static void requestOverlayPermission(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(activity)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, requestCode);
            } else {
                Toast.makeText(activity, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static boolean hasManageAllFilesAccessPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            return Environment.isExternalStorageManager();
        }
        return true; // Not applicable for older versions
    }

    public static void requestManageAllFilesAccessPermission(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Uri uri = Uri.parse("package:" + activity.getPackageName());
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
                    activity.startActivityForResult(intent, requestCode);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    activity.startActivityForResult(intent, requestCode);
                }
            } else {
                 Toast.makeText(activity, "所有文件访问权限已授予", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static boolean hasAllRequiredPermissionsForMiUiMonitoring(Context context) {
        boolean baseOk = hasBasePermissions(context, Build.VERSION.SDK_INT);
        boolean storageOk;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageOk = Environment.isExternalStorageManager();
        } else {
            storageOk = hasReadExternalStoragePermission(context) && hasWriteExternalStoragePermission(context, Build.VERSION.SDK_INT);
        }
        return baseOk && storageOk;
    }

     public static boolean canAccessMiUiRecordingFolder(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            // For older versions, standard READ_EXTERNAL_STORAGE should suffice if the folder is world-readable
            // or if WRITE_EXTERNAL_STORAGE was also granted (though technically only read is needed for monitoring)
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }
}