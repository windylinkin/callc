package com.example.callrecorderuploader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences; // To check if service should run based on settings
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.callrecorderuploader.MainActivity; // For PREFS_NAME and settings keys
import com.example.callrecorderuploader.R;
import com.example.callrecorderuploader.utils.PermissionUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class MiUiCallRecordMonitorService extends Service {
    private static final String TAG = "MiUiCallRecMonitor";

    // Primary and known alternative MIUI recording folder paths
    // Order matters: more common/newer paths should ideally be first.
    private static final String[] MIUI_RECORDING_FOLDER_PATHS = {
            Environment.getExternalStorageDirectory().getPath() + "/MIUI/sound_recorder/call_rec/",
            Environment.getExternalStorageDirectory().getPath() + "/Recordings/call_rec/", // Some newer Xiaomi/Poco
            Environment.getExternalStorageDirectory().getPath() + "/CallRecordings/", // Generic path some systems might use
            Environment.getExternalStorageDirectory().getPath() + "/sound_recorder/call_rec/", // Older variant
            Environment.getExternalStorageDirectory().getPath() + "/MIUI/recorder/call/", // Another older variant
            Environment.getExternalStorageDirectory().getPath() + "/Recorder/call_rec/", // Seen on some devices

            // Vivo paths
            Environment.getExternalStorageDirectory().getPath() + "/record/call/", // New Vivo system
            Environment.getExternalStorageDirectory().getPath() + "/Record/Call/", // Old Vivo system
            Environment.getExternalStorageDirectory().getPath() + "/CallRecord/", // Old Vivo system

            // Huawei paths
            Environment.getExternalStorageDirectory().getPath() + "/Sounds/CallRecord/", // New Huawei system
            Environment.getExternalStorageDirectory().getPath() + "/Recorder/callrecord/" // Old Huawei system
            // Add more known paths here if discovered
    };
    public static final String ACTION_NEW_MIUI_RECORDING = "com.example.callrecorderuploader.NEW_MIUI_RECORDING"; // Ensure this matches MainActivity
    public static final String EXTRA_FILE_PATH = "filePath"; // Ensure this matches MainActivity
    public static final String EXTRA_FILE_NAME = "fileName"; // Ensure this matches MainActivity


    private List<FileObserver> fileObservers = new ArrayList<>(); // Store multiple observers if monitoring multiple paths
    private static final String CHANNEL_ID = "MiUiCallRecordMonitorChannel";
    private static final int NOTIFICATION_ID = 78901;
    private static boolean isServiceRunning = false;
    private Handler mainHandler;
    private String activeMonitoringPath = null; // Path currently being monitored


    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service Created.");
        // Monitoring will be started in onStartCommand after checking settings
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand received");

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        boolean systemMonitoringEnabled = prefs.getBoolean(MainActivity.KEY_SYSTEM_MONITORING_ENABLED, true);

        if (!systemMonitoringEnabled) {
            Log.i(TAG, "System monitoring is disabled in settings. Stopping service.");
            isServiceRunning = false; // Mark as not running
            stopSelf(); // Stop the service
            return START_NOT_STICKY; // Don't restart if killed
        }

        Notification notification = createNotification(getString(R.string.miui_monitor_notification_text_monitoring)); // "小米通话录音监控中..."
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isServiceRunning = true; // Mark as running after successfully starting foreground
            Log.d(TAG, "Service started in foreground.");
        } catch (Exception e) {
            Log.e(TAG, "Error starting foreground service for MiUiCallRecordMonitorService: " + e.getMessage(), e);
            isServiceRunning = false; // Failed to start properly
            stopSelf();
            return START_NOT_STICKY;
        }


        if (fileObservers.isEmpty()) { // Start monitoring only if not already started
            startMonitoring();
        } else {
            Log.d(TAG, "FileObserver already active for path: " + activeMonitoringPath);
        }
        return START_STICKY; // Keep service running
    }

    private void startMonitoring() {
        stopExistingObservers(); // Stop any previous observers before starting new ones

        if (!PermissionUtils.canAccessMiUiRecordingFolder(this)) { // This checks general storage permissions
            Log.e(TAG, "Cannot start monitoring: Insufficient permissions for external storage access.");
            mainHandler.post(() -> Toast.makeText(MiUiCallRecordMonitorService.this, R.string.miui_monitor_toast_permission_denied, Toast.LENGTH_LONG).show());
            updateNotification(getString(R.string.miui_monitor_notification_text_permission_error));
            // Don't stopSelf() here, let it remain in foreground with error state.
            // User might grant permission later.
            return;
        }

        File recordingDirToMonitor = null;
        for (String path : MIUI_RECORDING_FOLDER_PATHS) {
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                // Additional check: Can we list files? (More robust permission check)
                try {
                    if (dir.canRead() && dir.listFiles() != null) { // Check if listFiles() doesn't throw SecurityException or return null
                        recordingDirToMonitor = dir;
                        activeMonitoringPath = path;
                        Log.i(TAG, "Found accessible MIUI recording directory to monitor: " + activeMonitoringPath);
                        break;
                    } else {
                        Log.w(TAG, "Directory exists but cannot list files (permission issue or empty): " + path);
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "SecurityException while trying to list files for path: " + path, se);
                }
            } else {
                Log.d(TAG, "Path does not exist or is not a directory: " + path);
            }
        }

        if (recordingDirToMonitor == null) {
            Log.e(TAG, "No accessible MIUI Recording directory found from known paths.");
            mainHandler.post(() -> Toast.makeText(MiUiCallRecordMonitorService.this, R.string.miui_monitor_toast_folder_not_found, Toast.LENGTH_LONG).show());
            updateNotification(getString(R.string.miui_monitor_notification_text_folder_error));
            activeMonitoringPath = null;
            return;
        }

        final String pathToObserve = recordingDirToMonitor.getAbsolutePath();
        final int eventMask = FileObserver.CLOSE_WRITE | FileObserver.CREATE;
        // For some systems, MOVED_TO might also be relevant if files are written to a temp location then moved.
        // final int eventMask = FileObserver.CLOSE_WRITE | FileObserver.CREATE | FileObserver.MOVED_TO;


        try {
            FileObserver observer = new FileObserver(pathToObserve, eventMask) {
                @Override
                public void onEvent(int event, @Nullable String path) {
                    if (path == null) {
                        Log.d(TAG, "FileObserver event with null path for " + pathToObserve);
                        return;
                    }

                    // Construct full path correctly
                    // On some devices, 'path' might already be absolute, on others relative.
                    // FileObserver documentation states 'path' is relative to the monitored directory.
                    File newFile = new File(pathToObserve, path);
                    String fullPath = newFile.getAbsolutePath();


                    // Filter for relevant file types (e.g., mp3, m4a, amr)
                    // Ensure the file actually exists and is a file (not a temp directory)
                    if (!newFile.isFile() || !(path.toLowerCase().endsWith(".mp3") ||
                            path.toLowerCase().endsWith(".m4a") ||
                            path.toLowerCase().endsWith(".amr") ||
                            path.toLowerCase().endsWith(".aac"))) { // Add more if needed
                        // Log.d(TAG, "Ignoring event for non-target file type or non-file: " + path);
                        return;
                    }
                    // Check file size as well, very small files are likely invalid or temporary
                    if (newFile.length() < 512) { // e.g., less than 0.5KB
                        Log.d(TAG, "Ignoring event for very small file (likely temp/invalid): " + path + ", size: " + newFile.length());
                        return;
                    }


                    // CLOSE_WRITE is generally the most reliable event for when a file is finished being written.
                    // CREATE might fire too early.
                    if ((event & FileObserver.CLOSE_WRITE) != 0) {
                        Log.i(TAG, "New recording detected (CLOSE_WRITE): " + path + " at " + fullPath);
                        handleNewRecording(fullPath, path);
                    } else if ((event & FileObserver.CREATE) != 0) {
                        Log.d(TAG, "File created (CREATE): " + path + ". Waiting for CLOSE_WRITE.");
                        // Typically, you'd wait for CLOSE_WRITE. Processing on CREATE might get incomplete files.
                        // However, if CLOSE_WRITE is unreliable on some devices for this path, you might need a delay mechanism after CREATE.
                    } else if ((event & FileObserver.MOVED_TO) != 0) { // If you use MOVED_TO
                        Log.i(TAG, "New recording detected (MOVED_TO): " + path + " at " + fullPath);
                        handleNewRecording(fullPath, path);
                    }
                }
            };
            observer.startWatching();
            fileObservers.add(observer); // Add to list
            Log.i(TAG, "FileObserver started watching: " + pathToObserve);
            updateNotification(getString(R.string.miui_monitor_notification_text_monitoring_path, pathToObserve));

        } catch (Exception e) { // Catch broad Exception as FileObserver constructor can throw various things
            Log.e(TAG, "Error starting FileObserver for " + pathToObserve, e);
            mainHandler.post(() -> Toast.makeText(MiUiCallRecordMonitorService.this, R.string.miui_monitor_toast_observer_start_failed, Toast.LENGTH_LONG).show());
            updateNotification(getString(R.string.miui_monitor_notification_text_observer_error));
            activeMonitoringPath = null;
        }
    }

    private void handleNewRecording(String fullPath, String relativeOrFileName) {
        // Debounce or check if already processed recently if events fire multiple times for the same file
        // For now, assume MainActivity handles deeper duplicate checks.

        mainHandler.post(() -> Toast.makeText(MiUiCallRecordMonitorService.this, getString(R.string.miui_monitor_toast_new_recording_found, relativeOrFileName), Toast.LENGTH_SHORT).show());

        Intent intent = new Intent(ACTION_NEW_MIUI_RECORDING);
        intent.putExtra(EXTRA_FILE_PATH, fullPath);
        intent.putExtra(EXTRA_FILE_NAME, new File(fullPath).getName()); // Send just the filename
        LocalBroadcastManager.getInstance(MiUiCallRecordMonitorService.this).sendBroadcast(intent);
        Log.d(TAG, "Broadcast sent: " + ACTION_NEW_MIUI_RECORDING + " for " + fullPath);
    }


    private void stopExistingObservers() {
        if (!fileObservers.isEmpty()) {
            for (FileObserver observer : fileObservers) {
                try {
                    observer.stopWatching();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping an existing FileObserver: " + e.getMessage());
                }
            }
            fileObservers.clear();
            Log.i(TAG, "All existing FileObservers stopped.");
        }
        activeMonitoringPath = null;
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Use M for FLAG_IMMUTABLE as S is too high for older devices
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.miui_monitor_notification_title))
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher) // Use app's launcher icon
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        if (!isServiceRunning && !text.toLowerCase().contains("error") && !text.toLowerCase().contains("disabled")) {
            // If service is stopping or disabled, don't update notification to "monitoring"
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.miui_monitor_channel_name), // From strings.xml
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription(getString(R.string.miui_monitor_channel_description)); // From strings.xml
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopExistingObservers();
        isServiceRunning = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping foreground in onDestroy: " + e.getMessage());
        }
        Log.d(TAG, "Service Destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isRunning() {
        return isServiceRunning;
    }
}