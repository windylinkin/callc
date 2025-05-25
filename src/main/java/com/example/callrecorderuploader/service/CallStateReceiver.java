package com.example.callrecorderuploader.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager; // 确保已导入

import com.example.callrecorderuploader.MainActivity;
import com.example.callrecorderuploader.R;

public class CallStateReceiver extends BroadcastReceiver {
    private static final String TAG = "CallStateReceiver";
    private static int lastState = TelephonyManager.CALL_STATE_IDLE;
    private static boolean isIncomingCall;
    private static String savedNumber; // 通常是对方号码

    // Actions for MainActivity to know call start and end
    public static final String ACTION_CALL_STARTED = "com.example.callrecorderuploader.ACTION_CALL_STARTED";
    public static final String ACTION_CALL_ENDED = "com.example.callrecorderuploader.ACTION_CALL_ENDED";

    public static final String EXTRA_CALL_STARTED_REMOTE_NUMBER = "extra_call_started_remote_number";
    public static final String EXTRA_CALL_STARTED_APPROX_START_TIME_MS = "extra_call_started_approx_start_time_ms";
    public static final String EXTRA_CALL_ENDED_REMOTE_NUMBER = "extra_call_ended_remote_number";
    public static final String EXTRA_CALL_ENDED_APPROX_START_TIME_MS = "extra_call_ended_approx_start_time_ms";

