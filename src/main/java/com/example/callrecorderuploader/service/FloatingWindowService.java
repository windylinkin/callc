package com.example.callrecorderuploader.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.example.callrecorderuploader.R;

public class FloatingWindowService extends Service {
    private static final String TAG = "FloatingWindowService";
    public static final String ACTION_SHOW = "com.example.callrecorderuploader.service.SHOW_FLOATING_WINDOW";
    public static final String ACTION_HIDE = "com.example.callrecorderuploader.service.HIDE_FLOATING_WINDOW";
    public static final String EXTRA_MESSAGE = "extra_message";

    private WindowManager windowManager;
    private View floatingView;
    private TextView tvMessage;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        if (inflater == null) {
            Log.e(TAG, "Layout Inflater service is null.");
            stopSelf();
            return;
        }
        try {
            floatingView = inflater.inflate(R.layout.floating_upload_layout, null);
            tvMessage = floatingView.findViewById(R.id.tvFloatingMessage);
        } catch (Exception e) {
            Log.e(TAG, "Error inflating floating_upload_layout: " + e.getMessage());
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (floatingView == null) {
            Log.e(TAG, "Floating view is null in onStartCommand, stopping.");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.getAction() != null) {
            String message = intent.getStringExtra(EXTRA_MESSAGE);
            if (message == null || message.isEmpty()) {
                message = getString(R.string.default_uploading_message);
            }

            switch (intent.getAction()) {
                case ACTION_SHOW:
                    Log.d(TAG, "Action SHOW. Message: " + message);
                    showFloatingWindow(message);
                    break;
                case ACTION_HIDE:
                    Log.d(TAG, "Action HIDE.");
                    hideFloatingWindow();
                    break;
                default:
                    Log.w(TAG, "Unknown action: " + intent.getAction());
                    break;
            }
        } else {
            Log.d(TAG, "Intent or action is null in onStartCommand.");
            if (floatingView.isAttachedToWindow()) hideFloatingWindow();
        }
        return START_NOT_STICKY;
    }

    private void showFloatingWindow(String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted.");
            return;
        }

        if (floatingView.isAttachedToWindow()) {
            Log.d(TAG, "Floating window already shown, updating message.");
            tvMessage.setText(message);
            return;
        }
        tvMessage.setText(message);

        int layoutParamsType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 100;

        try {
            windowManager.addView(floatingView, params);
            Log.d(TAG, "Floating window added.");
        } catch (Exception e) {
            Log.e(TAG, "Error adding floating window: " + e.getMessage());
        }
    }

    private void hideFloatingWindow() {
        if (floatingView != null && floatingView.isAttachedToWindow()) {
            try {
                windowManager.removeView(floatingView);
                Log.d(TAG, "Floating window removed.");
            } catch (Exception e) {
                Log.e(TAG, "Error removing floating window: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "FloatingWindowService destroyed.");
        hideFloatingWindow();
    }
}
