package com.argsecure.scada.rover;

import com.argsecure.mobility.api.LinkHealth;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import com.argsecure.scada.PhoenixApplication;
import java.util.WeakHashMap;

/** Apply the robot display policy to every Phoenix screen without replacing its startup. */
public final class RoverApplication extends PhoenixApplication implements Application.ActivityLifecycleCallbacks {
    private final WeakHashMap<Activity, ViewTreeObserver.OnWindowFocusChangeListener> focusListeners = new WeakHashMap<>();
    private RoverPowerPolicy power;
    private RoverVoiceSession voice;
    private RoverHardwareClient hardwareLink;
    private final RoverCoreStartup coreStartup = new RoverCoreStartup();
    private final RoverVendorDisplayPolicy vendorDisplay = new RoverVendorDisplayPolicy();
    private int visibleActivities;

    @Override public void onCreate() {
        super.onCreate(); // Preserve Phoenix database/backup recovery before screens or services start.
        com.argsecure.webserver.ArgAgentProduct.installPublicRuntime(() -> RoverPublicRuntime.snapshot(this, voice, visibleActivities > 0));
        getSharedPreferences("rastag_public_identity", MODE_PRIVATE).edit()
                .putInt("profile_version", com.argsecure.webserver.RastagPublicProfile.VERSION).apply();
        power = new RoverPowerPolicy(this);
        registerActivityLifecycleCallbacks(this);
        hardwareLink = new RoverHardwareClient(this);
        RoverPhoenixLink.start(this);
        RoverMemoryIndexWorker.get(this).kick();
        RoverDeviceAccess deviceAccess = new RoverDeviceAccess(this);
        com.argsecure.webserver.ArgAgentProduct.installDeviceTools(deviceAccess::execute);
    }

    @SuppressWarnings("deprecation")
    static void keepDisplayActive(Activity activity) {
        RoverDisplayControl.applySaved(activity);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        if (!(activity instanceof RoverActivity)) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        if (Build.VERSION.SDK_INT == 27 && "V9E".equals(Build.MODEL)) {
            // Let WindowManager account for the vendor navigation bars and IME.
            // Physical LCD pixels are not the available application width.
            WindowManager.LayoutParams bounds = activity.getWindow().getAttributes();
            bounds.width = WindowManager.LayoutParams.MATCH_PARENT;
            bounds.height = WindowManager.LayoutParams.MATCH_PARENT;
            bounds.gravity = Gravity.TOP | Gravity.LEFT; bounds.x = 0; bounds.y = 0;
            activity.getWindow().setAttributes(bounds);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        keepDisplayActive(activity);
        View decor = activity.getWindow().getDecorView();
        ViewTreeObserver.OnWindowFocusChangeListener listener = focused -> {
            vendorDisplay.onFocus(activity, focused);
            if (focused) decor.post(() -> { if (!activity.isDestroyed()) keepDisplayActive(activity); });
        };
        focusListeners.put(activity, listener);
        decor.getViewTreeObserver().addOnWindowFocusChangeListener(listener);
    }
    @Override public void onActivityResumed(Activity activity) {
        vendorDisplay.onResumed(activity);
        RoverDisplayControl.resume(activity);
        keepDisplayActive(activity);
        if (RoverVoiceSession.eligible(activity)) voiceSession(activity);
        if (voice != null) voice.onActivityResumed(activity);
    }
    @Override public void onActivityDestroyed(Activity activity) {
        vendorDisplay.onPaused(activity);
        if (voice != null) voice.onActivityDestroyed(activity);
        ViewTreeObserver.OnWindowFocusChangeListener listener = focusListeners.remove(activity);
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (listener != null && observer.isAlive()) observer.removeOnWindowFocusChangeListener(listener);
    }
    @Override public void onActivityStarted(Activity activity) {
        visibleActivities++; power.setVisible(true);
        hardwareLink.setVisible(true);
        if (activity instanceof RoverActivity) coreStartup.ensureStarted(this);
        if (RoverVoiceSession.eligible(activity)) voiceSession(activity);
        if (voice != null) voice.onActivityStarted(activity);
    }
    RoverVoiceSession voiceSession(Activity activity) {
        if (voice == null) voice = new RoverVoiceSession(activity);
        return voice;
    }
    void onCoreRunning() { coreStartup.onCoreRunning(); }
    LinkHealth.State hardwareState() { return hardwareLink.state(); }
    com.argsecure.mobility.api.LidarReading lidarReading(){return hardwareLink.lidar();}
    com.argsecure.mobility.api.MotionSnapshot motionSnapshot(){return hardwareLink.motion();}
    @Override public void onActivityPaused(Activity activity) {
        vendorDisplay.onPaused(activity);
        RoverDisplayControl.pause(activity);
        if (voice != null) voice.onActivityPaused(activity);
    }
    @Override public void onActivityStopped(Activity activity) {
        if (voice != null) voice.onActivityStopped(activity);
        visibleActivities = Math.max(0, visibleActivities - 1);
        if (visibleActivities == 0) { power.setVisible(false); hardwareLink.setVisible(false); }
    }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
