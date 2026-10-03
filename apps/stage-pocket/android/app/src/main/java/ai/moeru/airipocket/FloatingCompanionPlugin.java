package ai.moeru.airipocket;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Bridge for the user-initiated floating companion MVP.
 *
 * Every operation is safe to call repeatedly: start()/stop() are idempotent and
 * all permission-dependent paths return a clean error/status instead of crashing.
 * There is no method that starts the service without an explicit JS call.
 */
@CapacitorPlugin(
    name = "FloatingCompanion",
    permissions = {
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class FloatingCompanionPlugin extends Plugin {
    @PluginMethod
    public void isSupported(PluginCall call) {
        JSObject result = new JSObject();
        result.put("supported", true);
        call.resolve(result);
    }

    @PluginMethod
    public void hasOverlayPermission(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", Settings.canDrawOverlays(getContext()));
        call.resolve(result);
    }

    @PluginMethod
    public void requestOverlayPermission(PluginCall call) {
        if (Settings.canDrawOverlays(getContext())) {
            JSObject result = new JSObject();
            result.put("granted", true);
            call.resolve(result);
            return;
        }

        try {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getContext().getPackageName())
            );
            startActivityForResult(call, intent, "overlayPermissionResult");
        } catch (Exception error) {
            call.reject("Unable to open overlay permission settings: " + error.getMessage(), "OVERLAY_SETTINGS_UNAVAILABLE");
        }
    }

    @ActivityCallback
    private void overlayPermissionResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        JSObject response = new JSObject();
        response.put("granted", Settings.canDrawOverlays(getContext()));
        call.resolve(response);
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        // POST_NOTIFICATIONS only exists on Android 13+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            JSObject result = new JSObject();
            result.put("granted", true);
            call.resolve(result);
            return;
        }

        if (getPermissionState("notifications") == PermissionState.GRANTED) {
            JSObject result = new JSObject();
            result.put("granted", true);
            call.resolve(result);
            return;
        }

        requestPermissionForAlias("notifications", call, "notificationPermissionResult");
    }

    @PermissionCallback
    private void notificationPermissionResult(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(result);
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject result = new JSObject();
        result.put("running", FloatingCompanionService.isRunning());
        call.resolve(result);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!Settings.canDrawOverlays(getContext())) {
            call.reject("Overlay permission (Appear on top) is required", "OVERLAY_PERMISSION_MISSING");
            return;
        }

        if (FloatingCompanionService.isRunning()) {
            JSObject result = new JSObject();
            result.put("started", true);
            result.put("alreadyRunning", true);
            call.resolve(result);
            return;
        }

        try {
            Intent intent = new Intent(getContext(), FloatingCompanionService.class);
            intent.setAction(FloatingCompanionService.ACTION_START);
            ContextCompat.startForegroundService(getContext(), intent);

            JSObject result = new JSObject();
            result.put("started", true);
            result.put("alreadyRunning", false);
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Failed to start floating companion: " + error.getMessage(), "START_FAILED");
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            // stopService is a no-op-safe if the service is not running.
            getContext().stopService(new Intent(getContext(), FloatingCompanionService.class));

            JSObject result = new JSObject();
            result.put("stopped", true);
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Failed to stop floating companion: " + error.getMessage(), "STOP_FAILED");
        }
    }
}
