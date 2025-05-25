package com.example.callrecorderuploader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo; // 确保导入
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.callrecorderuploader.MainActivity;
import com.example.callrecorderuploader.R;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet; // 用于管理电话号码，避免重复
import java.util.Set;    // 用于管理电话号码
import java.util.Timer;
import java.util.TimerTask;

public class AppWebSocketClientService extends Service {
    private static final String TAG = "AppWebSocketClientSvc";
    private static final String WEBSOCKET_URI = "ws://appwebsocket.jujia618.com/ws/callbinding";
    private static final String CHANNEL_ID = "WebSocketServiceChannel";
    private static final int NOTIFICATION_ID = 90210; // 确保这个ID在您的应用中是唯一的

    public static final String ACTION_CONNECT = "com.example.callrecorderuploader.service.WS_CONNECT";
    public static final String ACTION_DISCONNECT = "com.example.callrecorderuploader.service.WS_DISCONNECT";
    public static final String ACTION_SEND_MESSAGE = "com.example.callrecorderuploader.service.WS_SEND_MESSAGE";
    public static final String ACTION_CONNECT_ON_BOOT = "com.example.callrecorderuploader.service.WS_CONNECT_ON_BOOT";

    public static final String EXTRA_MESSAGE_TO_SEND = "extra_message_to_send";
    public static final String EXTRA_PHONE_NUMBERS = "extra_phone_numbers";

    public static final String ACTION_WS_STATUS_UPDATE = "com.example.callrecorderuploader.WS_STATUS_UPDATE";
    public static final String EXTRA_WS_STATUS_MESSAGE = "extra_ws_status_message";
    public static final String ACTION_INITIATE_CALL = "com.example.callrecorderuploader.INITIATE_CALL";
    public static final String EXTRA_MIDDLE_NUMBER = "extra_middle_number";
    public static final String EXTRA_TARGET_ORIGINAL_NUMBER_FOR_CALL = "extra_target_original_number_for_call";

    private WebSocketClient mWebSocketClient;
    private Set<String> localPhoneNumbersToRegister = new HashSet<>(); // 使用Set避免重复，并方便管理
    private Handler mainHandler;
    private static String currentStatus = "未连接";
    private static boolean isActivityRunning = false;

    private Timer heartbeatTimer;
    private static final long HEARTBEAT_INTERVAL_MS = 30 * 1000;
    private static final long INITIAL_RECONNECT_DELAY_MS = 2 * 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 60 * 1000;
    private long currentReconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
    private Handler reconnectHandler;
    private Runnable reconnectRunnable;
    private boolean isManuallyDisconnected = false;
    private int connectAttempts = 0;
    private String deviceIdForRegistration;


    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        reconnectHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        deviceIdForRegistration = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Log.d(TAG, "Service Created. Device ID: " + deviceIdForRegistration);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand received, Action: " + (intent != null ? intent.getAction() : "null intent"));

