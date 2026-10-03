package ai.moeru.airipocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * User-initiated foreground service that hosts a draggable overlay button.
 *
 * This is the MVP proof that Stage Pocket can render a persistent control above
 * other Android apps. It intentionally renders a native test view only; the AIRI
 * avatar/WidgetStage is not involved yet.
 *
 * Hardening notes:
 * - The service is only ever started from the visible MainActivity (see
 *   FloatingCompanionPlugin.start()); there is no BOOT_COMPLETED receiver and it
 *   is registered START_NOT_STICKY, so the OS never auto-starts it.
 * - start/overlay creation is idempotent: repeated starts cannot duplicate the
 *   view or the foreground notification.
 * - Overlay removal is centralized and null-safe so partial/failed initialization
 *   and double removals cannot crash.
 * - No fixed screen dimensions are assumed; positions are clamped to the current
 *   display bounds and repaired on configuration/display-size changes (foldables).
 */
public class FloatingCompanionService extends Service {
    public static final String ACTION_START = "ai.moeru.airipocket.action.FLOATING_COMPANION_START";
    public static final String ACTION_STOP = "ai.moeru.airipocket.action.FLOATING_COMPANION_STOP";

    private static final String TAG = "FloatingCompanion";
    private static final String CHANNEL_ID = "airi_floating_companion";
    private static final int NOTIFICATION_ID = 4711;
    private static final float TAP_SLOP_DP = 8f;
    private static final int BUTTON_SIZE_DP = 56;

    private static volatile boolean running = false;

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams overlayParams;
    private boolean dragging;
    private float touchStartRawX;
    private float touchStartRawY;
    private int touchStartViewX;
    private int touchStartViewY;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopCompanion();
            return START_NOT_STICKY;
        }

        // Never add the overlay without the user-granted "Appear on top" permission.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; stopping companion service");
            stopCompanion();
            return START_NOT_STICKY;
        }

        startAsForeground();

        if (overlayView == null) {
            addOverlay();
        } else {
            clampCurrentPosition();
            updateOverlay();
        }

        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        createNotificationChannel();

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, pendingIntentFlags());

        Intent stopIntent = new Intent(this, FloatingCompanionService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingIntentFlags());

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AIRI companion active")
            .setContentText("Tap the floating button to open AIRI")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build();

        // Android 14 (API 34) requires a foreground service type matching the
        // manifest's specialUse declaration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AIRI floating companion",
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Keeps the user-initiated AIRI floating companion available over other apps");
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void addOverlay() {
        try {
            WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (manager == null) {
                Log.w(TAG, "WindowManager unavailable; not adding overlay");
                return;
            }

            int size = dp(BUTTON_SIZE_DP);

            ImageView view = new ImageView(this);
            view.setImageResource(R.mipmap.ic_launcher);
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0xCCFFFFFF);
            view.setBackground(background);
            int padding = dp(6);
            view.setPadding(padding, padding, padding, padding);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size,
                size,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;

            int[] start = clampToBounds(dp(24), dp(160), size, size);
            params.x = start[0];
            params.y = start[1];

            view.setOnTouchListener(createTouchListener(params));

            manager.addView(view, params);

            windowManager = manager;
            overlayView = view;
            overlayParams = params;
        } catch (Exception error) {
            Log.e(TAG, "Failed to add floating overlay; cleaning up", error);
            removeOverlay();
            stopSelf();
        }
    }

    private View.OnTouchListener createTouchListener(WindowManager.LayoutParams params) {
        return (view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartRawX = event.getRawX();
                    touchStartRawY = event.getRawY();
                    touchStartViewX = params.x;
                    touchStartViewY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float deltaX = event.getRawX() - touchStartRawX;
                    float deltaY = event.getRawY() - touchStartRawY;
                    if (!dragging && (Math.abs(deltaX) > tapSlop() || Math.abs(deltaY) > tapSlop())) {
                        dragging = true;
                    }
                    if (dragging && overlayParams != null) {
                        overlayParams.x = touchStartViewX + (int) deltaX;
                        overlayParams.y = touchStartViewY + (int) deltaY;
                        updateOverlay();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        clampCurrentPosition();
                        updateOverlay();
                    } else {
                        openApp();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        clampCurrentPosition();
                        updateOverlay();
                    }
                    return true;
                default:
                    return false;
            }
        };
    }

    private void openApp() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception error) {
            Log.e(TAG, "Failed to open MainActivity from overlay", error);
        }
    }

    private void updateOverlay() {
        if (windowManager == null || overlayView == null || overlayParams == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "Overlay view not attached during update", error);
        } catch (Exception error) {
            Log.w(TAG, "Failed to update overlay position", error);
        }
    }

    private void clampCurrentPosition() {
        if (overlayParams == null) {
            return;
        }
        int[] clamped = clampToBounds(overlayParams.x, overlayParams.y, overlayParams.width, overlayParams.height);
        overlayParams.x = clamped[0];
        overlayParams.y = clamped[1];
    }

    private int[] clampToBounds(int x, int y, int width, int height) {
        int size = dp(BUTTON_SIZE_DP);
        int viewWidth = width > 0 ? width : size;
        int viewHeight = height > 0 ? height : size;

        Rect bounds = currentDisplayBounds();
        int maxX = Math.max(0, bounds.width() - viewWidth);
        int maxY = Math.max(0, bounds.height() - viewHeight);

        int clampedX = Math.max(0, Math.min(x, maxX));
        int clampedY = Math.max(0, Math.min(y, maxY));
        return new int[]{clampedX, clampedY};
    }

    private Rect currentDisplayBounds() {
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return manager.getCurrentWindowMetrics().getBounds();
            } catch (Exception error) {
                Log.w(TAG, "getCurrentWindowMetrics failed; falling back to legacy metrics", error);
            }
        }

        DisplayMetrics metrics = new DisplayMetrics();
        if (manager != null) {
            manager.getDefaultDisplay().getRealMetrics(metrics);
        }
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private int overlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private float tapSlop() {
        return TAP_SLOP_DP * getResources().getDisplayMetrics().density;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Fold/unfold or display-size change: keep the overlay reachable.
        if (overlayView != null) {
            clampCurrentPosition();
            updateOverlay();
        } else if (running && Settings.canDrawOverlays(this)) {
            addOverlay();
        }
    }

    private void stopCompanion() {
        removeOverlay();
        running = false;
        stopForeground(true);
        stopSelf();
    }

    private void removeOverlay() {
        View view = overlayView;
        WindowManager manager = windowManager;
        overlayView = null;
        overlayParams = null;
        windowManager = null;
        dragging = false;

        if (view != null && manager != null) {
            try {
                manager.removeView(view);
            } catch (IllegalArgumentException error) {
                Log.w(TAG, "Overlay view already removed", error);
            } catch (Exception error) {
                Log.w(TAG, "Failed to remove overlay view", error);
            }
        }
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        running = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
