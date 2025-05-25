package com.example.callrecorderuploader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock; // Used for recordingStartTimeMs (elapsedRealtime based)
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.callrecorderuploader.MainActivity;
import com.example.callrecorderuploader.R;
// FileUtils is not directly used here but might be relevant for consumers of its broadcasts
// import com.example.callrecorderuploader.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

public class RecordingService extends Service {
    private static final String TAG = "RecordingService";

    // Intent Extras from CallStateReceiver
    public static final String EXTRA_CALL_STATE = "extra_call_state";
    public static final String EXTRA_REMOTE_NUMBER = "extra_remote_number";
    public static final String EXTRA_OWN_SIM_IDENTIFIER = "extra_own_sim_identifier";
    public static final String EXTRA_IS_INCOMING = "extra_is_incoming";
    public static final String EXTRA_ACTUAL_CALL_START_TIME_MS = "extra_actual_call_start_time_ms"; // New

    // Action and Extras for MainActivity
    public static final String ACTION_APP_RECORDING_COMPLETED = "com.example.callrecorderuploader.APP_RECORDING_COMPLETED";
    public static final String EXTRA_FILE_PATH = "extra_file_path";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_RECORDING_START_TIME_MS = "extra_recording_start_time_ms"; // ElapsedRealtime based start

    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private String currentFilePath;
    private String currentRemoteNumber;
    private String currentOwnSimIdentifier;
    private boolean currentIsIncoming;
    private long recordingStartTimeMs; // Based on SystemClock.elapsedRealtime() when mediaRecorder.start() is called
    private long actualCallStartTimeMs; // Based on System.currentTimeMillis() from CallStateReceiver


    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final int NOTIFICATION_ID = 12345;
    public static boolean IS_SERVICE_RUNNING = false;

    private static final Pattern SanitizeFilenamePattern = Pattern.compile("[^a-zA-Z0-9_().-]");


    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Log.d(TAG, "Service Created");
        IS_SERVICE_RUNNING = true; // Set when service instance is created
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand received");


        // Must call startForeground immediately for services started with startForegroundService
        Notification notification = createNotification(getString(R.string.notification_recording_service_standby));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // foregroundServiceType requires API 29
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting foreground service: " + e.getMessage(), e);
            // Handle error, perhaps stopSelf if foregrounding is critical and failed
        }


        if (intent != null) {
            String callState = intent.getStringExtra(EXTRA_CALL_STATE);
            currentRemoteNumber = intent.getStringExtra(EXTRA_REMOTE_NUMBER);
            currentOwnSimIdentifier = intent.getStringExtra(EXTRA_OWN_SIM_IDENTIFIER);
            currentIsIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false);
            actualCallStartTimeMs = intent.getLongExtra(EXTRA_ACTUAL_CALL_START_TIME_MS, 0); // Retrieve this

            if (TextUtils.isEmpty(currentRemoteNumber)) currentRemoteNumber = "Unknown";
            // Use a string resource or a constant for "Local"
            if (TextUtils.isEmpty(currentOwnSimIdentifier)) currentOwnSimIdentifier = getString(R.string.default_local_identifier_fallback);


            Log.d(TAG, "Call state: " + callState + ", Remote: " + currentRemoteNumber +
                    ", OwnID: " + currentOwnSimIdentifier + ", Incoming: " + currentIsIncoming +
                    ", ActualCallStartMs: " + actualCallStartTimeMs);

            SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
            boolean appRecordingEnabled = prefs.getBoolean(MainActivity.KEY_APP_RECORDING_ENABLED, false); // Use key from MainActivity

            if (!appRecordingEnabled) {
                Log.i(TAG, "App internal recording is disabled by user setting.");
                if (isRecording) {
                    stopRecordingAndCleanup(true); // true for quiet stop
                }
                updateNotification(getString(R.string.notification_recording_service_standby_disabled));
                // If not recording and app recording is disabled, consider stopping the service
                // if it has no other purpose. For now, it just stays standby.
                // stopSelfIfNotRecording(); // A helper method to check if service should stop
                return START_STICKY;
            }

            if ("OFFHOOK".equals(callState)) {
                if (!isRecording) {
                    startRecording();
                } else {
                    Log.w(TAG, "Received OFFHOOK but already recording. Remote: " + currentRemoteNumber + " (New event's remote)");
                    // Update notification if remote number changed mid-call (e.g., call waiting switch)
                    updateNotification(getString(R.string.notification_text_recording, currentRemoteNumber));
                }
            } else if ("IDLE".equals(callState)) {
                if (isRecording) {
                    // Check if the IDLE state corresponds to the call we were recording
                    // This might need more sophisticated logic if multiple calls/conferences are possible
                    // For now, assume any IDLE while recording means stop current recording.
                    stopRecordingAndCleanup(false);
                } else {
                    Log.d(TAG, "Received IDLE but not currently recording.");
                }
                updateNotification(getString(R.string.notification_recording_service_standby));
                // stopSelfIfNotRecording(); // Consider stopping if idle and not recording
            }
        } else {
            Log.w(TAG, "Intent is null in onStartCommand (service possibly restarted by system?).");
            updateNotification(getString(R.string.notification_recording_service_standby));
        }
        return START_STICKY;
    }

    private String sanitizeNumberForFilename(String number) {
        if (TextUtils.isEmpty(number) || "Unknown".equalsIgnoreCase(number)) return "Unknown";
        return SanitizeFilenamePattern.matcher(number).replaceAll("");
    }

    // 在 RecordingService.java 中
