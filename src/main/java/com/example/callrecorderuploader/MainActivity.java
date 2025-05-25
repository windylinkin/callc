package com.example.callrecorderuploader;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.callrecorderuploader.model.RecordingEntry;
import com.example.callrecorderuploader.model.ServerResponse;
import com.example.callrecorderuploader.service.AppWebSocketClientService;
import com.example.callrecorderuploader.service.CallStateReceiver; // 确保导入 CallStateReceiver
import com.example.callrecorderuploader.service.MiUiCallRecordMonitorService;
import com.example.callrecorderuploader.service.RecordingService;
import com.example.callrecorderuploader.ui.RecordingLogAdapter;
import com.example.callrecorderuploader.utils.FileUtils;
import com.example.callrecorderuploader.utils.PermissionUtils;
import com.example.callrecorderuploader.worker.UploadWorker;
import com.google.gson.Gson;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements RecordingLogAdapter.OnManualUploadClickListener {

    private static final String TAG = "MainActivity";
    // Permission and request codes
    private static final int PERMISSIONS_REQUEST_CODE_BASE = 100;
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 101;
    private static final int REQUEST_CODE_PICK_AUDIO = 102;
    private static final int MANAGE_STORAGE_PERMISSION_REQUEST_CODE = 103;

    // SharedPreferences keys
    public static final String PREFS_NAME = "AppPrefs";
    public static final String KEY_PHONE_NUMBER_1 = "localPhoneNumber1";
    public static final String KEY_PHONE_NUMBER_2 = "localPhoneNumber2";
    public static final String KEY_APP_RECORDING_ENABLED = "appRecordingEnabled";
    public static final String KEY_SYSTEM_MONITORING_ENABLED = "systemMonitoringEnabled";
    public static final String KEY_PREFER_SYSTEM_RECORDING = "preferSystemRecording";
    public static final String KEY_LAST_CALLED_MIDDLE_NUMBER_INFO = "lastCalledMiddleNumberInfo";

    // UI Elements
    private TextView tvStatus, tvPermissionStatus, tvWebSocketStatus, tvAutoUploadServiceStatus;
    private Button btnGrantOverlayPermission, btnGrantStoragePermission, btnSelectAndUpload, btnSaveSettings, btnConnectWs;
    private EditText etLocalPhoneNumber1, etLocalPhoneNumber2;
    private SwitchCompat switchAppRecording, switchSystemMonitoring, switchPreferSystem;

    private RecyclerView rvRecordingLog;
    private RecordingLogAdapter recordingLogAdapter;
    private final List<RecordingEntry> recordingEntriesList = new ArrayList<>();
    private SharedPreferences sharedPreferences;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // Constants for recording logic
    private static final long UPLOADED_RECORD_DISPLAY_DURATION_MS = 30 * 60 * 1000;
    private static final long DUPLICATE_CALL_THRESHOLD_MS = 25 * 1000; // For comparing recording timestamps
    private static final long RECENT_CALL_CACHE_DURATION_MS = 5 * 60 * 1000; // For recent call detection
    private static final long UPLOAD_DELAY_AFTER_CALL_END_MS = 2 * 60 * 1000; // 2 minutes delay, adjustable (e.g., 2 * 60 * 1000 for 2 mins)

    private static final Pattern FILENAME_PATTERN = Pattern.compile(
            "([\\d+]+(?:\\([\\d+]+\\))?)[(]?([\\d+]*)?[)]?_(\\d{14})\\.(m4a|mp3|amr)", Pattern.CASE_INSENSITIVE);

    // For managing deferred uploads
    private static class PendingUploadInfo {
        String filePath;
        String fileName;
        ParsedRecordingInfo parsedInfo;
        String remoteNumber; // From call context
        long callApproxStartTimeMs; // From call context

        PendingUploadInfo(String filePath, String fileName, ParsedRecordingInfo parsedInfo, String remoteNumber, long callApproxStartTimeMs) {
            this.filePath = filePath;
            this.fileName = fileName;
            this.parsedInfo = parsedInfo;
            this.remoteNumber = remoteNumber;
            this.callApproxStartTimeMs = callApproxStartTimeMs;
        }
        @Override public String toString() { return "PendingUpload: " + fileName + " for call with " + remoteNumber + " starting around " + callApproxStartTimeMs; }
    }
    private PendingUploadInfo recordingAwaitingCallEnd = null;
    private String activeCallRemoteNumber = null;
    private long activeCallApproxStartTimeMs = 0;


    private static class RecentCallInfo {
        String remoteNumber;
        String ownNumber;
        long approxTimestamp;
        String sourceFilePath;
        boolean isSystemRecording;
        long discoveryTimeMs;

        RecentCallInfo(String remote, String own, long time, String path, boolean isSystem) {
            this.remoteNumber = FileUtils.normalizePhoneNumber(remote);
            this.ownNumber = FileUtils.normalizePhoneNumber(own);
            this.approxTimestamp = time;
            this.sourceFilePath = path;
            this.isSystemRecording = isSystem;
            this.discoveryTimeMs = SystemClock.elapsedRealtime();
        }
        @Override public String toString() { return (isSystemRecording ? "SysRec: " : "AppRec: ") + remoteNumber + "(" + ownNumber + ") @ " + approxTimestamp; }
    }
    private final List<RecentCallInfo> recentCallsCache = new ArrayList<>();


    private final BroadcastReceiver newRecordingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Received broadcast from recording source: " + action);

            String filePath = null;
            String fileName = null;
            String remoteNumFromRecContext = null; // From recording context
            String ownNumFromRecContext = null;
            long actualCallStartTimeForRecording = 0; // From RecordingService or derived for system
            boolean isSystemRecording = false;

            if (RecordingService.ACTION_APP_RECORDING_COMPLETED.equals(action)) {
                filePath = intent.getStringExtra(RecordingService.EXTRA_FILE_PATH);
                fileName = intent.getStringExtra(RecordingService.EXTRA_FILE_NAME);
                remoteNumFromRecContext = intent.getStringExtra(RecordingService.EXTRA_REMOTE_NUMBER);
                ownNumFromRecContext = intent.getStringExtra(RecordingService.EXTRA_OWN_SIM_IDENTIFIER);
                actualCallStartTimeForRecording = intent.getLongExtra(RecordingService.EXTRA_ACTUAL_CALL_START_TIME_MS, 0);
                isSystemRecording = false;
                Log.d(TAG, "APP_RECORDING_COMPLETED: " + fileName + ", Remote: " + remoteNumFromRecContext + ", ActualCallStart: " + actualCallStartTimeForRecording);

            } else if ("com.example.callrecorderuploader.NEW_MIUI_RECORDING".equals(action)) {
                filePath = intent.getStringExtra("filePath");
                fileName = intent.getStringExtra("fileName");
                isSystemRecording = true;
                ParsedRecordingInfo tempParsed = parseRecordingInfoFromFilename(fileName, filePath);
                remoteNumFromRecContext = tempParsed.remoteNumber; // Get remote number from filename
                ownNumFromRecContext = tempParsed.ownNumber;
                actualCallStartTimeForRecording = tempParsed.timestampMillis; // Use file's embedded timestamp
                Log.d(TAG, "NEW_MIUI_RECORDING: " + fileName + ", Remote from parse: " + remoteNumFromRecContext + ", FileTimestamp: " + actualCallStartTimeForRecording);
            }

            if (filePath != null && fileName != null) {
                File recFile = new File(filePath);
                if (!recFile.exists() || recFile.length() < 1024) { // Minimal check for valid file
                    Log.w(TAG, "Skipping invalid/small recording file: " + filePath);
                    if (recFile.exists() && !isSystemRecording) { // Delete small app-generated files
                        recFile.delete();
                    }
                    return;
                }

                ParsedRecordingInfo parsedInfo = parseRecordingInfoFromFilename(fileName, filePath);
                if (parsedInfo == null || "Unknown".equals(parsedInfo.remoteNumber)) {
                    // Fallback if parsing fails or yields generic info
                    parsedInfo = new ParsedRecordingInfo(
                            remoteNumFromRecContext != null ? remoteNumFromRecContext : "Unknown",
                            ownNumFromRecContext != null ? ownNumFromRecContext : sharedPreferences.getString(KEY_PHONE_NUMBER_1, "Local"),
                            actualCallStartTimeForRecording > 0 ? actualCallStartTimeForRecording : recFile.lastModified()
                    );
                }

                // Check if this recording belongs to the currently active call
                boolean matchesActiveCall = false;
                if (activeCallRemoteNumber != null && activeCallApproxStartTimeMs > 0 &&
                        parsedInfo.remoteNumber != null && !parsedInfo.remoteNumber.equals("Unknown")) {
                    // Normalize numbers for comparison
                    String normalizedParsedRemote = FileUtils.normalizePhoneNumber(parsedInfo.remoteNumber);
                    String normalizedActiveRemote = FileUtils.normalizePhoneNumber(activeCallRemoteNumber);

                    // Compare remote numbers and timestamp (e.g., within 1 minute of call start)
                    // The recording's timestamp (parsedInfo.timestampMillis) should be close to activeCallApproxStartTimeMs
                    if (TextUtils.equals(normalizedParsedRemote, normalizedActiveRemote) &&
                            Math.abs(parsedInfo.timestampMillis - activeCallApproxStartTimeMs) < TimeUnit.MINUTES.toMillis(1)) {
                        matchesActiveCall = true;
                    } else {
                        Log.d(TAG, "Recording " + fileName + " timestamp " + parsedInfo.timestampMillis + " or remote " + normalizedParsedRemote +
                                " does not closely match active call (Remote: " + normalizedActiveRemote + ", Start: " + activeCallApproxStartTimeMs + ")");
                    }
                } else {
                    Log.d(TAG, "No active call info to match recording " + fileName + " against, or parsed remote is Unknown.");
                }


                if (matchesActiveCall) {
                    Log.i(TAG, "Recording " + fileName + " matches active call. Deferring upload.");
                    // Store for deferred upload after call ends
                    recordingAwaitingCallEnd = new PendingUploadInfo(filePath, fileName, parsedInfo, activeCallRemoteNumber, activeCallApproxStartTimeMs);
                    addOrUpdateRecordingEntryInList(filePath, fileName, getString(R.string.status_waiting_call_end), null, true);
                } else {
                    Log.d(TAG, "Recording " + fileName + " does not match active call. Processing with handleDiscoveredRecording (non-deferred).");
                    handleDiscoveredRecording(filePath, fileName, parsedInfo, isSystemRecording, false); // false: not part of active call's deferred upload path
                }
            }
        }
    };


    private final BroadcastReceiver callStartedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CallStateReceiver.ACTION_CALL_STARTED.equals(intent.getAction())) {
                activeCallRemoteNumber = intent.getStringExtra(CallStateReceiver.EXTRA_CALL_STARTED_REMOTE_NUMBER);
                activeCallApproxStartTimeMs = intent.getLongExtra(CallStateReceiver.EXTRA_CALL_STARTED_APPROX_START_TIME_MS, 0);
                Log.i(TAG, "Event: ACTION_CALL_STARTED. Remote=" + activeCallRemoteNumber + ", ApproxStart=" + activeCallApproxStartTimeMs);
                recordingAwaitingCallEnd = null; // Reset any pending upload from a previous call for this new call
            }
        }
    };

    private final BroadcastReceiver callEndedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CallStateReceiver.ACTION_CALL_ENDED.equals(intent.getAction())) {
                String endedRemoteNumber = intent.getStringExtra(CallStateReceiver.EXTRA_CALL_ENDED_REMOTE_NUMBER);
                long endedApproxStartTimeMs = intent.getLongExtra(CallStateReceiver.EXTRA_CALL_ENDED_APPROX_START_TIME_MS, 0);

                Log.i(TAG, "Event: ACTION_CALL_ENDED. EndedRemote: " + endedRemoteNumber + ", EndedApproxStart: " + endedApproxStartTimeMs);
                Log.d(TAG, "Current recordingAwaitingCallEnd: " + (recordingAwaitingCallEnd != null ? recordingAwaitingCallEnd.toString() : "null"));


                if (recordingAwaitingCallEnd != null) {
                    // Normalize numbers for comparison
                    String normalizedEndedRemote = FileUtils.normalizePhoneNumber(endedRemoteNumber);
                    String normalizedAwaitingRemote = FileUtils.normalizePhoneNumber(recordingAwaitingCallEnd.remoteNumber);

                    // Check if the ended call matches the one for which we are holding a recording
                    if (TextUtils.equals(normalizedEndedRemote, normalizedAwaitingRemote) &&
                            Math.abs(endedApproxStartTimeMs - recordingAwaitingCallEnd.callApproxStartTimeMs) < TimeUnit.SECONDS.toMillis(15)) { // Match within 15s tolerance

                        final PendingUploadInfo infoToUpload = recordingAwaitingCallEnd; // Final for lambda
                        Log.i(TAG, "Call ended which matches pending upload: " + infoToUpload.fileName + ". Scheduling upload in " + (UPLOAD_DELAY_AFTER_CALL_END_MS / 1000) + "s");

                        addOrUpdateRecordingEntryInList(infoToUpload.filePath, infoToUpload.fileName, getString(R.string.status_call_ended_waiting_delay), null, true);

                        uiHandler.postDelayed(() -> {
                            Log.i(TAG, "Delayed upload executing for: " + infoToUpload.fileName);
                            String phoneIdentifierForUpload = (infoToUpload.parsedInfo != null && !"Unknown".equals(infoToUpload.parsedInfo.remoteNumber))
                                    ? infoToUpload.parsedInfo.remoteNumber
                                    : infoToUpload.remoteNumber;
                            if (TextUtils.isEmpty(phoneIdentifierForUpload) || "Unknown".equalsIgnoreCase(phoneIdentifierForUpload)) {
                                phoneIdentifierForUpload = "DelayedUpload"; // Generic identifier
                            }
                            enqueueUploadRequest(infoToUpload.filePath, phoneIdentifierForUpload, false, infoToUpload.fileName);
                        }, UPLOAD_DELAY_AFTER_CALL_END_MS);

                        recordingAwaitingCallEnd = null; // Clear after scheduling
                    } else {
                        Log.w(TAG, "Call ended event (Remote: " + normalizedEndedRemote + ", Start: " + endedApproxStartTimeMs +
                                ") does not precisely match the recording awaiting end (Remote: " +
                                (recordingAwaitingCallEnd != null ? normalizedAwaitingRemote : "N/A") + ", Expected Start: " +
                                (recordingAwaitingCallEnd != null ? recordingAwaitingCallEnd.callApproxStartTimeMs : "N/A") + "). Not uploading it via this path.");
                        // Potentially, this recording might be picked up by checkAndProcessOldRecordings if it was missed.
                    }
                } else {
                    Log.d(TAG, "Call ended, but no specific recording was awaiting upload (recordingAwaitingCallEnd is null). This is normal if no recording was made or matched.");
                }
                // Reset active call markers as the call has ended
                activeCallRemoteNumber = null;
                activeCallApproxStartTimeMs = 0;
            }
        }
    };


    private final BroadcastReceiver webSocketStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AppWebSocketClientService.ACTION_WS_STATUS_UPDATE.equals(intent.getAction())) {
                String status = intent.getStringExtra(AppWebSocketClientService.EXTRA_WS_STATUS_MESSAGE);
                updateWebSocketStatus(status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity creating.");
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initViewsAndListeners();
        setupRecyclerView();
        updateButtonStates();
        observeUploads();

        IntentFilter recordingFilter = new IntentFilter();
        recordingFilter.addAction(RecordingService.ACTION_APP_RECORDING_COMPLETED);
        recordingFilter.addAction("com.example.callrecorderuploader.NEW_MIUI_RECORDING");
        LocalBroadcastManager.getInstance(this).registerReceiver(newRecordingReceiver, recordingFilter);

        LocalBroadcastManager.getInstance(this).registerReceiver(webSocketStatusReceiver,
                new IntentFilter(AppWebSocketClientService.ACTION_WS_STATUS_UPDATE));

        // Register receivers for call start and end
        LocalBroadcastManager.getInstance(this).registerReceiver(callStartedReceiver,
                new IntentFilter(CallStateReceiver.ACTION_CALL_STARTED));
        LocalBroadcastManager.getInstance(this).registerReceiver(callEndedReceiver,
                new IntentFilter(CallStateReceiver.ACTION_CALL_ENDED));


        Log.d(TAG, "onCreate: Finished.");
    }

    private void initViewsAndListeners() {
        tvStatus = findViewById(R.id.tvStatus);
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        btnGrantOverlayPermission = findViewById(R.id.btnGrantOverlayPermission);
        btnGrantStoragePermission = findViewById(R.id.btnGrantStoragePermission);
        btnSelectAndUpload = findViewById(R.id.btnSelectAndUpload);
        tvAutoUploadServiceStatus = findViewById(R.id.tvAutoUploadServiceStatus);
        rvRecordingLog = findViewById(R.id.rvRecordingLog);

        etLocalPhoneNumber1 = findViewById(R.id.etPhoneNumber1);
        etLocalPhoneNumber2 = findViewById(R.id.etPhoneNumber2);
        btnSaveSettings = findViewById(R.id.btnSaveSettings);
        btnConnectWs = findViewById(R.id.btnConnectWs);
        tvWebSocketStatus = findViewById(R.id.tvWebSocketStatus);

        switchAppRecording = findViewById(R.id.switchAppRecording);
        switchSystemMonitoring = findViewById(R.id.switchSystemMonitoring);
        switchPreferSystem = findViewById(R.id.switchPreferSystem);

        btnGrantOverlayPermission.setOnClickListener(v -> PermissionUtils.requestOverlayPermission(this, OVERLAY_PERMISSION_REQUEST_CODE));
        btnGrantStoragePermission.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PermissionUtils.requestManageAllFilesAccessPermission(this, MANAGE_STORAGE_PERMISSION_REQUEST_CODE);
            }
        });
        btnSelectAndUpload.setOnClickListener(v -> openAudioPicker());
        btnSaveSettings.setOnClickListener(v -> saveSettingsAndRestartServices());
        btnConnectWs.setOnClickListener(v -> connectWebSocketExplicitly());

        CompoundButton.OnCheckedChangeListener settingsChangeListener = (buttonView, isChecked) -> {
            // Settings are saved via the "Save Settings" button
        };
        switchAppRecording.setOnCheckedChangeListener(settingsChangeListener);
        switchSystemMonitoring.setOnCheckedChangeListener(settingsChangeListener);
        switchPreferSystem.setOnCheckedChangeListener(settingsChangeListener);
    }

    private void loadSettingsAndApply() {
        etLocalPhoneNumber1.setText(sharedPreferences.getString(KEY_PHONE_NUMBER_1, ""));
        etLocalPhoneNumber2.setText(sharedPreferences.getString(KEY_PHONE_NUMBER_2, ""));
        switchAppRecording.setChecked(sharedPreferences.getBoolean(KEY_APP_RECORDING_ENABLED, false));
        switchSystemMonitoring.setChecked(sharedPreferences.getBoolean(KEY_SYSTEM_MONITORING_ENABLED, true));
        switchPreferSystem.setChecked(sharedPreferences.getBoolean(KEY_PREFER_SYSTEM_RECORDING, true));
        Log.d(TAG, "Settings loaded: AppRec=" + switchAppRecording.isChecked() + ", SysMon=" + switchSystemMonitoring.isChecked() + ", PrefSys=" + switchPreferSystem.isChecked());
        autoStartServicesBasedOnSettings();
    }

    private void saveSettingsAndRestartServices() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_PHONE_NUMBER_1, etLocalPhoneNumber1.getText().toString().trim());
        editor.putString(KEY_PHONE_NUMBER_2, etLocalPhoneNumber2.getText().toString().trim());
        editor.putBoolean(KEY_APP_RECORDING_ENABLED, switchAppRecording.isChecked());
        editor.putBoolean(KEY_SYSTEM_MONITORING_ENABLED, switchSystemMonitoring.isChecked());
        editor.putBoolean(KEY_PREFER_SYSTEM_RECORDING, switchPreferSystem.isChecked());
        editor.apply();
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show();
        autoStartServicesBasedOnSettings();
        connectWebSocketExplicitly(); // Re-evaluate WebSocket connection based on new numbers
    }

    private void autoStartServicesBasedOnSettings() {
        boolean sysMonitoringEnabled = sharedPreferences.getBoolean(KEY_SYSTEM_MONITORING_ENABLED, true);
        if (sysMonitoringEnabled && PermissionUtils.hasAllRequiredPermissionsForMiUiMonitoring(this)) {
            Log.i(TAG, "Ensuring MiUiCallRecordMonitorService is running (based on settings).");
            Intent miuiServiceIntent = new Intent(this, MiUiCallRecordMonitorService.class);
            try { ContextCompat.startForegroundService(this, miuiServiceIntent); }
            catch (Exception e) { Log.e(TAG, "Failed to start MiUiCallRecordMonitorService", e); }
        } else if (!sysMonitoringEnabled) {
            Log.i(TAG, "System Monitoring disabled by settings, stopping MiUiCallRecordMonitorService.");
            stopService(new Intent(this, MiUiCallRecordMonitorService.class));
        }
    }

    private void connectWebSocketExplicitly() {
        String phone1 = sharedPreferences.getString(KEY_PHONE_NUMBER_1, "").trim();
        String phone2 = sharedPreferences.getString(KEY_PHONE_NUMBER_2, "").trim();

        if (TextUtils.isEmpty(phone1) && TextUtils.isEmpty(phone2)) {
            Log.i(TAG, "No local phone numbers configured for WebSocket. Not starting service.");
            updateWebSocketStatus(getString(R.string.status_websocket_no_numbers_configured));
            Intent stopWsIntent = new Intent(this, AppWebSocketClientService.class);
            stopWsIntent.setAction(AppWebSocketClientService.ACTION_DISCONNECT);
            try { ContextCompat.startForegroundService(this, stopWsIntent); }
            catch (Exception e) { Log.e(TAG, "Error trying to stop WebSocket service", e); }
            return;
        }

        Intent wsIntent = new Intent(this, AppWebSocketClientService.class);
        ArrayList<String> phoneNumbers = new ArrayList<>();
        if (!TextUtils.isEmpty(phone1)) phoneNumbers.add(phone1);
        if (!TextUtils.isEmpty(phone2)) phoneNumbers.add(phone2);

        wsIntent.putExtra(AppWebSocketClientService.EXTRA_PHONE_NUMBERS, phoneNumbers.toArray(new String[0]));
        wsIntent.setAction(AppWebSocketClientService.ACTION_CONNECT);
        try {
            ContextCompat.startForegroundService(this, wsIntent);
            Log.i(TAG, "Attempting to start/connect WebSocket service with numbers: " + phoneNumbers);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AppWebSocketClientService for connection.", e);
            updateWebSocketStatus(getString(R.string.status_websocket_start_failed));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Activity resuming.");
        checkAllPermissions();
        loadRecordingsAsync(); // Load existing recordings to UI
        checkAndProcessOldRecordings(); // Process older recordings that might have been missed
        loadSettingsAndApply(); // Load settings and start services if needed
        AppWebSocketClientService.setActivityRunning(true);
        updateWebSocketStatus(AppWebSocketClientService.getCurrentStatus()); // Get initial WS status
        Log.d(TAG, "onResume: Finished.");
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppWebSocketClientService.setActivityRunning(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(newRecordingReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(webSocketStatusReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(callStartedReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(callEndedReceiver);
        uiHandler.removeCallbacksAndMessages(null); // Important to prevent memory leaks from Handler
        Log.d(TAG, "onDestroy: Activity destroyed.");
    }

    private void setupRecyclerView() {
        rvRecordingLog.setLayoutManager(new LinearLayoutManager(this));
        recordingLogAdapter = new RecordingLogAdapter(this, new ArrayList<>(), this);
        rvRecordingLog.setAdapter(recordingLogAdapter);
    }

    // 在 MainActivity.java 中

// 确保这个数组在文件顶部已经定义，和 checkAndProcessOldRecordings 方法共用
// private static final String[] PATHS_TO_CHECK_FOR_OLD_RECORDINGS = { ... };

// ... 其他 MainActivity 代码 ...

// 在 MainActivity.java 中
// (确保相关的成员变量如 TAG, uiHandler, recordingEntriesList, sharedPreferences,
// PATHS_TO_CHECK_FOR_OLD_RECORDINGS, KEY_SYSTEM_MONITORING_ENABLED,
// parseRecordingInfoFromFilename, getString, filterAndSortRecordingList,
// recordingLogAdapter 等已正确定义和初始化)

    private void loadRecordingsAsync() {
        Log.d(TAG, "loadRecordingsAsync: Starting to load recordings for UI display from multiple paths.");

        new Thread(() -> {
            List<RecordingEntry> diskEntries = new ArrayList<>();
            Set<String> addedFilePathsForThisLoad = new HashSet<>();

            // 1. 加载应用内部录音 (App-specific directory)
            File appSpecificDirBase;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) { // getExternalFilesDir(type) is available from API 19
                appSpecificDirBase = getExternalFilesDir(null);
            } else {
                // Fallback for versions older than KitKat (API 19), though minSdk is likely higher.
                // This path construction is a common pattern for older APIs but less reliable.
                File externalStorage = Environment.getExternalStorageDirectory();
                if (externalStorage != null) {
                    String packageName = getPackageName();
                    if (packageName != null) {
                        appSpecificDirBase = new File(externalStorage.getAbsolutePath() + "/Android/data/" + packageName + "/files");
                    } else {
                        appSpecificDirBase = null; // Cannot construct path if package name is null
                    }
                } else {
                    appSpecificDirBase = null; // Cannot get external storage directory
                }
            }

            File appSpecificRecordingsDir = null;
            String customRecordingsSubDirName = "Recordings"; // 自定义子目录名

            if (appSpecificDirBase != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        // Attempt to use the standard directory constant
                        appSpecificRecordingsDir = getExternalFilesDir(Environment.DIRECTORY_RECORDINGS);
                        if (appSpecificRecordingsDir == null) {
                            Log.w(TAG, "getExternalFilesDir(Environment.DIRECTORY_RECORDINGS) returned null, falling back to custom subdir '" + customRecordingsSubDirName + "'.");
                            appSpecificRecordingsDir = new File(appSpecificDirBase, customRecordingsSubDirName);
                        }
                    } catch (NoSuchFieldError e) {
                        Log.w(TAG, "Environment.DIRECTORY_RECORDINGS not found on this ROM (likely Android 10 variant), falling back to custom subdirectory '" + customRecordingsSubDirName + "'.");
                        appSpecificRecordingsDir = new File(appSpecificDirBase, customRecordingsSubDirName);
                    }
                } else {
                    // For API levels below Q, DIRECTORY_RECORDINGS constant does not exist.
                    appSpecificRecordingsDir = new File(appSpecificDirBase, customRecordingsSubDirName);
                }

                // Ensure the determined directory exists
                if (appSpecificRecordingsDir != null && !appSpecificRecordingsDir.exists()) {
                    if (!appSpecificRecordingsDir.mkdirs()) {
                        Log.e(TAG, "Failed to create app-specific recordings directory: " + appSpecificRecordingsDir.getAbsolutePath());
                        appSpecificRecordingsDir = null; // Mark as null if creation failed
                    }
                }
            } else {
                Log.e(TAG, "Base app-specific external directory (appSpecificDirBase) is null. Cannot create recordings subdirectory.");
            }


            if (appSpecificRecordingsDir != null && appSpecificRecordingsDir.exists() && appSpecificRecordingsDir.isDirectory()) {
                Log.d(TAG, "loadRecordingsAsync: Scanning app-specific directory: " + appSpecificRecordingsDir.getAbsolutePath());
                File[] appFiles = appSpecificRecordingsDir.listFiles((dir, name) ->
                        name.toLowerCase().endsWith(".m4a") ||
                                name.toLowerCase().endsWith(".amr")
                );
                if (appFiles != null) {
                    for (File file : appFiles) {
                        if (file.isFile() && file.length() > 512) { // Basic file validity check
                            if (addedFilePathsForThisLoad.add(file.getAbsolutePath())) {
                                diskEntries.add(new RecordingEntry(
                                        file.getAbsolutePath(),
                                        file.getName(),
                                        file.lastModified(),
                                        getString(R.string.status_checking_status),
                                        null,
                                        0)
                                );
                            }
                        }
                    }
                    Log.d(TAG, "loadRecordingsAsync: Found " + (appFiles.length) + " potential files in app-specific dir: " + appSpecificRecordingsDir.getAbsolutePath());
                }
            } else {
                Log.w(TAG, "loadRecordingsAsync: App-specific recording directory not found, not accessible, or failed to create.");
            }

            // 2. 加载系统录音 (例如 MIUI 录音)，如果用户启用了系统监控并且有权限
            boolean sysMonEnabled = sharedPreferences.getBoolean(KEY_SYSTEM_MONITORING_ENABLED, true);
            if (sysMonEnabled && PermissionUtils.canAccessMiUiRecordingFolder(this)) { // canAccessMiUiRecordingFolder handles relevant storage permissions
                Log.d(TAG, "loadRecordingsAsync: System monitoring enabled, scanning known system paths.");
                for (String systemPathString : PATHS_TO_CHECK_FOR_OLD_RECORDINGS) { // Assumes PATHS_TO_CHECK_FOR_OLD_RECORDINGS is defined in MainActivity
                    File systemRecDir = new File(systemPathString);
                    if (systemRecDir.exists() && systemRecDir.isDirectory()) {
                        Log.d(TAG, "loadRecordingsAsync: Scanning system path: " + systemPathString);
                        File[] systemFiles = null;
                        try {
                            systemFiles = systemRecDir.listFiles((dir, name) ->
                                    name.toLowerCase().endsWith(".mp3") ||
                                            name.toLowerCase().endsWith(".m4a") ||
                                            name.toLowerCase().endsWith(".aac") ||
                                            name.toLowerCase().endsWith(".amr")
                            );
                        } catch (SecurityException se) {
                            Log.e(TAG, "loadRecordingsAsync: SecurityException while listing files in " + systemPathString, se);
                            continue; // Skip this path on security error
                        }

                        if (systemFiles != null) {
                            for (File file : systemFiles) {
                                if (file.isFile() && file.length() > 1024) { // Basic file validity check
                                    if (addedFilePathsForThisLoad.add(file.getAbsolutePath())) {
                                        ParsedRecordingInfo info = parseRecordingInfoFromFilename(file.getName(), file.getAbsolutePath());
                                        String initialStatus = (info != null && !"Unknown".equals(info.remoteNumber) && info.timestampMillis > 0)
                                                ? getString(R.string.status_not_yet_processed)
                                                : getString(R.string.status_filename_unparsed);
                                        diskEntries.add(new RecordingEntry(
                                                file.getAbsolutePath(),
                                                file.getName(),
                                                file.lastModified(),
                                                initialStatus,
                                                null,
                                                0)
                                        );
                                    }
                                }
                            }
                            Log.d(TAG, "loadRecordingsAsync: Found " + systemFiles.length + " potential files in " + systemPathString);
                        }
                    }
                }
            } else {
                if(!sysMonEnabled) Log.i(TAG, "loadRecordingsAsync: System monitoring is disabled. Skipping scan of system paths.");
                if(!PermissionUtils.canAccessMiUiRecordingFolder(this)) Log.w(TAG, "loadRecordingsAsync: Cannot access system recording folders (permissions missing). Skipping scan of system paths.");
            }

            // 3. 更新UI列表
            uiHandler.post(() -> {
                synchronized (recordingEntriesList) {
                    int newEntriesAddedToUi = 0;
                    for (RecordingEntry diskEntry : diskEntries) {
                        boolean foundInMemory = false;
                        for (int i = 0; i < recordingEntriesList.size(); i++) {
                            if (recordingEntriesList.get(i).getFilePath().equals(diskEntry.getFilePath())) {
                                foundInMemory = true;
                                break;
                            }
                        }
                        if (!foundInMemory) {
                            recordingEntriesList.add(diskEntry);
                            newEntriesAddedToUi++;
                        }
                    }

                    if (newEntriesAddedToUi > 0 || diskEntries.isEmpty() && !recordingEntriesList.isEmpty() && addedFilePathsForThisLoad.isEmpty()){
                        // Update if new entries added, or if disk scan found nothing but list previously had items (implies items might have been deleted externally)
                        // and addedFilePathsForThisLoad is empty means we didn't even find existing files again.
                        // A simple heuristic: if new entries were added, or if the size of items *found on disk this run* differs from list size
                        // (this condition "diskEntries.isEmpty() && !recordingEntriesList.isEmpty() && addedFilePathsForThisLoad.isEmpty()"
                        // might be too aggressive for just clearing UI if disk is temporarily unreadable, better to rely on explicit deletion signals)
                        // A safer condition to refresh: if new entries added, or if list becomes empty when it wasn't.
                        filterAndSortRecordingList();
                        recordingLogAdapter.updateData(new ArrayList<>(recordingEntriesList));
                        Log.d(TAG, "loadRecordingsAsync: UI updated. Total entries now: " + recordingEntriesList.size() + ". New disk entries added to UI: " + newEntriesAddedToUi);
                    } else if (recordingEntriesList.isEmpty() && diskEntries.isEmpty()) {
                        // If both are empty, ensure UI is cleared if it wasn't already
                        filterAndSortRecordingList(); // Will result in empty list
                        recordingLogAdapter.updateData(new ArrayList<>(recordingEntriesList));
                        Log.d(TAG, "loadRecordingsAsync: Both disk and memory lists are empty. UI reflects this.");
                    }
                    else {
                        Log.d(TAG, "loadRecordingsAsync: No new entries added to UI from disk scan, list likely unchanged or only internal status updates occurred.");
                    }
                }
            });
        }).start();
    }
    private void filterAndSortRecordingList() {
        synchronized (recordingEntriesList) {
            long currentTime = System.currentTimeMillis();
            recordingEntriesList.removeIf(entry ->
                    entry.getUploadStatus().startsWith(getString(R.string.status_upload_success_prefix)) &&
                            entry.getUploadSuccessTime() > 0 &&
                            (currentTime - entry.getUploadSuccessTime()) > UPLOADED_RECORD_DISPLAY_DURATION_MS
            );
            Collections.sort(recordingEntriesList, (e1, e2) -> Long.compare(e2.getCreationTimestamp(), e1.getCreationTimestamp()));
        }
    }

    private void observeUploads() {
        Log.d(TAG, "observeUploads: Setting up WorkManager LiveData observer.");
        WorkManager.getInstance(this).getWorkInfosByTagLiveData(UploadWorker.WORK_TAG_UPLOAD)
                .observe(this, workInfos -> {
                    if (workInfos == null) { // 添加null检查
                        Log.d(TAG, "observeUploads: workInfos is null, returning.");
                        updateAutoUploadServiceStatusText(false);
                        return;
                    }
                    if (workInfos.isEmpty()) {
                        Log.d(TAG, "observeUploads: workInfos is empty, no active or completed upload tasks with this tag.");
                        updateAutoUploadServiceStatusText(false);
                        // Potentially clear or update UI if all tasks are truly gone and list should reflect it.
                        // However, this observer fires on changes, so empty list means no tagged workers are in a non-finished state.
                        // Existing entries in recordingEntriesList might still be valid if they were processed earlier.
                        return;
                    }

                    boolean isAnyWorkRunningOrEnqueued = false;
                    boolean listChanged = false; // Flag to see if we need a full adapter update

                    synchronized (recordingEntriesList) {
                        Set<String> workIdsProcessedInThisBatch = new HashSet<>(); // Process each workId only once per batch from LiveData

                        for (WorkInfo workInfo : workInfos) {
                            if (!workIdsProcessedInThisBatch.add(workInfo.getId().toString())) {
                                continue; // Already processed this WorkInfo ID in the current LiveData emission
                            }

                            // Try to find the associated RecordingEntry
                            // KEY_FILE_PATH from outputData is the original input path/URI string to the worker
                            String originalPathFromOutput = workInfo.getOutputData().getString(UploadWorker.KEY_FILE_PATH);
                            String originalFileNameFromOutput = workInfo.getOutputData().getString(UploadWorker.KEY_ORIGINAL_FILE_NAME); // Worker now includes this in output

                            RecordingEntry associatedEntry = findOrCreateEntryForWorkInfo(workInfo, originalPathFromOutput, originalFileNameFromOutput);

                            if (associatedEntry == null) {
                                Log.w(TAG, "observeUploads: No associated RecordingEntry found for WorkInfo ID: " + workInfo.getId() +
                                        (originalPathFromOutput != null ? ", OriginalPath: " + originalPathFromOutput : "") +
                                        (originalFileNameFromOutput != null ? ", OriginalFileName: " + originalFileNameFromOutput : "") +
                                        ". State: " + workInfo.getState());
                                // Still check if this "orphan" work (maybe from a previous app session not fully loaded in UI yet) is active
                                if (workInfo.getState() == WorkInfo.State.RUNNING || workInfo.getState() == WorkInfo.State.ENQUEUED) {
                                    isAnyWorkRunningOrEnqueued = true;
                                }
                                continue;
                            }

                            boolean entryUpdated = updateEntryFromWorkInfo(associatedEntry, workInfo);
                            if (entryUpdated) {
                                listChanged = true;
                            }

                            if (workInfo.getState() == WorkInfo.State.RUNNING || workInfo.getState() == WorkInfo.State.ENQUEUED) {
                                isAnyWorkRunningOrEnqueued = true;
                            }
                        }

                        if (listChanged) {
                            filterAndSortRecordingList(); // Re-filter and sort after updates
                            recordingLogAdapter.updateData(new ArrayList<>(recordingEntriesList));
                            Log.d(TAG, "observeUploads: UI list updated due to changes in WorkInfo.");
                        }
                    } // end synchronized block
                    updateAutoUploadServiceStatusText(isAnyWorkRunningOrEnqueued);
                });
    }

    private RecordingEntry findOrCreateEntryForWorkInfo(WorkInfo workInfo, String originalPathFromOutput, String originalFileNameFromOutput) {
        String workId = workInfo.getId().toString();
        RecordingEntry entry = null;

        synchronized(recordingEntriesList) { // Synchronize access to recordingEntriesList
            // 1. Try to find by workId first
            for (RecordingEntry e : recordingEntriesList) {
                if (workId.equals(e.getWorkRequestId())) {
                    entry = e;
                    break;
                }
            }

            // 2. If not found by workId, try by originalPathFromOutput (if available)
            if (entry == null && originalPathFromOutput != null) {
                for (RecordingEntry e : recordingEntriesList) {
                    if (originalPathFromOutput.equals(e.getFilePath())) {
                        entry = e;
                        if (entry.getWorkRequestId() == null || !entry.getWorkRequestId().equals(workId)) {
                            entry.setWorkRequestId(workId); // Link this workId to existing entry
                        }
                        break;
                    }
                }
            }

            // 3. If still not found, and we have originalPath and originalFileName, try to create a new one
            if (entry == null && originalPathFromOutput != null && originalFileNameFromOutput != null) {
                // Double check if an entry with this path already exists (e.g., added by manual selection but workId not yet linked)
                boolean pathExistsInList = false;
                for (RecordingEntry e : recordingEntriesList) {
                    if (originalPathFromOutput.equals(e.getFilePath())) {
                        pathExistsInList = true; // Should have been caught by step 2, but defensive check
                        entry = e; // Use existing entry
                        if (entry.getWorkRequestId() == null || !entry.getWorkRequestId().equals(workId)) {
                            entry.setWorkRequestId(workId);
                        }
                        break;
                    }
                }

                if (!pathExistsInList) {
                    Log.d(TAG, "findOrCreateEntryForWorkInfo: Creating new RecordingEntry for WorkInfo. Path: " + originalPathFromOutput + ", FileName: " + originalFileNameFromOutput);
                    long creationTime;
                    if (originalPathFromOutput.startsWith("content://")) {
                        creationTime = System.currentTimeMillis(); // Cannot reliably get lastModified from content URI here
                    } else {
                        File f = new File(originalPathFromOutput);
                        creationTime = f.exists() ? f.lastModified() : System.currentTimeMillis();
                    }
                    entry = new RecordingEntry(originalPathFromOutput, originalFileNameFromOutput, creationTime,
                            getString(R.string.status_checking_status), workId, 0);
                    recordingEntriesList.add(0, entry); // Add to top, will be sorted later by filterAndSortRecordingList
                    // No need to call adapter update here, observeUploads will do it if listChanged is true
                }
            }
        } // end synchronized block

        if (entry == null) {
            Log.w(TAG, "findOrCreateEntryForWorkInfo: Could not find or create an entry for workId " + workId +
                    ", originalPath: " + originalPathFromOutput + ", originalFileName: " + originalFileNameFromOutput);
        }
        return entry;
    }


    // 修改 updateEntryFromWorkInfo 以返回一个 boolean 指示条目是否实际被更新
    private boolean updateEntryFromWorkInfo(RecordingEntry entry, WorkInfo workInfo) {
        WorkInfo.State state = workInfo.getState();
        String oldStatus = entry.getUploadStatus(); // Store old status to check for changes

        String statusMessage;
        String serverResponseMessage = null; // For the main upload's server message
        String rawJsonResponse = workInfo.getOutputData().getString(UploadWorker.KEY_OUTPUT_SERVER_RESPONSE);
        String errorMessageFromWorker = workInfo.getOutputData().getString(UploadWorker.KEY_OUTPUT_ERROR_MESSAGE);
        String skippedMessageFromWorker = workInfo.getOutputData().getString(UploadWorker.KEY_OUTPUT_UPLOAD_SKIPPED_MESSAGE);

        if (skippedMessageFromWorker != null && !skippedMessageFromWorker.isEmpty()) {
            // Handle skipped upload due to pre-check
            statusMessage = getString(R.string.status_upload_skipped_by_server_display, entry.getFileName(), skippedMessageFromWorker);
            entry.setUploadSuccessTime(0); // Not a successful data upload
            entry.setServerResponseMessage(skippedMessageFromWorker); // Store the skip reason as server response
            Log.i(TAG, "Updating entry " + entry.getFileName() + " to skipped: " + statusMessage);
        } else {
            // Handle regular upload states
            switch (state) {
                case ENQUEUED:
                    statusMessage = getString(R.string.status_queued_auto); // More specific than just "排队中"
                    break;
                case RUNNING:
                    statusMessage = getString(R.string.status_uploading);
                    break;
                case SUCCEEDED:
                    ServerResponse parsedResponse = parseServerResponse(rawJsonResponse);
                    if (parsedResponse != null) {
                        if (parsedResponse.getCode() == 200 && parsedResponse.getData() != null && parsedResponse.getData().getCode() == 200) {
                            statusMessage = getString(R.string.status_upload_success_prefix) + safeGetMessage(parsedResponse);
                            entry.setUploadSuccessTime(System.currentTimeMillis());
                            serverResponseMessage = safeGetMessage(parsedResponse);
                        } else {
                            // Server returned success HTTP code, but business logic indicates failure
                            String serverLogicError = getString(R.string.status_upload_failed_server_logic) + safeGetMessage(parsedResponse);
                            if (parsedResponse.getMessage() != null && !parsedResponse.getMessage().equals(safeGetMessage(parsedResponse))) {
                                serverLogicError += " (Outer: " + parsedResponse.getMessage() + ")";
                            }
                            statusMessage = serverLogicError;
                            entry.setUploadSuccessTime(0);
                            serverResponseMessage = safeGetMessage(parsedResponse);
                        }
                    } else {
                        // WorkManager reported SUCCEEDED, but response parsing failed or was null
                        statusMessage = getString(R.string.status_upload_success_generic_error_parsing_response);
                        if (rawJsonResponse != null) {
                            statusMessage += " (Raw: " + rawJsonResponse.substring(0, Math.min(rawJsonResponse.length(), 30)) + "...)";
                        }
                        entry.setUploadSuccessTime(System.currentTimeMillis()); // Assume data might have reached server
                    }
                    break;
                case FAILED:
                    statusMessage = getString(R.string.status_upload_failed_generic);
                    if (errorMessageFromWorker != null) {
                        statusMessage += ": " + errorMessageFromWorker;
                    } else if (rawJsonResponse != null) { // Fallback to raw response if no specific error message
                        statusMessage += " (Server: " + rawJsonResponse.substring(0, Math.min(rawJsonResponse.length(), 50)) + "...)";
                    }
                    entry.setUploadSuccessTime(0);
                    serverResponseMessage = errorMessageFromWorker; // Store error as server message
                    break;
                case CANCELLED:
                    statusMessage = getString(R.string.status_cancelled);
                    entry.setUploadSuccessTime(0);
                    break;
                case BLOCKED:
                    statusMessage = getString(R.string.status_blocked); // e.g. waiting for constraints
                    entry.setUploadSuccessTime(0);
                    break;
                default:
                    statusMessage = state.name(); // Should not happen often
                    entry.setUploadSuccessTime(0);
                    break;
            }
            if (serverResponseMessage != null) {
                entry.setServerResponseMessage(serverResponseMessage);
            }
        }

        boolean statusChanged = !TextUtils.equals(oldStatus, statusMessage);
        if (statusChanged) {
            entry.setUploadStatus(statusMessage);
        }

        // Update file path if it was processed (e.g., temp file from URI was used for upload)
        // KEY_OUTPUT_FILE_PATH_PROCESSED contains the path of the file actually uploaded (e.g. temp cache file)
        // KEY_FILE_PATH contains the original input path/URI that was given to the worker
        String processedPathFromOutput = workInfo.getOutputData().getString(UploadWorker.KEY_OUTPUT_FILE_PATH_PROCESSED);
        String originalInputPath = workInfo.getOutputData().getString(UploadWorker.KEY_FILE_PATH);

        // If the entry's current path is the original input (e.g. content URI)
        // and the worker reports a different processed path (e.g. a temp file path),
        // update the entry to reflect the path that was actually used/checked, if it's a file system path.
        if (processedPathFromOutput != null &&
                !processedPathFromOutput.equals(entry.getFilePath()) &&
                originalInputPath != null && originalInputPath.equals(entry.getFilePath()) && // Ensure we're updating the correct entry
                !processedPathFromOutput.startsWith("content://")) { // Only update if processedPath is a file path

            Log.d(TAG, "Updating file path for entry '" + entry.getFileName() + "' from original '" + entry.getFilePath() + "' to processed '" + processedPathFromOutput + "'");
            File f = new File(processedPathFromOutput);
            if (f.exists() && f.isFile()) { // Ensure the processed path is a valid existing file
                entry.setFilePath(processedPathFromOutput);
                entry.setFileName(f.getName()); // Update filename from the processed file
                entry.setCreationTimestamp(f.lastModified()); // Update timestamp
                statusChanged = true; // Path change also means entry changed
            } else {
                Log.w(TAG, "Processed path '" + processedPathFromOutput + "' reported by worker does not exist or is not a file. Not updating entry path.");
            }
        }

        // Ensure workRequestId is set
        if (entry.getWorkRequestId() == null || !entry.getWorkRequestId().equals(workInfo.getId().toString())) {
            entry.setWorkRequestId(workInfo.getId().toString());
            statusChanged = true; // Technically an update even if status string is same
        }
        return statusChanged;
    }

    private String safeGetMessage(ServerResponse response){
        if(response == null) return "N/A";
        if(response.getDataMessage() != null) return response.getDataMessage();
        if(response.getMessage() != null) return response.getMessage();
        return "成功 (无消息)"; // Success (no specific message)
    }

    private ServerResponse parseServerResponse(String rawJson) {
        if (rawJson == null) return null;
        try { return new Gson().fromJson(rawJson, ServerResponse.class); }
        catch (Exception e) { Log.e(TAG, "GSON parse error: " + rawJson, e); return null; }
    }

    private static class ParsedRecordingInfo {
        String remoteNumber; String ownNumber; long timestampMillis;
        ParsedRecordingInfo(String remote, String own, long timestamp) {
            this.remoteNumber = FileUtils.normalizePhoneNumber(remote);
            this.ownNumber = FileUtils.normalizePhoneNumber(own);
            this.timestampMillis = timestamp;
        }
        static ParsedRecordingInfo createGeneric(long fallbackTimestamp) { return new ParsedRecordingInfo("Unknown", "Unknown", fallbackTimestamp); }
        @Override public String toString() { return "ParsedInfo: Remote=" + remoteNumber + ", Own=" + ownNumber + ", Time=" + timestampMillis; }
    }

    private ParsedRecordingInfo parseRecordingInfoFromFilename(String fileName, String filePath) {
        if (fileName == null) return ParsedRecordingInfo.createGeneric(new File(filePath).lastModified());
        Matcher matcher = FILENAME_PATTERN.matcher(fileName);
        if (matcher.find()) {
            String group1 = matcher.group(1); // Can be remote or remote(own)
            String group2 = matcher.group(2); // Can be own if group1 is just remote, or empty
            String dateTimeStr = matcher.group(3);
            String remoteNum, ownNum;

            if (!TextUtils.isEmpty(group2)) { // Format: remote_own_timestamp or remote(something)_own_timestamp
                remoteNum = group1; // This might need refinement if group1 itself has (own)
                ownNum = group2;
                // A common pattern is "REMOTE_OWN_YYYYMMDDHHMMSS.mp3" or "REMOTE(CALLER_ID_NAME)_OWN_..."
                // If group1 contains '(', it might be "REMOTE_NUMBER(CALLER_ID_NAME)"
                int remoteParenIndex = remoteNum.indexOf('(');
                if (remoteParenIndex != -1) {
                    remoteNum = remoteNum.substring(0, remoteParenIndex).trim();
                }

            } else { // Format: remote(own)_timestamp or just remote_timestamp
                int ownNumStartIndex = group1.lastIndexOf('(');
                int ownNumEndIndex = group1.lastIndexOf(')');
                if (ownNumStartIndex != -1 && ownNumEndIndex > ownNumStartIndex) {
                    remoteNum = group1.substring(0, ownNumStartIndex).trim();
                    ownNum = group1.substring(ownNumStartIndex + 1, ownNumEndIndex).trim();
                } else { // Only remote number found, or non-standard format
                    remoteNum = group1.trim();
                    // Attempt to get own number from SharedPreferences as a fallback
                    ownNum = sharedPreferences.getString(KEY_PHONE_NUMBER_1, "Local");
                    if(TextUtils.isEmpty(ownNum)) ownNum = sharedPreferences.getString(KEY_PHONE_NUMBER_2, "Local");
                }
            }
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault());
                Date date = sdf.parse(dateTimeStr);
                if (date != null) return new ParsedRecordingInfo(remoteNum, ownNum, date.getTime());
            } catch (ParseException e) { Log.e(TAG, "Filename date parse error: " + fileName, e); }
        }
        Log.w(TAG, "Could not parse filename: " + fileName + " using FILENAME_PATTERN. Creating generic info.");
        return ParsedRecordingInfo.createGeneric(new File(filePath).lastModified());
    }

    // `isDeferredUploadContext` is true if called from the path that defers upload until call end.
    // It's false if called from initial scan or other non-active-call contexts.
    private synchronized void handleDiscoveredRecording(String filePath, String fileName, @Nullable ParsedRecordingInfo info, boolean isSystemRecordingSource, boolean isPartOfActiveCallDeferredPath) {
        Log.i(TAG, "Handling discovered recording: " + fileName + (isSystemRecordingSource ? " (Sys)" : " (App)") + ", isDeferredPath: " + isPartOfActiveCallDeferredPath);
        File currentFile = new File(filePath);
        if (!currentFile.exists() || currentFile.length() < 1024) { // Minimum 1KB
            Log.w(TAG, "File invalid/small, skipping: " + filePath + ", Size: " + (currentFile.exists() ? currentFile.length() : "N/A"));
            if (currentFile.exists() && !isSystemRecordingSource) { // Delete small app-generated files
                currentFile.delete();
            }
            return;
        }

        ParsedRecordingInfo currentParsedInfo = (info != null && !"Unknown".equals(info.remoteNumber)) ? info : parseRecordingInfoFromFilename(fileName, filePath);
        Log.d(TAG, "Parsed info for " + fileName + ": " + currentParsedInfo.toString());

        // If this is part of the active call's deferred upload path, we've already stored it in `recordingAwaitingCallEnd`.
        // We just need to ensure its entry in the UI list is up-to-date.
        if (isPartOfActiveCallDeferredPath) {
            addOrUpdateRecordingEntryInList(filePath, fileName, getString(R.string.status_waiting_call_end), null, true);
            return; // Upload will be handled by callEndedReceiver
        }

        // --- Logic for non-deferred recordings (old files, system files not matching active call) ---
        // Check if this file is already known and in a final or active state
        synchronized(recordingEntriesList) {
            for (RecordingEntry entry : recordingEntriesList) {
                if (entry.getFilePath().equals(filePath)) {
                    if (entry.getUploadStatus().startsWith(getString(R.string.status_upload_success_prefix)) ||
                            entry.getUploadStatus().equals(getString(R.string.status_uploading)) ||
                            entry.getUploadStatus().equals(getString(R.string.status_queued)) ||
                            entry.getUploadStatus().equals(getString(R.string.status_queued_auto)) ||
                            entry.getUploadStatus().equals(getString(R.string.status_call_ended_waiting_delay)) || // Already handled by deferred path
                            entry.getUploadStatus().equals(getString(R.string.status_waiting_call_end)) // Already handled by deferred path
                    ) {
                        Log.d(TAG, "handleDiscoveredRecording (non-deferred): File " + fileName + " already processed, active, or deferred. Status: " + entry.getUploadStatus() + ". Skipping re-enqueue.");
                        // Optionally update its UI entry if there's new info, but don't re-enqueue.
                        // addOrUpdateRecordingEntryInList(filePath, fileName, entry.getUploadStatus(), entry.getWorkRequestId(), false);
                        return; // Already handled or will be handled by deferred path
                    }
                    Log.d(TAG, "handleDiscoveredRecording (non-deferred): File " + fileName + " found in list with status: " + entry.getUploadStatus() + ". Will re-evaluate for upload.");
                    break; // Found it, will proceed with checks below
                }
            }
        }


        long nowForCache = SystemClock.elapsedRealtime();
        recentCallsCache.removeIf(cacheEntry -> (nowForCache - cacheEntry.discoveryTimeMs) > RECENT_CALL_CACHE_DURATION_MS);

        boolean preferSystem = sharedPreferences.getBoolean(KEY_PREFER_SYSTEM_RECORDING, true);
        // Check if the source type itself is enabled
        boolean appRecEnabledForThis = !isSystemRecordingSource && sharedPreferences.getBoolean(KEY_APP_RECORDING_ENABLED, false);
        boolean sysMonEnabledForThis = isSystemRecordingSource && sharedPreferences.getBoolean(KEY_SYSTEM_MONITORING_ENABLED, true);

        if (!appRecEnabledForThis && !sysMonEnabledForThis) {
            Log.i(TAG, "Neither app recording nor system monitoring is enabled for this type of source. Skipping " + fileName);
            addOrUpdateRecordingEntryInList(filePath, fileName, "跳过 (源类型禁用)", null, true);
            return;
        }


        RecentCallInfo conflictingCall = null;
        if (!"Unknown".equals(currentParsedInfo.remoteNumber)) { // Only check for conflicts if we have a remote number
            for (RecentCallInfo cacheEntry : recentCallsCache) {
                if (TextUtils.equals(cacheEntry.remoteNumber, currentParsedInfo.remoteNumber) &&
                        Math.abs(cacheEntry.approxTimestamp - currentParsedInfo.timestampMillis) < DUPLICATE_CALL_THRESHOLD_MS) {
                    conflictingCall = cacheEntry;
                    break;
                }
            }
        }


        String uiStatus = getString(R.string.status_processing_for_upload);
        boolean shouldUploadThis = true;

        if (conflictingCall != null) {
            Log.i(TAG, "Potential duplicate for (non-deferred) " + fileName + ": Current '" + fileName + "' (isSys=" + isSystemRecordingSource + ", time=" + currentParsedInfo.timestampMillis +
                    ") vs Cached '" + new File(conflictingCall.sourceFilePath).getName() + "' (isSys=" + conflictingCall.isSystemRecording + ", time=" + conflictingCall.approxTimestamp +")");
            if (preferSystem) {
                if (isSystemRecordingSource && !conflictingCall.isSystemRecording) { // Current is System, Cached is App
                    Log.i(TAG, "Preferring current System recording. Deleting cached App recording: " + conflictingCall.sourceFilePath);
                    deleteFileQuietly(conflictingCall.sourceFilePath);
                    recentCallsCache.remove(conflictingCall); // Remove the app recording from cache
                    // uiStatus remains "processing for upload" for current system file
                } else if (!isSystemRecordingSource && conflictingCall.isSystemRecording) { // Current is App, Cached is System
                    Log.i(TAG, "Preferring cached System recording. Deleting current App recording: " + filePath);
                    deleteFileQuietly(filePath);
                    uiStatus = getString(R.string.status_skipped_prefer_system); shouldUploadThis = false;
                } else { // Both are same type or some other condition
                    // If current file is older than cached one, and both are same type, maybe skip current
                    if(currentParsedInfo.timestampMillis < conflictingCall.approxTimestamp && isSystemRecordingSource == conflictingCall.isSystemRecording) {
                        Log.i(TAG, "Skipping older duplicate (non-deferred): " + fileName);
                        uiStatus = getString(R.string.status_skipped_duplicate_older); shouldUploadThis = false;
                    } else if (!filePath.equals(conflictingCall.sourceFilePath)) { // Different files, but considered duplicate by time/number
                        Log.i(TAG, "Considered duplicate (non-deferred), but different files. Current: " + fileName + ", Cached: " + new File(conflictingCall.sourceFilePath).getName() + ". Uploading current, assuming it's the more relevant one if not older.");
                        // If we proceed, we might want to remove the older one from cache, or even delete it if it's an app recording
                        if (conflictingCall.isSystemRecording == isSystemRecordingSource && currentParsedInfo.timestampMillis > conflictingCall.approxTimestamp) {
                            // If new one is same type and newer, remove old from cache, let old one be (or get deleted if it was app type)
                            recentCallsCache.remove(conflictingCall);
                        }
                    } else { // Same file path, already in cache
                        Log.i(TAG, "File " + fileName + " is already in recentCallsCache. Status: " + uiStatus);
                        // shouldUploadThis might still be true if it was not successfully uploaded before.
                        // The check against recordingEntriesList status at the beginning handles this.
                    }
                }
            } else { // Not preferring system (prefer app, or first-come-first-served if same type)
                if (isSystemRecordingSource && !conflictingCall.isSystemRecording) { // Current Sys, Cached App (but we DON'T prefer Sys)
                    Log.i(TAG, "Not preferring system. Cached App record exists. Deleting current System record: " + filePath);
                    deleteFileQuietly(filePath);
                    uiStatus = getString(R.string.status_skipped_prefer_app); shouldUploadThis = false;
                } else if (!isSystemRecordingSource && conflictingCall.isSystemRecording) { // Current App, Cached Sys
                    Log.i(TAG, "Not preferring system. Preferring current App record. Deleting cached System record: " + conflictingCall.sourceFilePath);
                    deleteFileQuietly(conflictingCall.sourceFilePath);
                    recentCallsCache.remove(conflictingCall);
                } else { // Both same type
                    if(currentParsedInfo.timestampMillis < conflictingCall.approxTimestamp) {
                        Log.i(TAG, "Skipping older duplicate (non-deferred, not prefer system): " + fileName);
                        uiStatus = getString(R.string.status_skipped_duplicate_older); shouldUploadThis = false;
                    } else if (!filePath.equals(conflictingCall.sourceFilePath)) {
                        Log.i(TAG, "Considered duplicate (non-deferred), but different files. Uploading current.");
                        if (currentParsedInfo.timestampMillis > conflictingCall.approxTimestamp) recentCallsCache.remove(conflictingCall);
                    }
                }
            }
        }

        addOrUpdateRecordingEntryInList(filePath, fileName, uiStatus, null, true); // Update UI first

        if (shouldUploadThis) {
            // Add to recentCallsCache only if it's not a re-evaluation of the exact same file already in cache
            boolean alreadyInCache = false;
            for(RecentCallInfo rci : recentCallsCache) {
                if (rci.sourceFilePath.equals(filePath)) {
                    alreadyInCache = true;
                    break;
                }
            }
            if(!alreadyInCache) {
                recentCallsCache.add(new RecentCallInfo(currentParsedInfo.remoteNumber, currentParsedInfo.ownNumber, currentParsedInfo.timestampMillis, filePath, isSystemRecordingSource));
            }
            enqueueUpload(filePath, fileName, currentParsedInfo);
        }
    }


    private void deleteFileQuietly(String path) {
        if (path == null) return;
        File fileToDelete = new File(path);
        if (fileToDelete.exists()) {
            if (fileToDelete.delete()) {
                Log.i(TAG, "Successfully deleted file: " + path);
                synchronized (recordingEntriesList) {
                    recordingEntriesList.removeIf(entry -> entry.getFilePath().equals(path));
                    uiHandler.post(() -> recordingLogAdapter.updateData(new ArrayList<>(recordingEntriesList)));
                }
            } else { Log.w(TAG, "Failed to delete file: " + path); }
        }
    }

    private void enqueueUpload(String filePath, String fileName, ParsedRecordingInfo parsedInfo) {
        String identifierForUpload = parsedInfo.remoteNumber;
        // If remote is "Unknown", try to use own number as identifier
        if ("Unknown".equals(identifierForUpload) && !"Unknown".equals(parsedInfo.ownNumber) && !"Local".equalsIgnoreCase(parsedInfo.ownNumber)) {
            identifierForUpload = parsedInfo.ownNumber;
        } else if ("Unknown".equals(identifierForUpload)) {
            identifierForUpload = "Recording"; // Generic if both unknown
        }
        enqueueUploadRequest(filePath, identifierForUpload, false, fileName);
    }

    // Modified to avoid adding duplicate if only status changes and not adding a new workId
    private void addOrUpdateRecordingEntryInList(String filePath, String fileName, String status, @Nullable String workId, boolean shouldAddIfNew) {
        synchronized (recordingEntriesList) {
            boolean found = false;
            int existingIdx = -1;
            for (int i = 0; i < recordingEntriesList.size(); i++) {
                RecordingEntry entry = recordingEntriesList.get(i);
                if (entry.getFilePath().equals(filePath)) {
                    entry.setUploadStatus(status); // Always update status
                    if (workId != null && (entry.getWorkRequestId() == null || !entry.getWorkRequestId().equals(workId))) {
                        // Only set workId if it's new or different (e.g., a retry generates a new workId for the same file path)
                        entry.setWorkRequestId(workId);
                    }
                    if (status.startsWith(getString(R.string.status_upload_success_prefix))) {
                        entry.setUploadSuccessTime(System.currentTimeMillis());
                    } else if (!status.equals(getString(R.string.status_uploading)) && !status.equals(getString(R.string.status_queued))) {
                        // Clear success time if it's not success, uploading or queued
                        // entry.setUploadSuccessTime(0); // This might be too aggressive, success time should persist.
                    }
                    existingIdx = i;
                    found = true;
                    break;
                }
            }

            if (!found && shouldAddIfNew) {
                File f = new File(filePath);
                long timestamp = f.exists() ? f.lastModified() : System.currentTimeMillis();
                ParsedRecordingInfo pInfo = parseRecordingInfoFromFilename(fileName, filePath); // Re-parse to get its own timestamp notion
                if (pInfo != null && pInfo.timestampMillis > 0) {
                    timestamp = pInfo.timestampMillis;
                }
                RecordingEntry newEntry = new RecordingEntry(filePath, fileName, timestamp, status, workId, 0);
                if (status.startsWith(getString(R.string.status_upload_success_prefix))) {
                    newEntry.setUploadSuccessTime(System.currentTimeMillis());
                }
                recordingEntriesList.add(0, newEntry); // Add to top, then sort
            }

            filterAndSortRecordingList(); // Always re-sort and filter

            // More efficient UI update
            final int finalFoundIdx = existingIdx; // Index if found
            final boolean finalAddedNew = !found && shouldAddIfNew;

            uiHandler.post(() -> {
                if (finalAddedNew) {
                    recordingLogAdapter.updateData(new ArrayList<>(recordingEntriesList)); // Full update if new item added
                } else if (finalFoundIdx != -1) {
                    recordingLogAdapter.notifyItemChanged(finalFoundIdx); // Specific item update
                } else {
                    // If !shouldAddIfNew and not found, no UI change was intended for this specific call
                }
            });
        }
    }


    public void enqueueUploadRequest(String filePathOrUriString, String phoneNumberIdentifier, boolean isManualSelection, String originalFileNameForDisplay) {
        String displayName = originalFileNameForDisplay != null ? originalFileNameForDisplay : FileUtils.getFileNameFromPath(filePathOrUriString);
        Log.d(TAG, "enqueueUploadRequest: Queuing for: " + displayName + " (Path/URI: " + filePathOrUriString + "), PhoneID: " + phoneNumberIdentifier);

        // Prevent re-queuing if already successfully uploaded and not manual
        if (!isManualSelection) {
            synchronized(recordingEntriesList) {
                for (RecordingEntry entry : recordingEntriesList) {
                    if (entry.getFilePath().equals(filePathOrUriString) &&
                            entry.getUploadStatus().startsWith(getString(R.string.status_upload_success_prefix))) {
                        Log.i(TAG, "File " + displayName + " already successfully uploaded. Skipping auto re-queue.");
                        return;
                    }
                }
            }
        }


        Data inputData = new Data.Builder()
                .putString(UploadWorker.KEY_FILE_PATH, filePathOrUriString)
                .putString(UploadWorker.KEY_PHONE_NUMBER, phoneNumberIdentifier != null ? phoneNumberIdentifier : "N/A")
                .putString(UploadWorker.KEY_ORIGINAL_FILE_NAME, displayName)
                .build();
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();

        // Generate a unique work name based on the file path/URI to leverage ExistingWorkPolicy.KEEP
        // This helps prevent multiple identical upload jobs for the same file if one is already pending or running.
        String uniqueWorkName = "upload_" + filePathOrUriString.hashCode() + "_" + System.currentTimeMillis(); // Add timestamp for true uniqueness if retrying failed

        OneTimeWorkRequest uploadWorkRequest = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setInputData(inputData)
                .setConstraints(constraints)
                .addTag(UploadWorker.WORK_TAG_UPLOAD)
                // Optional: Add backoff policy for retries on failure
                // .setBackoffCriteria(BackoffPolicy.LINEAR, OneTimeWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();

        // Using KEEP: If a work request with the same unique name already exists and is not finished, the new request is ignored.
        // If it has finished (succeeded/failed/cancelled), a new one can be enqueued.
        // For failed uploads, we *want* to be able to re-enqueue them (manually or via a retry mechanism).
        // So, if a previous attempt FAILED, ExistingWorkPolicy.KEEP will allow a new enqueue with the same name if that old one is truly 'done'.
        // If we want to replace a PENDING one, use REPLACE. For just avoiding duplicates of PENDING, KEEP is fine.
        WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, uploadWorkRequest);

        Log.i(TAG, "Enqueued WorkRequest ID: " + uploadWorkRequest.getId() + " (UniqueName: " + uniqueWorkName +") for: " + displayName);

        addOrUpdateRecordingEntryInList(filePathOrUriString, displayName,
                isManualSelection ? getString(R.string.status_queued_manual_selection) : getString(R.string.status_queued_auto),
                uploadWorkRequest.getId().toString(), true);

        if (isManualSelection || !isFinishing()) { // Avoid Toast if activity is finishing during auto process
            Toast.makeText(this, getString(R.string.upload_request_queued, displayName), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onManualUploadClick(RecordingEntry entry) {
        ParsedRecordingInfo info = parseRecordingInfoFromFilename(entry.getFileName(), entry.getFilePath());
        String phoneId = (info != null && !"Unknown".equals(info.remoteNumber)) ? info.remoteNumber : "ManualRetry";
        // If own number is more specific than "Unknown" for remote, use it.
        if ("ManualRetry".equals(phoneId) && info != null && !"Unknown".equals(info.ownNumber) && !"Local".equalsIgnoreCase(info.ownNumber)) {
            phoneId = info.ownNumber;
        }
        Log.i(TAG, "Manual upload initiated for: " + entry.getFileName() + " with phoneId: " + phoneId);
        enqueueUploadRequest(entry.getFilePath(), phoneId, true, entry.getFileName());
    }

    private void openAudioPicker() {
        if (!PermissionUtils.hasReadExternalStoragePermission(this) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ) {
            Toast.makeText(this, "请先授予读取存储权限", Toast.LENGTH_SHORT).show();
            PermissionUtils.requestReadExternalStoragePermission(this, PERMISSIONS_REQUEST_CODE_BASE); return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT); intent.setType("audio/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_audio_file_title)), REQUEST_CODE_PICK_AUDIO); }
        catch (android.content.ActivityNotFoundException ex) { Toast.makeText(this, R.string.file_manager_missing_toast, Toast.LENGTH_SHORT).show(); }
    }

    private void checkAllPermissions() {
        List<String> neededPermissions = new ArrayList<>(java.util.Arrays.asList(PermissionUtils.getBasePermissions(Build.VERSION.SDK_INT)));
        // Specific storage permissions for older Android versions if not targeting app-specific storage strictly
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { // Below Android 11
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CALL_PHONE);
        }

        // Remove already granted permissions
        neededPermissions.removeIf(perm -> ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED);

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), PERMISSIONS_REQUEST_CODE_BASE);
        }
        updatePermissionStatusText(); updateButtonStates();
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[]p, @NonNull int[]gr) {
        super.onRequestPermissionsResult(rc, p, gr);
        boolean allBaseGranted = true;
        if (rc == PERMISSIONS_REQUEST_CODE_BASE) {
            for (int result : gr) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allBaseGranted = false;
                    break;
                }
            }
            if(allBaseGranted && (PermissionUtils.hasAllRequiredPermissionsForMiUiMonitoring(this) || PermissionUtils.hasBasePermissions(this, Build.VERSION.SDK_INT))){
                Log.i(TAG, "All base permissions granted after request. Attempting to start services.");
                autoStartServicesBasedOnSettings();
            } else if (!allBaseGranted) {
                Log.w(TAG, "Some base permissions were denied after request.");
                Toast.makeText(this, R.string.some_permissions_denied_message, Toast.LENGTH_LONG).show();
            }
        }
        updatePermissionStatusText(); updateButtonStates();
    }

    @Override
    protected void onActivityResult(int rc, int resCode, @Nullable Intent data) {
        super.onActivityResult(rc, resCode, data);
        if (rc == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.overlay_permission_granted_toast, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.overlay_permission_not_granted_toast, Toast.LENGTH_SHORT).show();
            }
        } else if (rc == MANAGE_STORAGE_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                Toast.makeText(this, R.string.manage_storage_permission_granted_toast, Toast.LENGTH_SHORT).show();
                autoStartServicesBasedOnSettings(); // Try starting services if storage perm is key
            } else {
                Toast.makeText(this, R.string.manage_storage_permission_not_granted_toast, Toast.LENGTH_SHORT).show();
            }
        } else if (rc == REQUEST_CODE_PICK_AUDIO && resCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String fn = FileUtils.getFileNameFromUri(this, uri);
            Log.d(TAG, "Audio picked: " + uri.toString() + ", Filename: " + fn);
            enqueueUploadRequest(uri.toString(), "ManualPick-" + System.currentTimeMillis(), true, fn);
        }
        updatePermissionStatusText(); updateButtonStates();
    }

    private void updatePermissionStatusText() {
        StringBuilder s = new StringBuilder(getString(R.string.permission_status_title_template)); // "权限状态:\n"
        s.append("\n  ").append(getString(R.string.permission_record_phone_state_template, PermissionUtils.hasBasePermissions(this, Build.VERSION.SDK_INT) ? "✔️" : "❌"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            s.append("\n  ").append(getString(R.string.permission_overlay_template, Settings.canDrawOverlays(this) ? "✔️" : "❌"));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            s.append("\n  ").append(getString(R.string.permission_manage_files_template, Environment.isExternalStorageManager() ? "✔️" : "❌"));
        } else {
            s.append("\n  ").append(getString(R.string.permission_storage_legacy_template,
                    (PermissionUtils.hasReadExternalStoragePermission(this) && PermissionUtils.hasWriteExternalStoragePermission(this, Build.VERSION.SDK_INT)) ? "✔️" : "❌"));
        }
        s.append("\n  ").append(getString(R.string.permission_call_phone_template, ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED ? "✔️" : "❌"));

        tvPermissionStatus.setText(s.toString());
        tvStatus.setText(PermissionUtils.hasAllRequiredPermissionsForMiUiMonitoring(this) || PermissionUtils.hasBasePermissions(this, Build.VERSION.SDK_INT)
                ? getString(R.string.core_permissions_ready)
                : getString(R.string.please_grant_necessary_permissions));
    }


    private void updateAutoUploadServiceStatusText(boolean isAnyWorkActive) {
        if (isAnyWorkActive) {
            tvAutoUploadServiceStatus.setText(getString(R.string.auto_upload_status_active));
        } else {
            // Check if monitoring services are supposed to be running based on settings and actual state
            boolean miuiRunning = MiUiCallRecordMonitorService.isRunning();
            boolean appRecServiceCouldRun = sharedPreferences.getBoolean(KEY_APP_RECORDING_ENABLED, false); // Not RecordingService.IS_SERVICE_RUNNING as it's for active recording

            if ( (sharedPreferences.getBoolean(KEY_SYSTEM_MONITORING_ENABLED, true) && miuiRunning) || appRecServiceCouldRun ) {
                tvAutoUploadServiceStatus.setText(getString(R.string.auto_upload_status_idle));
            } else {
                tvAutoUploadServiceStatus.setText(getString(R.string.auto_upload_status_inactive_or_not_configured));
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && AppWebSocketClientService.ACTION_INITIATE_CALL.equals(intent.getAction())) {
            String middleNumber = intent.getStringExtra(AppWebSocketClientService.EXTRA_MIDDLE_NUMBER);
            String targetOriginalNumber = intent.getStringExtra(AppWebSocketClientService.EXTRA_TARGET_ORIGINAL_NUMBER_FOR_CALL);
            if (!TextUtils.isEmpty(middleNumber) && !TextUtils.isEmpty(targetOriginalNumber)) {
                promptToCallMiddleNumber(middleNumber, targetOriginalNumber);
            }
        }
    }

    private void promptToCallMiddleNumber(String middleNumber, String targetOriginalNumber) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_call_middle_number))
                .setMessage(getString(R.string.dialog_message_call_middle_number, middleNumber, targetOriginalNumber))
                .setPositiveButton(getString(R.string.button_call_action), (dialog, which) -> {
                    initiateCall(middleNumber);
                    long callInitiationTime = SystemClock.elapsedRealtime(); // Using elapsedRealtime for duration/internal timing
                    // Convert to actual time for storage if needed, or store elapsed + current time reference
                    String middleCallInfo = middleNumber + "_" + targetOriginalNumber + "_" + FileUtils.convertElapsedTimestampToActual(callInitiationTime);
                    sharedPreferences.edit().putString(KEY_LAST_CALLED_MIDDLE_NUMBER_INFO, middleCallInfo).apply();
                })
                .setNegativeButton(getString(R.string.button_cancel), null).show();
    }

    private void initiateCall(String phoneNumber) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.toast_missing_call_permission), Toast.LENGTH_SHORT).show();
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CALL_PHONE}, PERMISSIONS_REQUEST_CODE_BASE); return;
        }
        Intent callIntent = new Intent(Intent.ACTION_CALL); callIntent.setData(Uri.parse("tel:" + phoneNumber));
        try { startActivity(callIntent); }
        catch (SecurityException e) {
            Log.e(TAG, "Call failed due to SecurityException: " + e.getMessage());
            Toast.makeText(this, getString(R.string.toast_call_failed_security, e.getMessage()), Toast.LENGTH_LONG).show();
            // Fallback to DIAL intent if CALL fails
            Intent dialIntent = new Intent(Intent.ACTION_DIAL); dialIntent.setData(Uri.parse("tel:" + phoneNumber));
            startActivity(dialIntent);
        }
    }

    // This dialog is currently not used with the new deferred upload logic, but kept for potential future use.
    public void showUploadConfirmationDialog(String recordingPath, String associatedPhoneNumber, String fileName) {
        if (isFinishing() || isDestroyed()) return;
        File recordingFile = new File(recordingPath); if (!recordingFile.exists()) return;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_upload_confirmation, null);
        TextView tvFileName = dialogView.findViewById(R.id.tvDialogRecordingName);
        TextView tvFileSize = dialogView.findViewById(R.id.tvDialogRecordingSize);
        tvFileName.setText(fileName);
        tvFileSize.setText(android.text.format.Formatter.formatFileSize(this, recordingFile.length()));

        new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_title_upload_new_recording))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.button_upload_now), (d,w) -> enqueueUploadRequest(recordingPath, associatedPhoneNumber, false, fileName))
                .setNegativeButton(getString(R.string.button_upload_later_manual), (d,w) -> {
                    synchronized(recordingEntriesList){
                        boolean found=false;
                        for(RecordingEntry entry : recordingEntriesList) {
                            if(entry.getFilePath().equals(recordingPath)){
                                entry.setUploadStatus(getString(R.string.status_pending_manual_upload));
                                found=true;
                                break;
                            }
                        }
                        if(!found) {
                            recordingEntriesList.add(0, new RecordingEntry(recordingPath, fileName, recordingFile.lastModified(), getString(R.string.status_pending_manual_upload), null, 0));
                        }
                        filterAndSortRecordingList();
                        recordingLogAdapter.updateData(new ArrayList<>(recordingEntriesList));
                    }
                })
                .setNeutralButton(getString(R.string.button_cancel), null).show();
    }


    private void updateWebSocketStatus(String status) {
        if (tvWebSocketStatus != null) {
            String displayStatus = TextUtils.isEmpty(status) ? getString(R.string.status_websocket_unknown) : status;
            runOnUiThread(() -> tvWebSocketStatus.setText(getString(R.string.websocket_status_template, displayStatus)));
        }
    }

    private static final String[] PATHS_TO_CHECK_FOR_OLD_RECORDINGS = {
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

// ... 其他 MainActivity 代码 ...

    private void checkAndProcessOldRecordings() {
        boolean systemMonitoringEnabled = sharedPreferences.getBoolean(KEY_SYSTEM_MONITORING_ENABLED, true);
        if (!systemMonitoringEnabled) {
            Log.i(TAG, "checkAndProcessOldRecordings: System monitoring is disabled. Skipping check for old system recordings.");
            return;
        }

        // 权限检查应该在调用此方法之前或在此方法开始时进行
        if (!PermissionUtils.canAccessMiUiRecordingFolder(this)) { // canAccessMiUiRecordingFolder 内部会检查 MANAGE_EXTERNAL_STORAGE 或 READ_EXTERNAL_STORAGE
            Log.w(TAG, "checkAndProcessOldRecordings: Cannot access external storage to check old recordings (permissions missing or folder inaccessible).");
            // 可以在这里向用户发出一次性的Toast或更新UI提示权限问题
            // uiHandler.post(() -> Toast.makeText(MainActivity.this, "无法检查旧录音，存储权限不足", Toast.LENGTH_LONG).show());
            return;
        }

        Log.d(TAG, "checkAndProcessOldRecordings: Scanning for unprocessed system recordings from the last 24 hours across known paths.");

        new Thread(() -> {
            List<File> allRecentPotentialFiles = new ArrayList<>();
            int totalFilesFoundAcrossFolders = 0;

            for (String currentPathString : PATHS_TO_CHECK_FOR_OLD_RECORDINGS) {
                File miuiRecDir = new File(currentPathString);
                if (!miuiRecDir.exists() || !miuiRecDir.isDirectory()) {
                    // Log.d(TAG, "checkAndProcessOldRecordings: Path does not exist or is not a directory: " + currentPathString);
                    continue; // 尝试下一个路径
                }

                Log.d(TAG, "checkAndProcessOldRecordings: Scanning path: " + currentPathString);

                long twentyFourHoursAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
                File[] recentFilesInCurrentFolder = null;
                try {
                    recentFilesInCurrentFolder = miuiRecDir.listFiles(file ->
                            file.isFile() &&
                                    (file.getName().toLowerCase().endsWith(".mp3") || // 常见的系统录音格式
                                            file.getName().toLowerCase().endsWith(".m4a") ||
                                            file.getName().toLowerCase().endsWith(".aac") ||
                                            file.getName().toLowerCase().endsWith(".amr")) &&
                                    file.lastModified() >= twentyFourHoursAgo &&
                                    file.length() > 1024 // 至少大于1KB，过滤掉无效或空的录音文件
                    );
                } catch (SecurityException se) {
                    Log.e(TAG, "checkAndProcessOldRecordings: SecurityException while listing files in " + currentPathString, se);
                    // 如果一个路径出现权限问题，记录并继续尝试其他路径
                    continue;
                }


                if (recentFilesInCurrentFolder != null && recentFilesInCurrentFolder.length > 0) {
                    allRecentPotentialFiles.addAll(Arrays.asList(recentFilesInCurrentFolder));
                    totalFilesFoundAcrossFolders += recentFilesInCurrentFolder.length;
                }
            } // 结束遍历所有路径

            if (allRecentPotentialFiles.isEmpty()) {
                Log.d(TAG, "checkAndProcessOldRecordings: No recent valid system files found from any known path in the last 24 hours.");
                return;
            }

            Log.d(TAG, "checkAndProcessOldRecordings: Found " + totalFilesFoundAcrossFolders + " total recent system files across all checked paths.");
            int newFilesQueuedCount = 0;

            // 使用HashSet来避免因跨文件夹但文件名和时间戳可能相同而导致的重复处理（尽管可能性小）
            Set<String> processedFilePathsInThisRun = new HashSet<>();

            for (File fileToCheck : allRecentPotentialFiles) {
                if (!processedFilePathsInThisRun.add(fileToCheck.getAbsolutePath())) {
                    continue; // 如果此运行中已处理过此完整路径，则跳过
                }

                // 检查此文件是否对应于当前正在等待呼叫结束的延迟上传的录音
                if (recordingAwaitingCallEnd != null && recordingAwaitingCallEnd.filePath.equals(fileToCheck.getAbsolutePath())) {
                    Log.d(TAG, "checkAndProcessOldRecordings: File " + fileToCheck.getName() + " is currently awaiting active call end. Skipping separate processing via old check.");
                    continue;
                }

                boolean alreadyHandledOrActive = false;
                synchronized (recordingEntriesList) {
                    for (RecordingEntry entry : recordingEntriesList) {
                        if (entry.getFilePath().equals(fileToCheck.getAbsolutePath())) {
                            // 如果文件已成功上传，或正在上传/排队中，或明确标记为等待延迟/手动，则视为已处理或正在处理
                            if (entry.getUploadStatus().startsWith(getString(R.string.status_upload_success_prefix)) ||
                                    entry.getUploadStatus().equals(getString(R.string.status_uploading)) ||
                                    entry.getUploadStatus().equals(getString(R.string.status_queued)) ||
                                    entry.getUploadStatus().equals(getString(R.string.status_queued_auto)) ||
                                    entry.getUploadStatus().equals(getString(R.string.status_queued_manual_selection)) ||
                                    entry.getUploadStatus().equals(getString(R.string.status_call_ended_waiting_delay)) ||
                                    entry.getUploadStatus().equals(getString(R.string.status_waiting_call_end))) {
                                alreadyHandledOrActive = true;
                            }
                            break;
                        }
                    }
                }

                if (!alreadyHandledOrActive) {
                    Log.i(TAG, "checkAndProcessOldRecordings: Found old, unprocessed system recording: " + fileToCheck.getName() + " in " + fileToCheck.getParentFile().getName());
                    // 解析文件名以获取可能的通话信息
                    final ParsedRecordingInfo parsedInfo = parseRecordingInfoFromFilename(fileToCheck.getName(), fileToCheck.getAbsolutePath());
                    final String filePathFinal = fileToCheck.getAbsolutePath();
                    final String fileNameFinal = fileToCheck.getName();

                    // 在UI线程上调用 handleDiscoveredRecording
                    // 注意：handleDiscoveredRecording 应该能够处理来自这里的调用，
                    // 并决定是否应该上传（例如，基于去重逻辑等）
                    uiHandler.post(() -> handleDiscoveredRecording(
                            filePathFinal,
                            fileNameFinal,
                            parsedInfo,
                            true, // 标记这是系统录音来源
                            false // 标记这不是活动呼叫延迟路径的一部分
                    ));
                    newFilesQueuedCount++; // 计数器在这里可能不完全准确反映实际入队的数量，因为handleDiscoveredRecording内部还有逻辑
                }
            }

            if (newFilesQueuedCount > 0) {
                final int finalCount = newFilesQueuedCount; // 这里只是发现的可能需要处理的新文件数
                Log.d(TAG, "checkAndProcessOldRecordings: Identified " + finalCount + " potentially new old system files to process.");
                // Toast 消息最好在 handleDiscoveredRecording 实际决定入队后再发出，或者基于实际入队数量
                // uiHandler.post(() -> Toast.makeText(MainActivity.this, getString(R.string.toast_added_old_recordings_to_queue, finalCount), Toast.LENGTH_LONG).show());
            } else {
                Log.d(TAG, "checkAndProcessOldRecordings: No new old system files to queue from this scan (either all handled or none found meeting criteria).");
            }
        }).start();
    }


    private void updateButtonStates() {
        // Overlay Permission Button
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean canDrawOverlays = Settings.canDrawOverlays(this);
            if (btnGrantOverlayPermission != null) {
                btnGrantOverlayPermission.setText(canDrawOverlays ? getString(R.string.overlay_permission_granted_button) : getString(R.string.grant_overlay_permission_button));
                btnGrantOverlayPermission.setEnabled(!canDrawOverlays);
            }
        } else {
            if (btnGrantOverlayPermission != null) {
                btnGrantOverlayPermission.setText(getString(R.string.overlay_permission_legacy_button));
                btnGrantOverlayPermission.setEnabled(false); // Not applicable
            }
        }

        // Storage Permission Button (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean hasManageStorage = Environment.isExternalStorageManager();
            if (btnGrantStoragePermission != null) {
                btnGrantStoragePermission.setVisibility(View.VISIBLE);
                btnGrantStoragePermission.setText(hasManageStorage ?
                        getString(R.string.storage_permission_granted_android11) :
                        getString(R.string.button_grant_storage_permission_android11));
                btnGrantStoragePermission.setEnabled(!hasManageStorage);
            }
        } else {
            if (btnGrantStoragePermission != null) {
                btnGrantStoragePermission.setVisibility(View.GONE);
            }
        }
        Log.d(TAG, "updateButtonStates: UI states updated.");
        // AutoUploadServiceStatus is updated by its own method via LiveData observer
    }


}