    private static long approxCallStartTimeMs = 0; // 存储当前通话的大致开始时间 (System.currentTimeMillis())

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) {
            Log.d(TAG, "Action is null, returning.");
            return;
        }
        Log.d(TAG, "Action received: " + intent.getAction());

        if (intent.getAction().equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
            savedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            Log.d(TAG, "Outgoing call initiated to: " + savedNumber);
            isIncomingCall = false;
            // 对于呼出电话，呼叫开始时间将在状态变为OFFHOOK时更准确地设置
            // lastState 在这里可能还是 IDLE
        } else if (intent.getAction().equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
            String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            String numberFromStateChange = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            int currentState = TelephonyManager.CALL_STATE_IDLE;

            if (stateStr != null) {
                if (stateStr.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
                    currentState = TelephonyManager.CALL_STATE_OFFHOOK;
                } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
                    currentState = TelephonyManager.CALL_STATE_RINGING;
                    if (!TextUtils.isEmpty(numberFromStateChange)) {
                        savedNumber = numberFromStateChange;
                        isIncomingCall = true;
                        Log.d(TAG, "Incoming call ringing from: " + savedNumber);
                    }
                } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
                    currentState = TelephonyManager.CALL_STATE_IDLE;
                }
            }
            Log.d(TAG, "Phone state changed: " + stateStr + " (current: " + currentState + ", last: " + lastState + "). SavedNumber: " + savedNumber);
            onCallStateChanged(context, currentState, savedNumber, isIncomingCall);
        }
    }

    public void onCallStateChanged(Context context, int state, String remoteNumber, boolean incoming) {
        // 如果状态没有改变，并且不是一个需要重复评估的OFFHOOK状态，则返回
        // （允许OFFHOOK重复触发是为了确保服务在某些边缘情况下也能启动）
        if (lastState == state && state != TelephonyManager.CALL_STATE_OFFHOOK) {
            // Log.d(TAG, "State unchanged (" + state + ") or not critical for re-evaluation, returning.");
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String ownNumber1 = prefs.getString(MainActivity.KEY_PHONE_NUMBER_1, "");
        String ownSimIdentifier = !TextUtils.isEmpty(ownNumber1) ? ownNumber1 : context.getString(R.string.default_local_identifier_fallback); // 使用 strings.xml 或默认 "Local"

        boolean appRecordingEnabled = prefs.getBoolean(MainActivity.KEY_APP_RECORDING_ENABLED, false); // 使用 MainActivity 中的 Key

        Intent serviceIntent = new Intent(context, RecordingService.class);
        serviceIntent.putExtra(RecordingService.EXTRA_REMOTE_NUMBER, remoteNumber); // 这里的remoteNumber是onCallStateChanged的参数
        serviceIntent.putExtra(RecordingService.EXTRA_OWN_SIM_IDENTIFIER, ownSimIdentifier);
        serviceIntent.putExtra(RecordingService.EXTRA_IS_INCOMING, incoming);


        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                Log.d(TAG, "Call RINGING. Remote: " + remoteNumber + ", Incoming: " + incoming);
                // 当电话响铃时，如果是新呼叫（之前是IDLE），重置呼叫开始时间
                if (lastState == TelephonyManager.CALL_STATE_IDLE) {
                    approxCallStartTimeMs = 0; // 将在OFFHOOK时准确设置
                }
                // 通常不在RINGING状态启动录音，等待OFFHOOK
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                Log.d(TAG, "Call OFFHOOK. Current Remote: " + remoteNumber + ", SavedNumber for call: " + savedNumber + ", OwnID: " + ownSimIdentifier + ", Incoming: " + incoming);

                // 仅当从IDLE或RINGING转到OFFHOOK时，视为呼叫开始
                if (lastState == TelephonyManager.CALL_STATE_IDLE || lastState == TelephonyManager.CALL_STATE_RINGING) {
                    approxCallStartTimeMs = System.currentTimeMillis(); // 记录呼叫开始的准确时间戳

                    Intent callStartedIntent = new Intent(ACTION_CALL_STARTED);
                    // 'savedNumber' 应该是在 NEW_OUTGOING_CALL 或 RINGING 时捕获的对方号码
                    // 'remoteNumber' 参数可能在呼出时为空，呼入时有值
                    String activeCallRemoteNumber = TextUtils.isEmpty(savedNumber) ? remoteNumber : savedNumber;
                    if (TextUtils.isEmpty(activeCallRemoteNumber)) activeCallRemoteNumber = "Unknown";

                    callStartedIntent.putExtra(EXTRA_CALL_STARTED_REMOTE_NUMBER, activeCallRemoteNumber);
                    callStartedIntent.putExtra(EXTRA_CALL_STARTED_APPROX_START_TIME_MS, approxCallStartTimeMs);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(callStartedIntent);
                    Log.i(TAG, "Broadcast sent: ACTION_CALL_STARTED for remote: " + activeCallRemoteNumber + " at " + approxCallStartTimeMs);
                }

                serviceIntent.putExtra(RecordingService.EXTRA_CALL_STATE, "OFFHOOK");
                serviceIntent.putExtra(RecordingService.EXTRA_ACTUAL_CALL_START_TIME_MS, approxCallStartTimeMs);

                if (appRecordingEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    Log.i(TAG, "RecordingService intent sent for OFFHOOK with startTime: " + approxCallStartTimeMs);
                } else {
                    Log.i(TAG, "App recording disabled, RecordingService not started for OFFHOOK.");
                }
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                Log.d(TAG, "Call IDLE. Last state was: " + lastState + ". Remote at IDLE: " + remoteNumber + ", SavedNumber for ended call: " + savedNumber);
                // 仅当从OFFHOOK转到IDLE时，视为呼叫结束
                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    Intent callEndedIntent = new Intent(ACTION_CALL_ENDED);
                    // 使用'savedNumber'作为结束通话的对方号码，因为它是在呼叫活动期间（呼出或呼入时）设置的
                    // 'remoteNumber' 在IDLE时可能为空或不准确
                    String endedCallRemoteNumber = savedNumber;
                    if (TextUtils.isEmpty(endedCallRemoteNumber)) {
                        // 如果 savedNumber 碰巧为空（例如，一个未接来电后直接IDLE，没有经过OFFHOOK），
                        // 而 remoteNumber (来自TelephonyManager.EXTRA_INCOMING_NUMBER的残留) 有值，可以考虑使用它，
                        // 但对于正常的 OFFHOOK -> IDLE 流程，savedNumber 应该是首选。
                        // 在这里，我们主要关心的是由OFFHOOK结束的呼叫。
                        endedCallRemoteNumber = "Unknown"; // 或 remoteNumber, 但需谨慎
                        Log.w(TAG, "SavedNumber was empty at call end for OFFHOOK->IDLE. Using 'Unknown'. RemoteNumber at IDLE was: " + remoteNumber);
                    }

                    callEndedIntent.putExtra(EXTRA_CALL_ENDED_REMOTE_NUMBER, endedCallRemoteNumber);
                    callEndedIntent.putExtra(EXTRA_CALL_ENDED_APPROX_START_TIME_MS, approxCallStartTimeMs);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(callEndedIntent);
                    Log.i(TAG, "Broadcast sent: ACTION_CALL_ENDED for remote: " + endedCallRemoteNumber + " approx start: " + approxCallStartTimeMs);

                    // 重置呼叫开始时间，为下一次呼叫做准备
                    approxCallStartTimeMs = 0;
                }

                if (appRecordingEnabled && RecordingService.IS_SERVICE_RUNNING) {
                    serviceIntent.putExtra(RecordingService.EXTRA_CALL_STATE, "IDLE");
                    // 传递approxCallStartTimeMs是为了让RecordingService知道是哪个通话的IDLE
                    serviceIntent.putExtra(RecordingService.EXTRA_ACTUAL_CALL_START_TIME_MS, approxCallStartTimeMs); // 传递0或者最后一次的，取决于逻辑
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    Log.i(TAG, "RecordingService intent sent for IDLE.");
                } else {
                    Log.i(TAG, "Call IDLE, but app recording was disabled or service not running. No action for RecordingService.");
                }
                // 重置状态变量，为下一次呼叫准备
                savedNumber = null;
                isIncomingCall = false;
                break;
        }
        lastState = state;
    }
}