// (确保相关的成员变量如 TAG, currentRemoteNumber, currentOwnSimIdentifier, currentFilePath,
// mediaRecorder, isRecording, recordingStartTimeMs, sanitizeNumberForFilename, getString, Toast,
// updateNotification, cleanupMediaRecorderOnError, cleanupMediaRecorder 等已正确定义和初始化)

    private void startRecording() {
        if (isRecording) {
            Log.w(TAG, "StartRecording called but already recording.");
            return;
        }

        // 确定应用专属录音目录 (兼容性处理)
        File storageDir;
        File baseAppDir = getExternalFilesDir(null); // API 8+
        String customRecordingsSubDirName = "Recordings"; // 自定义子目录名

        if (baseAppDir != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
                try {
                    storageDir = getExternalFilesDir(Environment.DIRECTORY_RECORDINGS);
                    if (storageDir == null) {
                        Log.w(TAG, "getExternalFilesDir(Environment.DIRECTORY_RECORDINGS) returned null, using custom '" + customRecordingsSubDirName + "' subdir.");
                        storageDir = new File(baseAppDir, customRecordingsSubDirName);
                    }
                } catch (NoSuchFieldError e) {
                    Log.w(TAG, "Environment.DIRECTORY_RECORDINGS not found on this ROM (likely Android 10 variant), using custom '" + customRecordingsSubDirName + "' subdir.");
                    storageDir = new File(baseAppDir, customRecordingsSubDirName);
                }
            } else {
                // API < Q, DIRECTORY_RECORDINGS constant does not exist.
                storageDir = new File(baseAppDir, customRecordingsSubDirName);
            }

            // 确保目录存在
            if (!storageDir.exists()) {
                if (!storageDir.mkdirs()) {
                    Log.e(TAG, "Failed to create app-specific recordings directory: " + storageDir.getAbsolutePath());
                    Toast.makeText(this, R.string.toast_failed_to_access_create_recordings_directory, Toast.LENGTH_SHORT).show();
                    // Update notification to reflect error and don't proceed with recording
                    updateNotification(getString(R.string.notification_recording_service_standby_error));
                    return;
                }
            }
        } else {
            Log.e(TAG, "Failed to get base app-specific external files directory (baseAppDir is null). Cannot save recordings.");
            Toast.makeText(this, R.string.toast_failed_to_access_create_recordings_directory, Toast.LENGTH_SHORT).show();
            updateNotification(getString(R.string.notification_recording_service_standby_error));
            return;
        }

        // 再次检查 storageDir 是否有效 (mkdirs 可能失败但没有抛异常，或者 baseAppDir 为 null)
        if (storageDir == null || !storageDir.isDirectory()) {
            Log.e(TAG, "Final determined storageDir is invalid or not a directory. Cannot start recording.");
            Toast.makeText(this, R.string.toast_failed_to_access_create_recordings_directory, Toast.LENGTH_SHORT).show();
            updateNotification(getString(R.string.notification_recording_service_standby_error));
            return;
        }

        // 生成文件名
        String cleanRemoteNumber = sanitizeNumberForFilename(currentRemoteNumber);
        String cleanOwnId = sanitizeNumberForFilename(currentOwnSimIdentifier);
        String timeStampForFile = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(new Date());
        String baseFileName = cleanRemoteNumber + "(" + cleanOwnId + ")_" + timeStampForFile;
        String fullFileName = baseFileName + ".m4a"; // 输出为 M4A

        currentFilePath = new File(storageDir, fullFileName).getAbsolutePath();
        Log.d(TAG, "Attempting to record to file: " + currentFilePath);

        // 初始化 MediaRecorder
        // 在API 31+上，推荐使用 MediaRecorder(Context) 构造函数，但旧的构造函数仍然可用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                mediaRecorder = new MediaRecorder(this); // Use context-aware constructor for API 31+
            } catch (Exception e) {
                Log.w(TAG, "Failed to init MediaRecorder with context, falling back to default constructor.", e);
                mediaRecorder = new MediaRecorder(); // Fallback for safety, though not expected to fail often
            }
        } else {
            mediaRecorder = new MediaRecorder();
        }


        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            Log.d(TAG, "Audio source set to VOICE_COMMUNICATION.");
        } catch (Exception e) {
            Log.w(TAG, "VOICE_COMMUNICATION failed (" + e.getMessage() + "), trying MIC as fallback.");
            try {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                Log.d(TAG, "Audio source set to MIC.");
            } catch (Exception e2) {
                Log.e(TAG, "Failed to set any audio source: " + e2.getMessage(), e2);
                Toast.makeText(this, getString(R.string.toast_failed_to_set_audio_source, e2.getMessage()), Toast.LENGTH_LONG).show();
                cleanupMediaRecorder(); // Release recorder if source fails
                updateNotification(getString(R.string.notification_recording_service_standby_error));
                return;
            }
        }

        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(128000);
        mediaRecorder.setAudioSamplingRate(44100);
        mediaRecorder.setOutputFile(currentFilePath);

        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            recordingStartTimeMs = SystemClock.elapsedRealtime();
            Log.i(TAG, "Recording started: " + fullFileName + " (Remote: " + currentRemoteNumber + ")");
            Toast.makeText(this, getString(R.string.toast_recording_started, fullFileName), Toast.LENGTH_SHORT).show();
            updateNotification(getString(R.string.notification_text_recording, currentRemoteNumber));
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "MediaRecorder prepare/start failed: " + e.getMessage(), e);
            Toast.makeText(this, getString(R.string.toast_recording_start_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            cleanupMediaRecorderOnError();
            // Notification update is handled in cleanupMediaRecorderOnError
        }
    }

    private void stopRecordingAndCleanup(boolean quiet) {
        if (!isRecording || mediaRecorder == null) {
            if (!quiet) Log.w(TAG, "StopRecording called but not recording or mediaRecorder is null.");
            cleanupMediaRecorder(); // Ensure it's released
            updateNotification(getString(R.string.notification_recording_service_standby));
            return;
        }

        String filePathToProcess = currentFilePath; // Hold a copy before resetting instance variables
        String fileNameForNotification = (filePathToProcess != null) ? new File(filePathToProcess).getName() : "UnknownFile";

        try {
            mediaRecorder.stop();
            Log.i(TAG, "Recording stopped for: " + fileNameForNotification);
        } catch (RuntimeException e) {
            // stop() can throw RuntimeException if called in an invalid state (e.g., already stopped, not prepared)
            Log.e(TAG, "MediaRecorder.stop() failed, possibly already stopped or in error state: " + e.getMessage());
            // Continue to process the file if it exists and seems valid, as some data might have been written.
        } finally {
            isRecording = false; // Set before cleanup
            cleanupMediaRecorder(); // Always release the recorder

            File recordedFile = (filePathToProcess != null) ? new File(filePathToProcess) : null;
            if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 500) { // Check for minimal valid size (e.g., > 0.5KB)
                Log.i(TAG, "File saved: " + recordedFile.getAbsolutePath() + ", Size: " + recordedFile.length());
                if (!quiet) {
                    Toast.makeText(this, getString(R.string.toast_recording_saved, fileNameForNotification), Toast.LENGTH_LONG).show();
                }

                // Notify MainActivity about the completed app recording
                Intent intentToMain = new Intent(ACTION_APP_RECORDING_COMPLETED);
                intentToMain.putExtra(EXTRA_FILE_PATH, recordedFile.getAbsolutePath());
                intentToMain.putExtra(EXTRA_FILE_NAME, recordedFile.getName());
                intentToMain.putExtra(EXTRA_REMOTE_NUMBER, currentRemoteNumber);
                intentToMain.putExtra(EXTRA_OWN_SIM_IDENTIFIER, currentOwnSimIdentifier);
                intentToMain.putExtra(EXTRA_IS_INCOMING, currentIsIncoming);
                intentToMain.putExtra(EXTRA_RECORDING_START_TIME_MS, recordingStartTimeMs); // ElapsedRealtime based
                intentToMain.putExtra(EXTRA_ACTUAL_CALL_START_TIME_MS, actualCallStartTimeMs); // Wall clock time from CallStateReceiver
                LocalBroadcastManager.getInstance(this).sendBroadcast(intentToMain);
                Log.d(TAG, "Broadcast sent: APP_RECORDING_COMPLETED for " + recordedFile.getName() +
                        " with actualCallStartTimeMs: " + actualCallStartTimeMs);

            } else {
                Log.w(TAG, "Recorded file invalid or too small, not processing: " + filePathToProcess +
                        (recordedFile != null ? ", Exists: " + recordedFile.exists() + ", Size: " + recordedFile.length() : ", File obj is null"));
                if (!quiet) {
                    Toast.makeText(this, R.string.toast_recorded_file_invalid, Toast.LENGTH_SHORT).show();
                }
                if (recordedFile != null && recordedFile.exists()) { // Delete small/invalid app-generated file
                    if(recordedFile.delete()){
                        Log.d(TAG, "Deleted invalid/small recorded file: " + filePathToProcess);
                    } else {
                        Log.w(TAG, "Failed to delete invalid/small recorded file: " + filePathToProcess);
                    }
                }
            }

            // Reset instance variables for the next call
            currentFilePath = null;
            // currentRemoteNumber, currentOwnSimIdentifier, currentIsIncoming will be reset by next onStartCommand
            recordingStartTimeMs = 0;
            actualCallStartTimeMs = 0; // Reset this too
            // Update notification to standby only after processing, as broadcast might trigger UI changes
            updateNotification(getString(R.string.notification_recording_service_standby));
        }
    }

    private void cleanupMediaRecorderOnError() {
        Log.e(TAG, "Cleaning up MediaRecorder due to an error during start/prepare.");
        isRecording = false;
        cleanupMediaRecorder(); // Release
        if (currentFilePath != null) {
            File problematicFile = new File(currentFilePath);
            if (problematicFile.exists()) {
                if(problematicFile.delete()){
                    Log.d(TAG, "Deleted potentially corrupted file on error: " + currentFilePath);
                } else {
                    Log.w(TAG, "Failed to delete problematic file on error: " + currentFilePath);
                }
            }
            currentFilePath = null;
        }
        updateNotification(getString(R.string.notification_recording_service_standby_error));
    }


    private void cleanupMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                // Only call reset if not already released.
                // Calling reset on a released recorder can cause issues.
                // A simple null check isn't enough if it's in an error state.
                // Using a try-catch for reset and release individually.
                try {
                    mediaRecorder.reset();
                } catch (IllegalStateException ise) {
                    Log.w(TAG, "IllegalStateException during mediaRecorder.reset(): " + ise.getMessage());
                }
                mediaRecorder.release();
            } catch (Exception e) {
                Log.e(TAG, "Exception releasing MediaRecorder: " + e.getMessage());
            } finally {
                mediaRecorder = null;
                Log.d(TAG, "MediaRecorder cleaned up and released.");
            }
        }
    }

    private void stopSelfIfNotRecording() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        boolean appRecordingEnabled = prefs.getBoolean(MainActivity.KEY_APP_RECORDING_ENABLED, false);
        if (!isRecording && !appRecordingEnabled) {
            Log.i(TAG, "Not recording and app recording is disabled. Stopping service.");
            stopSelf();
        } else if(!isRecording) {
            Log.d(TAG, "Not recording, but app recording is enabled. Service will remain standby.");
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service Destroyed");
        if (isRecording) {
            Log.w(TAG, "Service destroyed while recording. Attempting to save recording (quietly).");
            stopRecordingAndCleanup(true); // Attempt to finalize recording if destroyed unexpectedly
        } else {
            cleanupMediaRecorder(); // Ensure resources are released
        }
        IS_SERVICE_RUNNING = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping foreground in onDestroy: " + e.getMessage());
        }
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.recording_service_channel_name), // From strings.xml
                    NotificationManager.IMPORTANCE_LOW); // Low importance for less intrusive notification
            serviceChannel.setDescription(getString(R.string.recording_service_channel_description)); // From strings.xml
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d(TAG, "Notification channel created: " + CHANNEL_ID);
            } else {
                Log.e(TAG, "NotificationManager is null, cannot create channel.");
            }
        }
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK); // Or FLAG_ACTIVITY_SINGLE_TOP
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title_recording_service)) // From strings.xml
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Makes the notification persistent
                .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            try {
                notificationManager.notify(NOTIFICATION_ID, createNotification(text));
            } catch (Exception e) {
                Log.e(TAG, "Error updating notification: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "NotificationManager is null, cannot update notification.");
        }
    }
}