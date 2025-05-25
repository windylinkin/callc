package com.example.callrecorderuploader.worker;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.callrecorderuploader.MainActivity;
import com.example.callrecorderuploader.R;
import com.example.callrecorderuploader.model.ServerResponse; // For the main upload response
import com.example.callrecorderuploader.service.FloatingWindowService;
import com.example.callrecorderuploader.utils.FileUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject; // For parsing pre-upload check response
import com.google.gson.JsonParser; // For parsing pre-upload check response
import com.google.gson.JsonSyntaxException;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;


public class UploadWorker extends Worker {
    private static final String TAG = "UploadWorker";
    public static final String KEY_FILE_PATH = "key_file_path";
    public static final String KEY_PHONE_NUMBER = "key_phone_number";
    public static final String KEY_ORIGINAL_FILE_NAME = "key_original_file_name";

    public static final String KEY_OUTPUT_SERVER_RESPONSE = "key_output_server_response";
    public static final String KEY_OUTPUT_ERROR_MESSAGE = "key_output_error_message";
    public static final String KEY_OUTPUT_FILE_PATH_PROCESSED = "key_output_file_path_processed";
    public static final String KEY_OUTPUT_UPLOAD_SKIPPED_MESSAGE = "key_output_upload_skipped_message"; // New key for skipped uploads

    private static final String PRE_UPLOAD_CHECK_URL = "https://hideboot.jujia618.com/upload/preAudioRecord";
    private static final String UPLOAD_URL = "https://hideboot.jujia618.com/upload/audioRecord";
    private static final String UPLOAD_NOTIFICATION_CHANNEL_ID = "UploadNotificationChannel";
    private static final int UPLOAD_NOTIFICATION_ID_BASE = 20000; // Keep unique from other service notifications

    public static final String WORK_TAG_UPLOAD = "call_recording_upload";