        // 必须先调用 startForeground
        Notification notification = createNotification(currentStatus);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29 for foregroundServiceType argument
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                Log.d(TAG, "Service started in foreground with foregroundServiceType (API 29+).");
            } else {
                // For API 26 (Oreo) to API 28 (Pie), use startForeground without foregroundServiceType
                // For API < 26, startForeground also works without type but service behavior is different.
                startForeground(NOTIFICATION_ID, notification);
                Log.d(TAG, "Service started in foreground (API < 29).");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting foreground service in AppWebSocketClientService: " + e.getMessage(), e);
            // 如果前台服务启动失败，根据业务逻辑决定是否停止服务
            // stopSelf();
            // return START_NOT_STICKY;
        }


        if (intent != null && intent.getAction() != null) {
            isManuallyDisconnected = false; // Reset manual disconnect on any new command except explicit disconnect
            switch (intent.getAction()) {
                case ACTION_CONNECT_ON_BOOT:
                    Log.i(TAG, "ACTION_CONNECT_ON_BOOT received. Loading numbers from prefs and connecting.");
                    loadNumbersFromPrefsAndConnect();
                    break;
                case ACTION_CONNECT:
                    if (intent.hasExtra(EXTRA_PHONE_NUMBERS)) {
                        String[] numbersArray = intent.getStringArrayExtra(EXTRA_PHONE_NUMBERS);
                        if (numbersArray != null) {
                            localPhoneNumbersToRegister.clear();
                            for (String num : numbersArray) {
                                if (!TextUtils.isEmpty(num)) {
                                    localPhoneNumbersToRegister.add(num);
                                }
                            }
                        }
                        Log.i(TAG, "ACTION_CONNECT received. Registering numbers: " + localPhoneNumbersToRegister);
                    } else {
                        Log.w(TAG, "ACTION_CONNECT received without phone numbers. Will use previously loaded or saved numbers if available.");
                        // Optionally, load from prefs here if localPhoneNumbersToRegister is empty
                        if (localPhoneNumbersToRegister.isEmpty()) {
                            loadNumbersFromPrefsAndConnect(); // Try loading as a fallback
                        }
                    }
                    connectWebSocket();
                    break;
                case ACTION_DISCONNECT:
                    Log.i(TAG, "ACTION_DISCONNECT received. Manually disconnecting.");
                    disconnectWebSocket(true);
                    break;
                case ACTION_SEND_MESSAGE:
                    String messageToSend = intent.getStringExtra(EXTRA_MESSAGE_TO_SEND);
                    sendMessage(messageToSend);
                    break;
                default:
                    Log.w(TAG, "Unknown action received: " + intent.getAction());
                    if (mWebSocketClient == null || !mWebSocketClient.isOpen()) {
                        Log.d(TAG, "No active WebSocket connection, attempting to connect with stored/default numbers.");
                        connectWebSocket(); // Attempt to connect if not connected
                    }
                    break;
            }
        } else {
            Log.w(TAG, "Service restarted by system or intent/action is null. Attempting to connect using saved/current numbers.");
            if (localPhoneNumbersToRegister.isEmpty()) { // If no numbers are in memory from a previous command
                loadNumbersFromPrefsAndConnect();
            } else {
                connectWebSocket(); // Attempt to connect with numbers already in memory
            }
        }
        return START_STICKY;
    }

    private void loadNumbersFromPrefsAndConnect() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String phone1 = prefs.getString(MainActivity.KEY_PHONE_NUMBER_1, "");
        String phone2 = prefs.getString(MainActivity.KEY_PHONE_NUMBER_2, "");

        localPhoneNumbersToRegister.clear(); // Clear before loading
        if (!TextUtils.isEmpty(phone1)) localPhoneNumbersToRegister.add(phone1);
        if (!TextUtils.isEmpty(phone2)) localPhoneNumbersToRegister.add(phone2);

        if (!localPhoneNumbersToRegister.isEmpty()) {
            Log.i(TAG, "Loaded numbers from prefs for registration: " + localPhoneNumbersToRegister);
            isManuallyDisconnected = false;
            connectWebSocket();
        } else {
            Log.w(TAG, "No phone numbers in prefs. Cannot connect on boot/restart automatically.");
            updateStatusAndNotify(getString(R.string.status_websocket_no_numbers_configured)); // "号码未配置"
            // If no numbers, the service might not need to run or attempt connections.
            // stopSelf(); // Consider stopping if no configuration to connect.
        }
    }


    private synchronized void connectWebSocket() {
        if (mWebSocketClient != null && mWebSocketClient.isOpen()) {
            Log.i(TAG, "WebSocket already connected. Sending registration if needed.");
            // If already open, re-send registration in case numbers changed or server missed it.
            sendRegistrationMessage();
            return;
        }
        if (mWebSocketClient != null && mWebSocketClient.isOpen()) {
            Log.i(TAG, "WebSocket is already in the process of connecting.");
            return;
        }

        if (localPhoneNumbersToRegister.isEmpty() && TextUtils.isEmpty(deviceIdForRegistration)) {
            Log.e(TAG, "No local phone numbers or device ID available to register. Cannot connect.");
            updateStatusAndNotify(getString(R.string.status_websocket_no_identifiers)); // "无有效标识符，连接失败"
            return;
        }

        URI uri;
        try {
            uri = new URI(WEBSOCKET_URI);
        } catch (URISyntaxException e) {
            Log.e(TAG, "URISyntaxException: " + e.getMessage());
            updateStatusAndNotify(getString(R.string.status_websocket_error_uri_format)); // "连接失败: URL格式错误"
            return;
        }

        Log.i(TAG, "Attempting to connect to WebSocket: " + uri + " (Attempt: " + (++connectAttempts) + ")");
        updateStatusAndNotify(getString(R.string.status_websocket_connecting)); // "连接中..."
        updateNotificationText(getString(R.string.status_websocket_connecting));


        mWebSocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                Log.i(TAG, "WebSocket Opened successfully! Server: " + handshakedata.getHttpStatusMessage());
                currentStatus = getString(R.string.status_websocket_connected); // "已连接"
                updateStatusAndNotify(currentStatus);
                updateNotificationText(currentStatus);
                connectAttempts = 0;
                currentReconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
                stopReconnectTask();

                sendRegistrationMessage();
                startHeartbeat();
            }

            @Override
            public void onMessage(String message) {
                Log.i(TAG, "Received message from server: " + message);
                try {
                    JSONObject jsonObject = new JSONObject(message);
                    String type = jsonObject.optString("type");

                    if ("bind_intermediate_number_request".equalsIgnoreCase(type)) {
                        String middleNum = jsonObject.optString("intermediateNumber");
                        String targetNum = jsonObject.optString("targetOriginalNumber");
                        Log.d(TAG, "Received 'bind_intermediate_number_request': Middle=" + middleNum + ", Target=" + targetNum);

                        Intent intentToMain = new Intent(getApplicationContext(), MainActivity.class);
                        intentToMain.setAction(ACTION_INITIATE_CALL);
                        intentToMain.putExtra(EXTRA_MIDDLE_NUMBER, middleNum);
                        intentToMain.putExtra(EXTRA_TARGET_ORIGINAL_NUMBER_FOR_CALL, targetNum);
                        intentToMain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intentToMain);
                    } else if ("heartbeat_ack".equalsIgnoreCase(type)) {
                        Log.d(TAG, "Heartbeat acknowledged by server: " + message);
                    } else if ("server_ack".equalsIgnoreCase(type)) {
                        Log.d(TAG, "Server Acknowledged: " + jsonObject.optString("message"));
                        // Could update status if needed based on ack.
                    } else {
                        Log.w(TAG, "Received unhandled message type: " + type);
                        // Optionally broadcast to MainActivity or handle other message types
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing received JSON message: " + message, e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                Log.w(TAG, "WebSocket Closed! Code: " + code + ", Reason: '" + reason + "', Remote: " + remote);
                currentStatus = getString(R.string.status_websocket_disconnected_reason, reason != null ? reason : "N/A"); // "已断开: %s"
                updateStatusAndNotify(currentStatus);
                updateNotificationText(currentStatus);
                stopHeartbeat();
                if (!isManuallyDisconnected) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onError(Exception ex) {
                Log.e(TAG, "WebSocket Error! Message: " + ex.getMessage(), ex);
                currentStatus = getString(R.string.status_websocket_connection_error); // "连接错误"
                String detailedError = currentStatus + (ex.getMessage() != null ? " (" + ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 50)) + "...)" : "");
                updateStatusAndNotify(detailedError);
                updateNotificationText(currentStatus); // Keep notification simpler
                stopHeartbeat();
                if (!isManuallyDisconnected && (mWebSocketClient == null || !mWebSocketClient.isOpen())) {
                    // Error might not always trigger onClose, so schedule reconnect if not manually disconnected
                    // and connection is indeed not open.
                    scheduleReconnect();
                }
            }
        };
        mWebSocketClient.connect();
    }

    private void sendRegistrationMessage() {
        if (mWebSocketClient == null || !mWebSocketClient.isOpen()) {
            Log.w(TAG, "Cannot send registration message, WebSocket is not open.");
            return;
        }
        try {
            JSONObject registrationMessage = new JSONObject();
            registrationMessage.put("type", "client_hello");

            // Iterate over the Set to add phone1, phone2, etc.
            // For simplicity, if we have numbers, send the first as phone1, second as phone2
            String[] numbers = localPhoneNumbersToRegister.toArray(new String[0]);
            if (numbers.length > 0 && !TextUtils.isEmpty(numbers[0])) {
                registrationMessage.put("phone1", numbers[0]);
            }
            if (numbers.length > 1 && !TextUtils.isEmpty(numbers[1])) {
                registrationMessage.put("phone2", numbers[1]);
            }
            // Always send deviceId
            if (!TextUtils.isEmpty(deviceIdForRegistration)) {
                registrationMessage.put("deviceId", deviceIdForRegistration);
            }


            if (registrationMessage.length() > 1) { // Has more than just "type"
                mWebSocketClient.send(registrationMessage.toString());
                Log.d(TAG, "Sent registration/identification message: " + registrationMessage.toString());
            } else {
                Log.w(TAG, "No identifiers (phone numbers or deviceId) to send in registration message.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error constructing or sending registration message", e);
        }
    }


    private synchronized void scheduleReconnect() {
        if (isManuallyDisconnected) {
            Log.i(TAG, "Reconnect scheduling skipped: manually disconnected.");
            return;
        }
        stopReconnectTask();

        Log.i(TAG, "Scheduling WebSocket reconnect in " + (currentReconnectDelayMs / 1000) + " seconds.");
        String reconnectMsg = getString(R.string.status_websocket_reconnecting_in_seconds, (currentReconnectDelayMs / 1000)); // "连接中断，%1$d秒后尝试重连..."
        updateStatusAndNotify(reconnectMsg);
        updateNotificationText(getString(R.string.status_websocket_attempting_reconnect)); // "WebSocket 尝试重连..."

        reconnectRunnable = () -> {
            Log.i(TAG, "Executing scheduled reconnect task.");
            if (!isManuallyDisconnected) {
                connectWebSocket();
            }
        };
        reconnectHandler.postDelayed(reconnectRunnable, currentReconnectDelayMs);
        currentReconnectDelayMs = Math.min(currentReconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS);
    }

    private synchronized void stopReconnectTask() {
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
            Log.i(TAG, "WebSocket reconnect task stopped.");
        }
    }

    private synchronized void disconnectWebSocket(boolean manual) {
        this.isManuallyDisconnected = manual;
        stopHeartbeat();
        stopReconnectTask();

        if (mWebSocketClient != null) {
            try {
                if (mWebSocketClient.isOpen()) {
                    Log.i(TAG, "Closing WebSocket connection...");
                    mWebSocketClient.close(); // Use non-blocking close first
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception during WebSocket close(): " + e.getMessage());
            } finally {
                mWebSocketClient = null; // Release reference
            }
        }
        Log.i(TAG, "WebSocket disconnected " + (manual ? "manually." : "due to other reasons."));
        currentStatus = manual ? getString(R.string.status_websocket_manually_disconnected) : getString(R.string.status_websocket_disconnected); // "已手动断开" / "已断开"
        updateStatusAndNotify(currentStatus);
        updateNotificationText(currentStatus);

        if (manual) {
            // If manual disconnect, we might want to stop the service if no other tasks
            // stopSelf(); // Be careful with this, ensure it's the desired behavior
        }
    }


    private void sendMessage(String message) {
        if (message == null || message.isEmpty()) {
            Log.w(TAG, "Attempted to send null or empty message.");
            return;
        }
        if (mWebSocketClient != null && mWebSocketClient.isOpen()) {
            try {
                mWebSocketClient.send(message);
                Log.d(TAG, "Message sent to WebSocket server: " + message);
            } catch (Exception e) { // Catch org.java_websocket.exceptions.WebsocketNotConnectedException etc.
                Log.e(TAG, "Error sending message via WebSocket: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Cannot send message: WebSocket is not open or client is null.");
        }
    }


    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTimer = new Timer("WebSocketHeartbeatTimer"); // Give timer a name for debugging
        heartbeatTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (mWebSocketClient != null && mWebSocketClient.isOpen()) {
                    try {
                        JSONObject heartbeatMsg = new JSONObject();
                        heartbeatMsg.put("type", "heartbeat");
                        String identifier;
                        if (!localPhoneNumbersToRegister.isEmpty()) {
                            identifier = localPhoneNumbersToRegister.iterator().next(); // Send first phone as identifier
                        } else {
                            identifier = deviceIdForRegistration;
                        }
                        heartbeatMsg.put("identifier", identifier);
                        sendMessage(heartbeatMsg.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "Error constructing or sending heartbeat message", e);
                    }
                } else {
                    Log.w(TAG, "Heartbeat: WebSocket not open or client is null. Stopping heartbeat timer.");
                    stopHeartbeat();
                    if(!isManuallyDisconnected) {
                        Log.d(TAG, "Heartbeat found connection closed, scheduling reconnect.");
                        scheduleReconnect(); // If heartbeat finds connection closed, try to reconnect
                    }
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);
        Log.i(TAG, "Heartbeat timer started with interval: " + HEARTBEAT_INTERVAL_MS + "ms");
    }

    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer.purge();
            heartbeatTimer = null;
            Log.i(TAG, "Heartbeat timer stopped.");
        }
    }


    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title_websocket_service))
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotificationText(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && isServiceEffectivelyRunning()) { // Only update if service should be active
            try {
                manager.notify(NOTIFICATION_ID, createNotification(text));
            } catch (Exception e) {
                Log.e(TAG, "Error updating WebSocket notification: " + e.getMessage());
            }
        }
    }
    private boolean isServiceEffectivelyRunning() {
        // A helper to determine if the service should still be showing notifications
        // e.g., if manually disconnected and no numbers configured, maybe not.
        // For now, assume if instance exists, it's "running" in some state.
        return true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.websocket_service_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            serviceChannel.setDescription(getString(R.string.websocket_service_channel_description));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void updateStatusAndNotify(String statusMessage) {
        currentStatus = statusMessage;
        Intent intent = new Intent(ACTION_WS_STATUS_UPDATE);
        intent.putExtra(EXTRA_WS_STATUS_MESSAGE, statusMessage);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(TAG, "WS Status Broadcast: " + statusMessage);
    }

    public static String getCurrentStatus() { return currentStatus; }
    public static void setActivityRunning(boolean running) { isActivityRunning = running; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service Destroying...");
        disconnectWebSocket(true); // Treat service destruction as a manual disconnect intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        Log.i(TAG, "Service Destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Add new string resources to strings.xml:
    // R.string.status_websocket_no_identifiers -> "无有效标识符，连接失败"
    // R.string.status_websocket_error_uri_format -> "连接失败: URL格式错误"
    // R.string.status_websocket_connected -> "已连接"
    // R.string.status_websocket_disconnected_reason -> "已断开: %s"
    // R.string.status_websocket_connection_error -> "连接错误"
    // R.string.status_websocket_reconnecting_in_seconds -> "连接中断，%1$d秒后尝试重连..."
    // R.string.status_websocket_attempting_reconnect -> "WebSocket 尝试重连..."
    // R.string.status_websocket_manually_disconnected -> "已手动断开"
    // R.string.status_websocket_disconnected -> "已断开"
    // R.string.status_websocket_no_numbers_configured (already suggested) -> "号码未配置"
}