    private NotificationManager notificationManager;
    private final Context appContext;
    private String displayFileNameForNotification = "uploading_file"; // Default


    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.appContext = context;
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createUploadNotificationChannel(context);
    }

    private void createUploadNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager != null && notificationManager.getNotificationChannel(UPLOAD_NOTIFICATION_CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        UPLOAD_NOTIFICATION_CHANNEL_ID,
                        context.getString(R.string.upload_notification_channel_name), // "文件上传"
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(context.getString(R.string.upload_notification_channel_description)); // "文件上传状态通知"
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private ForegroundInfo createForegroundInfo(String progressMessage) {
        String originalFileName = getInputData().getString(KEY_ORIGINAL_FILE_NAME);
        displayFileNameForNotification = (originalFileName != null && !originalFileName.isEmpty())
                ? originalFileName
                : appContext.getString(R.string.default_file_display_name); // "默认文件名称"

        Intent notificationIntent = new Intent(appContext, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(appContext, 0, notificationIntent, pendingIntentFlags);

        int notificationId = UPLOAD_NOTIFICATION_ID_BASE + getId().hashCode();

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(appContext, UPLOAD_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.upload_notification_title_template, displayFileNameForNotification)) // "正在上传: %1$s"
                .setTicker(progressMessage)
                .setContentText(progressMessage)
                .setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(notificationId, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(notificationId, notificationBuilder.build());
        }
    }

    @SuppressLint("MissingPermission")
    private void updateNotificationProgress(String message, boolean isProgress, int progressPercent, boolean isFinalStatus) {
        if (notificationManager == null) return;
        int notificationId = UPLOAD_NOTIFICATION_ID_BASE + getId().hashCode();
        String title = appContext.getString(R.string.upload_notification_title_template, displayFileNameForNotification);

        Intent notificationIntent = new Intent(appContext, MainActivity.class);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(appContext, 0, notificationIntent, pendingIntentFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, UPLOAD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(isProgress && !isFinalStatus) // Ongoing only if it's a progress update, not a final success/failure/skipped message
                .setAutoCancel(isFinalStatus); // Auto cancel for final status notifications

        if (isProgress && !isFinalStatus) {
            builder.setProgress(100, progressPercent, false);
        } else {
            builder.setProgress(0, 0, false); // Clear progress for final messages
        }
        try {
            notificationManager.notify(notificationId, builder.build());
        } catch (SecurityException e) { // Catch potential SecurityException if notification permission is revoked mid-operation
            Log.e(TAG, "Notification permission might be missing for upload progress: " + e.getMessage());
        }
    }

    private void removeNotificationAfterDelay() {
        if (notificationManager != null) {
            // Delay removal to allow user to see final status
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                notificationManager.cancel(UPLOAD_NOTIFICATION_ID_BASE + getId().hashCode());
            }, 7000); // 7 seconds delay
        }
    }

    private void manageFloatingWindow(boolean show, String message) {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context) && show) {
            Log.w(TAG, "Overlay permission not granted. Floating window for '" + message + "' not shown by worker.");
            return;
        }
        Intent windowIntent = new Intent(context, FloatingWindowService.class);
        windowIntent.setAction(show ? FloatingWindowService.ACTION_SHOW : FloatingWindowService.ACTION_HIDE);
        if (show) {
            windowIntent.putExtra(FloatingWindowService.EXTRA_MESSAGE, message != null ? message : context.getString(R.string.default_uploading_message));
        }
        try {
            context.startService(windowIntent);
        } catch (Exception e) { // Catch IllegalStateException if service cannot be started from background on some Android versions
            Log.e(TAG, "Error starting/stopping FloatingWindowService: " + e.getMessage());
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        String originalInputPathOrUri = getInputData().getString(KEY_FILE_PATH);
        String phoneNumber = getInputData().getString(KEY_PHONE_NUMBER);
        String originalFileName = getInputData().getString(KEY_ORIGINAL_FILE_NAME);

        displayFileNameForNotification = (originalFileName != null && !originalFileName.isEmpty())
                ? originalFileName
                : appContext.getString(R.string.default_file_display_name);

        Log.d(TAG, "UploadWorker: doWork() started for: " + displayFileNameForNotification + " (Path/URI: " + originalInputPathOrUri + ")");

        try {
            setForegroundAsync(createForegroundInfo(appContext.getString(R.string.status_upload_preparing))); // "准备上传..."
        } catch (Exception e) {
            Log.e(TAG, "Error calling setForegroundAsync for " + displayFileNameForNotification, e);
            // Cannot proceed without foreground service in many cases
            return Result.failure(createOutputData(null, "Foreground setup error: " + e.getMessage(), originalInputPathOrUri, null));
        }

        manageFloatingWindow(true, appContext.getString(R.string.upload_status_checking_file, displayFileNameForNotification)); // "检查文件状态: %s"
        updateNotificationProgress(appContext.getString(R.string.upload_status_checking_file, displayFileNameForNotification), true, 5, false);


        if (originalInputPathOrUri == null || originalInputPathOrUri.isEmpty()) {
            Log.e(TAG, "File path/URI is null or empty for " + displayFileNameForNotification);
            manageFloatingWindow(false, null);
            removeNotificationAfterDelay();
            return Result.failure(createOutputData(null, appContext.getString(R.string.error_file_path_uri_empty), originalInputPathOrUri, null)); // "文件路径/URI为空"
        }

        // --- Step 1: Pre-Upload Check ---
        OkHttpClient preCheckClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        boolean shouldProceedToUpload = false;
        String preCheckResponseMessage = "预检失败";

        try {
            JsonObject jsonPayload = new JsonObject();
            jsonPayload.addProperty("fileName", displayFileNameForNotification);
            // You might need to add other identifiers here if your backend requires them for pre-check
            // jsonPayload.addProperty("phoneNumber", phoneNumber);
            // jsonPayload.addProperty("timestamp", System.currentTimeMillis());

            RequestBody preCheckBody = RequestBody.create(jsonPayload.toString(), MediaType.get("application/json; charset=utf-8"));
            Request preCheckRequest = new Request.Builder()
                    .url(PRE_UPLOAD_CHECK_URL)
                    .post(preCheckBody)
                    .build();

            Log.i(TAG, "Performing pre-upload check for: " + displayFileNameForNotification + " to " + PRE_UPLOAD_CHECK_URL);
            updateNotificationProgress(appContext.getString(R.string.upload_status_pre_checking, displayFileNameForNotification), true, 10, false); // "预检查中: %s"

            Response preCheckResponse = preCheckClient.newCall(preCheckRequest).execute();
            try (ResponseBody responseBody = preCheckResponse.body()) {
                String preCheckResponseBodyString = (responseBody != null) ? responseBody.string() : "";
                Log.d(TAG, "Pre-upload check response for " + displayFileNameForNotification + ": Code=" + preCheckResponse.code() + ", Body=" + preCheckResponseBodyString);

                if (preCheckResponse.isSuccessful()) {
                    try {
                        JsonObject responseJson = JsonParser.parseString(preCheckResponseBodyString).getAsJsonObject();
                        // Adapt this parsing logic based on your actual API response structure
                        if (responseJson.has("data") && responseJson.get("data").isJsonObject() &&
                                responseJson.getAsJsonObject("data").has("shouldUpload")) {
                            shouldProceedToUpload = responseJson.getAsJsonObject("data").get("shouldUpload").getAsBoolean();
                            preCheckResponseMessage = responseJson.has("message") ? responseJson.get("message").getAsString() : (shouldProceedToUpload ? "需要上传" : "服务器确认无需上传");
                        } else if (responseJson.has("shouldUpload")) { // Simpler structure
                            shouldProceedToUpload = responseJson.get("shouldUpload").getAsBoolean();
                            preCheckResponseMessage = responseJson.has("reason") ? responseJson.get("reason").getAsString() : (shouldProceedToUpload ? "需要上传" : "服务器确认无需上传");
                        } else {
                            // If "shouldUpload" field is missing, assume failure or ambiguity
                            shouldProceedToUpload = false; // Default to not uploading if unclear
                            preCheckResponseMessage = appContext.getString(R.string.error_pre_check_response_invalid_format) +": "+ preCheckResponseBodyString; // "预检响应格式无效"
                            Log.w(TAG, "Pre-check response for " + displayFileNameForNotification + " was successful but 'shouldUpload' field is missing or in unexpected format.");
                        }
                    } catch (JsonSyntaxException | IllegalStateException e) {
                        shouldProceedToUpload = false; // Don't upload if response parsing fails
                        preCheckResponseMessage = appContext.getString(R.string.error_pre_check_response_parse_failed) + ": " + e.getMessage(); // "预检响应解析失败"
                        Log.e(TAG, "Error parsing pre-upload JSON response for " + displayFileNameForNotification, e);
                    }
                } else {
                    // HTTP error during pre-check (4xx, 5xx)
                    shouldProceedToUpload = false; // Don't upload if pre-check HTTP call failed
                    preCheckResponseMessage = appContext.getString(R.string.error_pre_check_http_failed, preCheckResponse.code(), preCheckResponse.message()); // "预检HTTP失败 (%1$d): %2$s"
                    Log.e(TAG, "Pre-upload check HTTP error for " + displayFileNameForNotification + ": " + preCheckResponse.code() + " " + preCheckResponse.message());
                }
            } // ResponseBody auto-closed here
        } catch (Exception e) {
            Log.e(TAG, "Exception during pre-upload check for " + displayFileNameForNotification, e);
            shouldProceedToUpload = false; // Don't upload if any exception occurs during pre-check
            preCheckResponseMessage = appContext.getString(R.string.error_pre_check_exception) + ": " + e.getMessage(); // "预检时发生异常"
            // This could be a network error, so Result.retry() might be appropriate
            manageFloatingWindow(false, null);
            updateNotificationProgress(preCheckResponseMessage, false, 0, true);
            removeNotificationAfterDelay();
            return Result.retry(); // Retry if pre-check itself fails due to network/exception
        }

        if (!shouldProceedToUpload) {
            Log.i(TAG, "Pre-upload check determined no upload needed for: " + displayFileNameForNotification + ". Reason: " + preCheckResponseMessage);
            manageFloatingWindow(false, null);
            updateNotificationProgress(appContext.getString(R.string.upload_status_skipped_by_server, displayFileNameForNotification, preCheckResponseMessage), false, 100, true); // "已跳过(服务器): %1$s - %2$s"
            removeNotificationAfterDelay();
            // Return success because the "work" (checking) completed, and server said no upload needed.
            // Pass the skip reason back to MainActivity.
            return Result.success(createOutputData(null, null, originalInputPathOrUri, preCheckResponseMessage));
        }

        Log.i(TAG, "Pre-upload check successful, proceeding with actual file upload for: " + displayFileNameForNotification);
        updateNotificationProgress(appContext.getString(R.string.status_upload_preparing_after_check, displayFileNameForNotification), true, 20, false); // "准备实际上传: %s"
        manageFloatingWindow(true, appContext.getString(R.string.uploading_specific_file, displayFileNameForNotification)); // "正在上传: %s"

        // --- Step 2: Actual File Upload (only if shouldProceedToUpload is true) ---
        File fileToUpload = null;
        String actualUploadedFilePath = originalInputPathOrUri;
        boolean isTempFileUsed = false;

        try {
            if (originalInputPathOrUri.startsWith("content://")) {
                Uri uri = Uri.parse(originalInputPathOrUri);
                updateNotificationProgress(appContext.getString(R.string.upload_status_copying_uri_content, displayFileNameForNotification), true, 30, false); // "复制文件内容: %s"
                File tempDir = appContext.getCacheDir();
                if (!tempDir.exists() && !tempDir.mkdirs()) {
                    Log.e(TAG, "Failed to create cache directory for temp file.");
                    throw new IOException("Cannot create cache directory");
                }
                // Sanitize filename for temp file system
                String tempFileNameToUse = displayFileNameForNotification.replaceAll("[^a-zA-Z0-9._-]", "_");
                if(tempFileNameToUse.length() > 100) tempFileNameToUse = tempFileNameToUse.substring(0,100);
                if(TextUtils.isEmpty(tempFileNameToUse)) tempFileNameToUse = "temp_upload_file";


                fileToUpload = new File(tempDir, "upload_temp_" + System.currentTimeMillis() + "_" + tempFileNameToUse);

                try (InputStream inputStream = appContext.getContentResolver().openInputStream(uri);
                     OutputStream outputStream = new FileOutputStream(fileToUpload)) {
                    if (inputStream == null) {
                        throw new java.io.FileNotFoundException(appContext.getString(R.string.error_uri_stream_null) + ": " + uri); // "无法从URI打开输入流"
                    }
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                    }
                    isTempFileUsed = true;
                    actualUploadedFilePath = fileToUpload.getAbsolutePath();
                    Log.d(TAG, "Copied URI content to temp file: " + actualUploadedFilePath + " for " + displayFileNameForNotification);
                }
            } else {
                fileToUpload = new File(originalInputPathOrUri);
            }

            if (fileToUpload == null || !fileToUpload.exists() || fileToUpload.length() == 0) {
                Log.e(TAG, "File for upload is invalid or empty: " + (fileToUpload != null ? fileToUpload.getAbsolutePath() : "null") + " for " + displayFileNameForNotification);
                String errorMsg = appContext.getString(R.string.error_file_invalid_or_empty, displayFileNameForNotification); // "文件无效、不存在或为空: %s"
                updateNotificationProgress(errorMsg, false, 0, true);
                if (isTempFileUsed && fileToUpload != null) fileToUpload.delete();
                return Result.failure(createOutputData(null, errorMsg, originalInputPathOrUri, null));
            }

            OkHttpClient uploadClient = new OkHttpClient.Builder() // Use a new client or the same one if settings are identical
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build();

            RequestBody fileBody = RequestBody.create(fileToUpload, MediaType.parse(FileUtils.determineMimeType(displayFileNameForNotification)));
            MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", displayFileNameForNotification, fileBody)
                    .addFormDataPart("filename", displayFileNameForNotification);
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                requestBodyBuilder.addFormDataPart("phoneNumber", phoneNumber);
            }
            requestBodyBuilder.addFormDataPart("uploadTime", String.valueOf(System.currentTimeMillis()));
            RequestBody requestBody = requestBodyBuilder.build();
            Request request = new Request.Builder().url(UPLOAD_URL).post(requestBody).build();

            Log.i(TAG, "Attempting to upload: " + displayFileNameForNotification + " (URL: " + UPLOAD_URL + ") from " + fileToUpload.getAbsolutePath());
            updateNotificationProgress(appContext.getString(R.string.status_uploading), true, 50, false); // "上传中..."

            Response response = uploadClient.newCall(request).execute();
            String responseBodyString = "";
            try (ResponseBody actualUploadResponseBody = response.body()) { // Ensure response body is closed
                if (actualUploadResponseBody != null) {
                    responseBodyString = actualUploadResponseBody.string();
                }
            }
            Log.d(TAG, "Upload response for " + displayFileNameForNotification + ": Code=" + response.code() + ", Body=" + responseBodyString);

            if (response.isSuccessful()) {
                Gson gson = new Gson();
                ServerResponse serverResponse = null;
                try {
                    serverResponse = gson.fromJson(responseBodyString, ServerResponse.class);
                } catch (JsonSyntaxException e) {
                    Log.e(TAG, "Failed to parse main upload JSON response for " + displayFileNameForNotification, e);
                    // Treat as success with parsing error, or failure depending on policy
                    updateNotificationProgress(appContext.getString(R.string.status_upload_success_generic_error_parsing_response) + " (UploadWorker)", false, 100, true);
                    return Result.success(createOutputData(responseBodyString, appContext.getString(R.string.error_upload_response_parse_failed), actualUploadedFilePath, null));
                }


                if (serverResponse != null && serverResponse.getCode() == 200 && serverResponse.getData() != null && serverResponse.getData().getCode() == 200) {
                    Log.i(TAG, "Upload successful for " + displayFileNameForNotification + ": " + serverResponse.getMessage() + " | Data: " + serverResponse.getData().getMessage());
                    String successMsg = appContext.getString(R.string.status_upload_success_prefix) + serverResponse.getData().getMessage(); // "上传成功: "
                    updateNotificationProgress(successMsg, false, 100, true);
                    return Result.success(createOutputData(responseBodyString, null, actualUploadedFilePath, null));
                } else {
                    String detailedError = appContext.getString(R.string.error_server_logic_failed); // "服务器逻辑错误"
                    if (serverResponse != null) {
                        detailedError = "外层: " + serverResponse.getMessage() + " (code " + serverResponse.getCode() + ")";
                        if (serverResponse.getData() != null) {
                            detailedError += " | 内层: " + serverResponse.getData().getMessage() + " (code " + serverResponse.getData().getCode() + ")";
                        }
                    } else { detailedError += " - " + appContext.getString(R.string.error_unable_to_parse_response) + ": " + responseBodyString; } // "无法解析响应"
                    Log.e(TAG, "Upload failed (server logic) for " + displayFileNameForNotification + ": " + detailedError);
                    updateNotificationProgress(appContext.getString(R.string.status_upload_failed_server_logic) + detailedError, false, 0, true);
                    return Result.failure(createOutputData(responseBodyString, detailedError, actualUploadedFilePath, null));
                }
            } else {
                String httpError = appContext.getString(R.string.error_upload_http_failed, response.code(), response.message()); // "上传失败 (HTTP %1$d): %2$s"
                Log.e(TAG, "Upload failed (HTTP error) for " + displayFileNameForNotification + ": " + httpError + " - Body: " + responseBodyString);
                updateNotificationProgress(httpError, false, 0, true);
                // Retry for server-side errors (5xx), fail for client-side (4xx) unless specific cases
                return (response.code() >= 500 && response.code() <= 599) ? Result.retry() : Result.failure(createOutputData(responseBodyString, httpError, actualUploadedFilePath, null));
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during actual file upload for " + displayFileNameForNotification + ": " + e.getMessage(), e);
            String exceptionMsg = appContext.getString(R.string.status_upload_failed_exception, e.getMessage()); // "上传异常: %s"
            updateNotificationProgress(exceptionMsg, false, 0, true);
            // For most exceptions during upload (IO, network), retry is a good strategy.
            return Result.retry();
        } finally {
            manageFloatingWindow(false, null);
            removeNotificationAfterDelay();
            if (isTempFileUsed && fileToUpload != null && fileToUpload.exists()) {
                if (fileToUpload.delete()) {
                    Log.d(TAG, "Temp file successfully deleted in finally block: " + fileToUpload.getAbsolutePath());
                } else {
                    Log.w(TAG, "Failed to delete temp file in finally block: " + fileToUpload.getAbsolutePath());
                }
            }
        }
    }

    private Data createOutputData(String serverResponse, String errorMessage, String processedFilePath, String skippedMessage) {
        Data.Builder builder = new Data.Builder();
        if (serverResponse != null) builder.putString(KEY_OUTPUT_SERVER_RESPONSE, serverResponse);
        if (errorMessage != null) builder.putString(KEY_OUTPUT_ERROR_MESSAGE, errorMessage);
        if (processedFilePath != null) builder.putString(KEY_OUTPUT_FILE_PATH_PROCESSED, processedFilePath);
        if (skippedMessage != null) builder.putString(KEY_OUTPUT_UPLOAD_SKIPPED_MESSAGE, skippedMessage); // Add skipped message

        // Always include the original input path for matching in MainActivity
        String originalInput = getInputData().getString(KEY_FILE_PATH);
        if (originalInput != null) builder.putString(KEY_FILE_PATH, originalInput);
        // Also include original file name if available, for matching
        String originalFileName = getInputData().getString(KEY_ORIGINAL_FILE_NAME);
        if (originalFileName != null) builder.putString(KEY_ORIGINAL_FILE_NAME, originalFileName);


        return builder.build();
    }
}