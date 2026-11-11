package com.argsecure.scada.rover;

import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.argsecure.scada.ArgActivity_Desktop;
import com.argsecure.scada.ArgActivity_PhoenixAgent;
import com.argsecure.scada.ArgActivity_ServiceSettings;
import com.argsecure.scada.PhoenixNetworkBackgroundView;
import com.argsecure.scada.PhoenixProtectionView;
import com.argsecure.scada.R;
import com.argsecure.scada.person.ArgActivity_PersonAcc;
import com.argsecure.scadalib.ArgCls_Setting;
import com.argsecure.scadalib.ArgCls_Broadcast;
import com.argsecure.scadalib.service.ArgPhoenixServiceControl;
import androidx.core.content.ContextCompat;
import com.argsecure.scadalib.ArgGlb_System;
import com.argsecure.webserver.ArgAgentProduct;
import com.argsecure.webserver.ArgAiDiagnostics;
import com.google.gson.JsonObject;

/** Same Phoenix lifecycle and desktop, with a robot surface in the same Activity. */
public final class RoverActivity extends ArgActivity_Desktop {
    private static final int AI_ACCESS = 8401;
    private static final int CAMERA_ACCESS = 8403;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RoverMood mood = new RoverMood();
    private final RoverCoreStatus core = new RoverCoreStatus();
    private final RoverAttention attention = new RoverAttention();
    private final BroadcastReceiver coreHeartbeat = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            core.observe(ArgCls_Broadcast.BR_CORE_THREAD_RUN.equals(intent.getAction()), SystemClock.elapsedRealtime());
            if (ArgCls_Broadcast.BR_CORE_THREAD_RUN.equals(intent.getAction()))
                ((RoverApplication) getApplication()).onCoreRunning();
        }
    };
    private View phoenixDesktop;
    private RoverFaceStage robotSurface;
    private RoverSettingsPanel settingsPanel;
    private Switch captionToggle;
    private Button returnToFace;
    private RoverFaceView face;
    private RoverHearingIndicator hearingIndicator;
    private RoverStatusBadge activityBadge;
    private RoverAmbientWeather ambientWeather;
    private RoverPoseBadge poseBadge;
    private RoverPerformanceBadge performanceBadge;
    private RoverPerformanceMonitor performanceMonitor;
    private RoverHardwareBadge hardwareBadge;
    private RoverLidarCard lidarCard;
    private RoverMotionBadge motionBadge;
    private RoverCameraPreview frontPreview, rearPreview;
    private android.app.Dialog poseDialog;
    private android.app.Dialog faceLearningDialog,faceEnrollmentDialog;
    private android.app.Dialog visualLegendDialog;
    private android.app.Dialog audioLevelsDialog;
    private android.app.Dialog locationDialog;
    private boolean robotQuestion;
    private String questionOutputTail = "";
    private float audibleOutput;
    private TextView status, subtitle;
    private RoverVoiceSession speech;
    private RoverLiveVoice.Listener voiceListener;
    private android.app.Dialog voiceStatusDialog;
    private android.app.Dialog researchDialog;
    private android.app.Dialog conversationDialog, voiceChoiceDialog;
    private boolean researchLinkVisible;
    private String inputTranscript = "", outputTranscript = "";
    private boolean showVoiceCaptions;
    private RoverLiveVoice.State lastVoiceState = RoverLiveVoice.State.OFF;
    private long voiceHintUntil;
    private boolean showingFace = true;
    private boolean resumed;
    private String aiStatus = "";
    private Button visionToggle;
    private Button handsFreeToggle;
    private Button conversationAction;
    private boolean handsFreeRequested;
    private boolean visionEnabled, visionFailed, cameraPermissionPending;
    private long lastVisionAt = -1;
    private int faceCount;
    private String visionErrorMessage = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            boolean alarm = ArgGlb_System.SYSTEMSTATE == ArgCls_Setting.STATE_ACCIDENT;
            face.setExpression(mood.current(alarm, SystemClock.elapsedRealtime()));
            RoverCoreStatus.State coreState = core.state(SystemClock.elapsedRealtime());
            String statusLine = (coreState == RoverCoreStatus.State.RUNNING ? "هستهٔ فونیکس فعال"
                    : coreState == RoverCoreStatus.State.STOPPED ? "پردازش هسته متوقف است" : "در انتظار هستهٔ فونیکس")
                    + "   ·   " + aiStatus;
            if (!statusLine.contentEquals(status.getText())) status.setText(statusLine);
            if (visionEnabled) face.lookAt(attention.x(SystemClock.elapsedRealtime()), attention.y(SystemClock.elapsedRealtime()));
            refreshVisionLabel();
            refreshResearchLink();
            refreshHearingIndicator();
            refreshVisualStatus();
            hardwareBadge.setState(((RoverApplication) getApplication()).hardwareState());
            if(lidarCard!=null)lidarCard.display(((RoverApplication)getApplication()).lidarReading());
            if(motionBadge!=null)motionBadge.display(((RoverApplication)getApplication()).motionSnapshot());
            if(poseBadge!=null) poseBadge.update(RoverPoseSensors.get(RoverActivity.this).snapshot(getWindowManager().getDefaultDisplay().getRotation()));
            if(performanceBadge!=null) performanceBadge.display(performanceMonitor.current());
            if (showingFace) pauseHiddenAnimations();
            main.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); // Settings, providers, model load, alerts and all original services.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout content = findViewById(android.R.id.content);
        phoenixDesktop = content.getChildAt(0);
        buildRobotSurface();
        content.addView(robotSurface, new FrameLayout.LayoutParams(-1, -1));
        returnToFace = button("چهرهٔ رستاگ");
        returnToFace.setId(R.id.rover_return_face);
        FrameLayout.LayoutParams back = new FrameLayout.LayoutParams(dp(142), dp(44), Gravity.TOP | Gravity.LEFT);
        content.addView(returnToFace, back);
        content.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> positionReturnButton(content));
        returnToFace.setOnClickListener(v -> showFace(true));
        setupVoice();
        View desktopVoice = findViewById(R.id.desktop_imgbtn_voice);
        if (desktopVoice != null) {
            // The robot edition has one microphone path, including its Phoenix desktop shortcut.
            desktopVoice.setOnTouchListener(null);
            desktopVoice.setOnClickListener(v -> {
                handsFreeRequested = true;
                getSharedPreferences("rover_voice", MODE_PRIVATE).edit().putBoolean("automatic", true).apply();
                showFace(true);
            });
        }
        visionEnabled = getSharedPreferences("rover_perception", MODE_PRIVATE).getBoolean("enabled", false);
        IntentFilter heartbeat = new IntentFilter(ArgCls_Broadcast.BR_CORE_THREAD_RUN);
        heartbeat.addAction(ArgCls_Broadcast.BR_CORE_THREAD_STOP);
        ContextCompat.registerReceiver(this, coreHeartbeat, heartbeat, ArgPhoenixServiceControl.PERMISSION,
                null, ContextCompat.RECEIVER_EXPORTED);
        showFace(state == null || state.getBoolean("rover.face", true));
    }

    private void buildRobotSurface() {
        robotSurface = new RoverFaceStage(this);
        robotSurface.setId(R.id.rover_surface);
        face = new RoverFaceView(this);
        face.setId(R.id.rover_face);
        face.setSoundEffectsEnabled(false);
        face.setOnClickListener(v -> {
            mood.touch(SystemClock.elapsedRealtime());
            face.setExpression(mood.current(ArgGlb_System.SYSTEMSTATE == ArgCls_Setting.STATE_ACCIDENT, SystemClock.elapsedRealtime()));
        });
        subtitle = text("", 17, 0xFFC1D9E4);
        subtitle.setMaxLines(2); subtitle.setGravity(Gravity.CENTER);
        subtitle.setTag("rover_response_caption"); subtitle.setVisibility(View.GONE);
        subtitle.setSoundEffectsEnabled(false);
        subtitle.setOnClickListener(v -> {
            if (RoverResearchPresentation.hasResult() && (researchDialog == null || !researchDialog.isShowing()))
                researchDialog = RoverResearchPresentation.show(this);
        });
        subtitle.setClickable(false);
        ImageButton settings = new ImageButton(this);
        settings.setTag("rover_settings"); settings.setContentDescription("تنظیمات رستاگ");
        settings.setImageResource(R.drawable.rover_ic_settings);
        settings.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        settings.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable background = new GradientDrawable(); background.setColor(0xB00A1B2A);
        background.setCornerRadius(dp(24)); settings.setBackground(background);
        settings.setSoundEffectsEnabled(false); settings.setOnClickListener(v -> openSettings());
        hearingIndicator = new RoverHearingIndicator(this);
        hearingIndicator.setOnClickListener(v -> {
            if (voiceStatusDialog == null || !voiceStatusDialog.isShowing())
                voiceStatusDialog = RoverVoiceStatusDialog.show(this);
        });
        robotSurface.setLayers(face, subtitle, settings, hearingIndicator);
        hardwareBadge = new RoverHardwareBadge(this);
        hardwareBadge.setOnClickListener(v -> openMapping());
        robotSurface.setHardwareLayer(hardwareBadge);
        activityBadge = new RoverStatusBadge(this);
        activityBadge.setTag("rover_activity_status"); activityBadge.setSoundEffectsEnabled(false);
        activityBadge.setOnClickListener(v -> {
            if (visualLegendDialog == null || !visualLegendDialog.isShowing()) visualLegendDialog = RoverVisualLegend.show(this);
        });
        robotSurface.setStatusLayer(activityBadge);
        RoverAmbientCard clock = new RoverAmbientCard(this, true), weather = new RoverAmbientCard(this, false);
        robotSurface.setAmbientLayers(clock, weather);
        ambientWeather = new RoverAmbientWeather(this, clock, weather);
        poseBadge=new RoverPoseBadge(this);
        performanceMonitor=new RoverPerformanceMonitor(this);
        performanceBadge=new RoverPerformanceBadge(this);
        LinearLayout deviceStatus=new LinearLayout(this);
        deviceStatus.setTag("rover_device_status");deviceStatus.setOrientation(LinearLayout.HORIZONTAL);
        deviceStatus.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        deviceStatus.addView(poseBadge,new LinearLayout.LayoutParams(0,-1,2));
        LinearLayout.LayoutParams performanceSize=new LinearLayout.LayoutParams(0,-1,1);performanceSize.leftMargin=dp(6);
        deviceStatus.addView(performanceBadge,performanceSize);robotSurface.setPoseLayer(deviceStatus);
        poseBadge.setOnClickListener(v->openPose());
        frontPreview = new RoverCameraPreview(this, "دوربین جلو", "rover_front_preview");
        rearPreview = new RoverCameraPreview(this, "دوربین عقب", "rover_rear_preview");
        robotSurface.setCameraLayers(rearPreview, frontPreview);
        frontPreview.setTargetListener(this::syncVision); rearPreview.setTargetListener(this::syncVision);
        LinearLayout navigation=new LinearLayout(this);navigation.setOrientation(LinearLayout.VERTICAL);
        lidarCard=new RoverLidarCard(this);navigation.addView(lidarCard,new LinearLayout.LayoutParams(-1,0,1));
        motionBadge=new RoverMotionBadge(this);navigation.addView(motionBadge,new LinearLayout.LayoutParams(-1,dp(64)));
        motionBadge.setOnClickListener(v->openMotion());robotSurface.setLidarLayer(navigation);
        lidarCard.setOnClickListener(v->openLidar());
        buildSettings();
    }

    private void openSettings() {
        refreshCaptionPreference(); refreshVoiceLabel(); refreshVisionLabel();
        captionToggle.setChecked(showVoiceCaptions);
        if (!settingsPanel.isShowing()) settingsPanel.show();
    }

    private void openMapping() {
        try { startActivity(new Intent().setComponent(new android.content.ComponentName(
                "com.argsecure.rastagmobility","com.argsecure.rastagmobility.MappingActivity"))); }
        catch (android.content.ActivityNotFoundException | SecurityException e) {
            android.widget.Toast.makeText(this,"برنامهٔ حرکت و نقشه در دسترس نیست",android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    private void openLidar(){
        try{startActivity(new Intent().setComponent(new android.content.ComponentName(
                "com.argsecure.rastagmobility","com.argsecure.rastagmobility.LidarActivity")));}
        catch(android.content.ActivityNotFoundException|SecurityException e){
            Toast.makeText(this,"نمایش لیدار در دسترس نیست",Toast.LENGTH_SHORT).show();
        }
    }
    private void openMotion(){
        try{startActivity(new Intent().setComponent(new android.content.ComponentName(
                "com.argsecure.rastagmobility","com.argsecure.rastagmobility.MotionActivity")));}
        catch(android.content.ActivityNotFoundException|SecurityException e){Toast.makeText(this,"کنترل حرکت در دسترس نیست",Toast.LENGTH_SHORT).show();}
    }

    private void buildSettings() {
        settingsPanel = new RoverSettingsPanel(this);
        settingsPanel.setOnDismissListener(ignored -> refreshCaptionPreference());
        settingsPanel.section("حرکت و نقشه");
        action(settingsPanel.row(),"کنترل حرکت و کالیبراسیون",View.NO_ID).setOnClickListener(v->{settingsPanel.dismiss();openMotion();});
        action(settingsPanel.row(), "تعریف و مدیریت نقشه‌ها", View.NO_ID).setOnClickListener(v -> {
            settingsPanel.dismiss();openMapping();
        });
        settingsPanel.section("گفت‌وگو و صدا");
        LinearLayout actions = settingsPanel.row();
        handsFreeToggle = action(actions, "شنیدن رستاگ: خاموش", View.NO_ID);
        handsFreeToggle.setTag("rover_hands_free_toggle");
        handsFreeToggle.setOnClickListener(v -> {
            handsFreeRequested = !handsFreeRequested;
            getSharedPreferences("rover_voice", MODE_PRIVATE).edit().putBoolean("automatic", handsFreeRequested).apply();
            syncVoice();
        });
        conversationAction = action(actions, "بگو «رستاگ…»", R.id.rover_voice);
        conversationAction.setOnClickListener(v -> {
            if (speech != null && speech.isConversationActive()) {
                speech.endConversation();
                return;
            }
            handsFreeRequested = true;
            getSharedPreferences("rover_voice", MODE_PRIVATE).edit().putBoolean("automatic", true).apply();
            syncVoice();
            settingsPanel.showFeedback("بگو «رستاگ سلام»؛ بعد از پاسخ می‌توانی بدون تکرار نامم ادامه بدهی");
        });
        Button voiceStatus = action(actions, "وضعیت شنیدن", View.NO_ID);
        voiceStatus.setTag("rover_voice_status");
        voiceStatus.setOnClickListener(v -> {
            if (voiceStatusDialog == null || !voiceStatusDialog.isShowing())
                voiceStatusDialog = RoverVoiceStatusDialog.show(this);
        });
        LinearLayout history = settingsPanel.row();
        action(history, "موقعیت و هواشناسی", View.NO_ID).setOnClickListener(v -> {
            if (locationDialog == null || !locationDialog.isShowing()) locationDialog = RoverLocationDialog.show(this);
        });
        action(history, "صدا و میکروفون", View.NO_ID).setOnClickListener(v -> {
            if (audioLevelsDialog == null || !audioLevelsDialog.isShowing()) audioLevelsDialog = RoverAudioLevelsDialog.show(this);
        });
        action(history, "صدای رستاگ", View.NO_ID).setOnClickListener(v -> {
            if (voiceChoiceDialog == null || !voiceChoiceDialog.isShowing()) voiceChoiceDialog = RoverVoiceChoiceDialog.show(this);
        });
        action(history, "گفت‌وگوی اخیر", View.NO_ID).setOnClickListener(v -> {
            if (conversationDialog == null || !conversationDialog.isShowing()) conversationDialog = RoverConversationDialog.show(this);
        });
        action(history, "منابع پاسخ", View.NO_ID).setOnClickListener(v -> {
            if (researchDialog == null || !researchDialog.isShowing()) researchDialog = RoverResearchPresentation.show(this);
        });
        if (RoverDevelopmentBridge.enabled(this)) {
            LinearLayout observerTools = settingsPanel.row();
            action(observerTools, "ناظر گفت‌وگو و کدنویسی", View.NO_ID).setOnClickListener(v -> {
                com.google.gson.JsonObject report = RoverConversationObserver.status();
                String message = "گفت‌وگوهای پذیرفته‌شده برای ارزیابی کیفیت و استخراج درخواست توسعه بررسی می‌شوند.\n"
                        + "تحلیل‌شده: " + report.get("analyzed").getAsInt() + "   تحویل‌شده: " + report.get("delivered").getAsInt()
                        + "\nمنتظر: " + report.get("pending").getAsInt() + "   درخواست کدنویسی ثبت‌شده: " + report.get("coding_queued").getAsInt()
                        + (report.get("last_failure").getAsString().isEmpty() ? "" : "\nتحلیل یا تحویل اخیر خطا داشته و دوباره بررسی می‌شود.")
                        + "\nفایل صدای محیط ذخیره نمی‌شود.";
                new android.app.AlertDialog.Builder(this).setTitle("ناظر گفت‌وگو").setMessage(message)
                        .setPositiveButton("بستن", null).show();
            });
        }
        settingsPanel.section("دیدن و حافظه");
        LinearLayout faceTools=settingsPanel.row();
        action(faceTools,"یادگیری چهره‌ها",View.NO_ID).setOnClickListener(v->{settingsPanel.dismiss();faceLearningDialog=RoverFaceLearningDialog.show(this);});
        action(faceTools,"ثبت و مدیریت چهره‌ها",View.NO_ID).setOnClickListener(v->{settingsPanel.dismiss();faceEnrollmentDialog=RoverFaceEnrollmentDialog.show(this);});
        LinearLayout poseTools=settingsPanel.row();
        action(poseTools,"جهت، شیب و کالیبراسیون",View.NO_ID).setOnClickListener(v->openPose());
        action(poseTools,"عامل‌های تصویر",View.NO_ID).setOnClickListener(v->{
            long now=SystemClock.elapsedRealtime();StringBuilder message=new StringBuilder();
            for(int id=0;id<2;id++)message.append(id==0?"دوربین جلو: ":"دوربین عقب: ")
                    .append(RoverCameraObservations.LIVE.current(id,now).isEmpty()?"در انتظار تحلیل تازه":"تحلیل پیوسته فعال")
                    .append(!RoverSceneAgent.enabled(this)?"؛ تحلیل آنلاین خاموش":RoverSceneObservations.LIVE.current(id,now).isEmpty()?"؛ تحلیل پیشرفته در انتظار نتیجه":"؛ تحلیل پیشرفته آماده")
                    .append("\n");
            message.append("\nدر تحلیل آنلاین، عکس‌های نمونه به OpenAI ارسال می‌شوند. تطبیق چهره روی دستگاه انجام می‌شود.");
            new android.app.AlertDialog.Builder(this).setTitle("عامل‌های بررسی تصویر").setMessage(message.toString()).setPositiveButton("بستن",null).show();
        });
        LinearLayout controls = settingsPanel.row();
        Button onlineScene=action(controls,RoverSceneAgent.enabled(this)?"تحلیل تصویری آنلاین: روشن":"تحلیل تصویری آنلاین: خاموش",View.NO_ID);
        onlineScene.setOnClickListener(v->{boolean enabled=!RoverSceneAgent.enabled(this);RoverSceneAgent.enabled(this,enabled);onlineScene.setText(enabled?"تحلیل تصویری آنلاین: روشن":"تحلیل تصویری آنلاین: خاموش");settingsPanel.showFeedback(enabled?"عکس‌های نمونه برای تحلیل صحنه به OpenAI ارسال می‌شوند؛ شناسایی چهره محلی ادامه دارد.":"ارسال تصویر متوقف شد؛ شناسایی محلی چهره و اشیا ادامه دارد.");});
        onlineScene.setContentDescription("تحلیل تصویری آنلاین؛ عکس‌های نمونه برای بررسی صحنه به OpenAI ارسال می‌شوند. شناسایی چهره روی دستگاه انجام می‌شود.");
        visionToggle = action(controls, "دیدن: خاموش", R.id.rover_vision_toggle);
        visionToggle.setOnClickListener(v -> {
            visionEnabled = !visionEnabled; visionFailed = false;
            getSharedPreferences("rover_perception", MODE_PRIVATE).edit().putBoolean("enabled", visionEnabled).apply();
            syncVision();
        });
        action(controls, "دوربین", R.id.rover_vision).setOnClickListener(v -> {
            settingsPanel.dismiss(); startActivity(new Intent(this, RoverVisionActivity.class));
        });
        action(controls, "حافظه و دانش", View.NO_ID).setOnClickListener(v -> {
            if (speech != null) speech.endConversation();
            settingsPanel.dismiss(); startActivity(new Intent(this, RoverKnowledgeActivity.class));
        });
        Button teaching = action(controls, "آموزش مدیر", View.NO_ID); teaching.setTag("rover_teaching_open");
        teaching.setOnClickListener(v -> {
            if (speech != null) speech.endConversation();
            settingsPanel.dismiss(); startActivity(new Intent(this, RoverTeachingActivity.class));
        });
        settingsPanel.section("فونیکس و نمایشگر");
        LinearLayout capabilities = settingsPanel.row();
        action(capabilities, "رفتار و وضعیت قابلیت‌ها", View.NO_ID).setOnClickListener(v -> RoverAgentStatusDialog.show(this));
        LinearLayout system = settingsPanel.row();
        action(system, "فونیکس", R.id.rover_phoenix).setOnClickListener(v -> { settingsPanel.dismiss(); showFace(false); });
        action(system, "گفت‌وگوی فونیکس", R.id.rover_chat).setOnClickListener(v -> {
            if (agentReady()) { settingsPanel.dismiss(); startActivity(new Intent(this, ArgActivity_PhoenixAgent.class)); }
        });
        action(system, "هوش و صدا", R.id.rover_ai_settings).setOnClickListener(v -> {
            settingsPanel.dismiss();
            Intent access = new Intent(this, ArgActivity_PersonAcc.class);
            access.putExtra(ArgActivity_PersonAcc.DATA_ACCESS_TYPE, ArgActivity_PersonAcc.ACC_TYPE_ACC);
            startActivityForResult(access, AI_ACCESS);
        });
        LinearLayout display = settingsPanel.row();
        Button calibrate = action(display, "تنظیم محدودهٔ نمایشگر", View.NO_ID);
        calibrate.setTag("rover_display_calibration");
        calibrate.setOnClickListener(v -> { settingsPanel.dismiss(); startActivity(new Intent(this, RoverDisplayCalibrationActivity.class)); });
        captionToggle = new Switch(this); captionToggle.setText("نمایش متن گفت‌وگو روی چهره");
        captionToggle.setTag("rover_caption_toggle"); captionToggle.setTextColor(0xFFC1D9E4);
        captionToggle.setSoundEffectsEnabled(false); captionToggle.setTextSize(14);
        captionToggle.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("rover_voice", MODE_PRIVATE).edit().putBoolean(RoverVoiceStatusDialog.CAPTIONS_KEY, checked).apply();
            refreshCaptionPreference();
        });
        display.addView(captionToggle, new LinearLayout.LayoutParams(0, dp(56), 2));
        status = text("", 13, 0xFF83A8BB); status.setGravity(Gravity.CENTER); status.setMaxLines(3);
        settingsPanel.body.addView(status, new LinearLayout.LayoutParams(-1, -2));
    }

    private boolean agentReady() {
        try { ArgAgentProduct.requireReady(this); return true; }
        catch (IllegalStateException error) {
            settingsPanel.showFeedback(error.getMessage()); mood.error(SystemClock.elapsedRealtime()); return false;
        }
    }

    private void setupVoice() {
        speech = RoverVoiceSession.get(this);
        voiceListener = new RoverLiveVoice.Listener() {
            public void onWake() {
                robotQuestion = false; questionOutputTail = "";
                hearingIndicator.clearNotice();
                inputTranscript = ""; outputTranscript = ""; voiceHintUntil = 0;
                mood.voice(RoverMood.Expression.LISTENING);
                mood.wake(SystemClock.elapsedRealtime());
                face.setSpeechLevel(0); face.setAttention(true, false);
                face.setExpression(mood.current(ArgGlb_System.SYSTEMSTATE == ArgCls_Setting.STATE_ACCIDENT, SystemClock.elapsedRealtime()));
                subtitle.setText("شنیدمت؛ گوش می‌دهم");
            }
            public void onState(RoverLiveVoice.State state, String message) {
                if (state == RoverLiveVoice.State.LOCAL_WAKE || state == RoverLiveVoice.State.CLOSING
                        || state == RoverLiveVoice.State.OFF || state == RoverLiveVoice.State.INACTIVE
                        || state == RoverLiveVoice.State.LIVE && lastVoiceState != RoverLiveVoice.State.LIVE) {
                    robotQuestion = false; questionOutputTail = "";
                }
                if (state == RoverLiveVoice.State.CONNECTING || state == RoverLiveVoice.State.LIVE
                        || state == RoverLiveVoice.State.ATTENTIVE) hearingIndicator.clearNotice();
                updateVoiceExpression(state, 0);
                if (state == RoverLiveVoice.State.CONNECTING
                        || state == RoverLiveVoice.State.LIVE && lastVoiceState == RoverLiveVoice.State.ATTENTIVE) {
                    inputTranscript = ""; outputTranscript = "";
                }
                lastVoiceState = state;
                if (ambientWeather != null) ambientWeather.setConversationBusy(state == RoverLiveVoice.State.CONNECTING
                        || state == RoverLiveVoice.State.LIVE || state == RoverLiveVoice.State.RESPONDING
                        || state == RoverLiveVoice.State.ATTENTIVE);
                boolean preserveHint = SystemClock.elapsedRealtime() < voiceHintUntil
                        && (state == RoverLiveVoice.State.CLOSING || state == RoverLiveVoice.State.LOCAL_WAKE);
                if (!preserveHint && state != RoverLiveVoice.State.DESTROYED) {
                    if (showVoiceCaptions && !outputTranscript.isEmpty()
                            && (state == RoverLiveVoice.State.ATTENTIVE || state == RoverLiveVoice.State.RESPONDING))
                        subtitle.setText(outputTranscript);
                    else subtitle.setText(voiceStateCaption(state, message));
                }
                refreshVoiceLabel();
                refreshHearingIndicator();
                refreshVisualStatus();
            }
            public void onWakeRejected(String fragment) {
                hearingIndicator.notice("نام تشخیص داده نشد", "دوباره بگو: رستاگ", false, SystemClock.elapsedRealtime());
                refreshHearingIndicator();
                showVoiceHint("نام تشخیص داده نشد؛ با صدای معمولی بگویید «رَستاگ سلام». وضعیت را در «وضعیت شنیدن» ببینید",
                        "نشنیدم؛ دوباره بگو");
                mood.error(SystemClock.elapsedRealtime());
            }
            public void onInputTranscript(String delta) {
                robotQuestion = false; questionOutputTail = "";
                inputTranscript = transcriptTail(inputTranscript + delta);
                if (showVoiceCaptions) subtitle.setText("«" + inputTranscript + "»");
            }
            public void onOutputTranscript(String delta) {
                questionOutputTail = transcriptTail(questionOutputTail + delta);
                robotQuestion = RoverVisualStatus.endsWithQuestion(questionOutputTail);
                refreshVisualStatus();
                outputTranscript = transcriptTail(outputTranscript + delta);
                if (showVoiceCaptions) subtitle.setText(outputTranscript);
            }
            public void onError(String message) {
                hearingIndicator.notice("خطای صدا", message, true, SystemClock.elapsedRealtime());
                refreshHearingIndicator();
                showVoiceHint(message, "مشکلی پیش آمد؛ دوباره صدایم کن");
                mood.error(SystemClock.elapsedRealtime());
            }
            public void onNotice(String message) {
                hearingIndicator.notice("پاسخ درخواست", message, false, SystemClock.elapsedRealtime());
                refreshHearingIndicator();
                if (showVoiceCaptions) subtitle.setText(message);
            }
            public void onLevel(float input, float output) {
                audibleOutput = output;
                face.setSpeechLevel(output);
                face.setInputLevel(input);
                RoverLiveVoice.State state = speech == null ? RoverLiveVoice.State.OFF : speech.getState();
                updateVoiceExpression(state, output);
            }
            public void onEnabledChanged(boolean enabled) {
                handsFreeRequested = enabled;
                refreshVoiceLabel();
            }
        };
        speech.addListener(voiceListener);
        handsFreeRequested = speech.isEnabled();
    }

    private void updateVoiceExpression(RoverLiveVoice.State state, float output) {
        face.setAttention(speech != null && speech.isConversationActive(), state == RoverLiveVoice.State.ATTENTIVE);
        mood.voice(output > 0 ? RoverMood.Expression.SPEAKING
                : state == RoverLiveVoice.State.ATTENTIVE ? RoverMood.Expression.ATTENTIVE
                : speech != null && speech.isWaitingForResponse() ? RoverMood.Expression.THINKING
                : state == RoverLiveVoice.State.LIVE ? RoverMood.Expression.LISTENING
                : state == RoverLiveVoice.State.PREPARING ? RoverMood.Expression.BOOTING
                : state == RoverLiveVoice.State.CONNECTING ? RoverMood.Expression.CONNECTING
                : state == RoverLiveVoice.State.OFF || state == RoverLiveVoice.State.INACTIVE
                ? RoverMood.Expression.RESTING : RoverMood.Expression.IDLE);
        face.setExpression(mood.current(ArgGlb_System.SYSTEMSTATE == ArgCls_Setting.STATE_ACCIDENT, SystemClock.elapsedRealtime()));
    }

    private void refreshHearingIndicator() {
        if (resumed && showingFace && speech != null && hearingIndicator != null)
            hearingIndicator.update(speech.getHearingStatus(), SystemClock.elapsedRealtime());
    }

    private void refreshVisualStatus() {
        if (!resumed || !showingFace || speech == null || activityBadge == null) return;
        RoverLiveVoice.HearingStatus hearing = speech.getHearingStatus();
        boolean alarm = ArgGlb_System.SYSTEMSTATE == ArgCls_Setting.STATE_ACCIDENT;
        RoverVisualStatus value = RoverBehaviorPolicy.get().decide(hearing, speech.getVisualWorkStatus(), robotQuestion, audibleOutput > 0,
                mood.current(false, SystemClock.elapsedRealtime()) == RoverMood.Expression.CONFUSED, alarm, SystemClock.elapsedRealtime());
        activityBadge.setState(value); face.setVisualStatus(value);
    }

    private String voiceStateCaption(RoverLiveVoice.State state, String message) {
        if (showVoiceCaptions) return RoverVoiceStatusDialog.stateMessage(state, message);
        switch (state) {
            case OFF: return "شنیدن خاموش است";
            case INACTIVE: return "فعلاً استراحت می‌کنم";
            case PERMISSION: return "مجوز میکروفون لازم است";
            case PREPARING: return "دارم آماده می‌شوم";
            case LOCAL_WAKE: return "صدایم کن: رستاگ";
            case CALIBRATING: return "گوش می‌دهم";
            case CONNECTING: return "شنیدمت؛ یک لحظه";
            case LIVE: return "گوش می‌دهم";
            case RESPONDING: return "رستاگ پاسخ می‌دهد";
            case ATTENTIVE: return "حواسم به توست؛ ادامه بده";
            case CLOSING: return "گفت‌وگو تمام شد";
            case ERROR: return "مشکلی پیش آمد؛ دوباره صدایم کن";
            default: return "";
        }
    }

    private void showVoiceHint(String message, String brief) {
        voiceHintUntil = SystemClock.elapsedRealtime() + 5000;
        subtitle.setText(showVoiceCaptions ? message : brief);
        main.postDelayed(() -> {
            if (speech != null && SystemClock.elapsedRealtime() >= voiceHintUntil
                    && speech.getState() == RoverLiveVoice.State.LOCAL_WAKE)
                subtitle.setText(voiceStateCaption(speech.getState(), speech.getStateMessage()));
        }, 5100);
    }

    private void syncVoice() {
        if (speech == null) return;
        if (speech.isEnabled() != handsFreeRequested) speech.setEnabled(handsFreeRequested);
        refreshVoiceLabel();
    }

    private void refreshVoiceLabel() {
        if (handsFreeToggle == null) return;
        boolean conversation = speech != null && speech.isConversationActive();
        if (conversationAction != null) {
            conversationAction.setText(conversation ? "پایان گفت‌وگو" : "بگو «رستاگ…»");
            conversationAction.setTextColor(conversation ? 0xFFFFD1A4 : 0xFF91E9FF);
            conversationAction.setContentDescription(conversation ? "پایان گفت‌وگو؛ شنیدن محلی نام روشن می‌ماند" : "راهنمای شروع گفت‌وگو با رستاگ");
        }
        String label = "شنیدن رستاگ: خاموش";
        if (handsFreeRequested) {
            RoverLiveVoice.State state = speech == null ? RoverLiveVoice.State.OFF : speech.getState();
            label = state == RoverLiveVoice.State.LOCAL_WAKE ? "محلی: منتظر رستاگ"
                    : state == RoverLiveVoice.State.CONNECTING ? "صدا: در حال اتصال"
                    : state == RoverLiveVoice.State.LIVE ? "صدای زنده: گوش می‌دهم"
                    : state == RoverLiveVoice.State.ATTENTIVE ? "حواسم به توست"
                    : state == RoverLiveVoice.State.RESPONDING ? "صدای زنده: پاسخ"
                    : state == RoverLiveVoice.State.CLOSING ? "صدا: پایان گفت‌وگو"
                    : state == RoverLiveVoice.State.ERROR ? "شنیدن: نیازمند بررسی" : "شنیدن رستاگ: روشن";
        }
        handsFreeToggle.setText(label);
        handsFreeToggle.setTextColor(handsFreeRequested ? 0xFF61F2CE : 0xFF91E9FF);
    }

    private void refreshResearchLink() {
        boolean available = showVoiceCaptions && RoverResearchPresentation.hasResult();
        if (subtitle == null || researchLinkVisible == available) return;
        researchLinkVisible = available;
        subtitle.setClickable(available); subtitle.setFocusable(available);
        subtitle.setPaintFlags(available ? subtitle.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG
                : subtitle.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        subtitle.setTextColor(available ? 0xFF91E9FF : 0xFFC1D9E4);
    }

    private void refreshCaptionPreference() {
        boolean enabled = getSharedPreferences("rover_voice", MODE_PRIVATE)
                .getBoolean(RoverVoiceStatusDialog.CAPTIONS_KEY, false);
        if (subtitle != null) subtitle.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (showVoiceCaptions == enabled) return;
        showVoiceCaptions = enabled;
        if (enabled && !outputTranscript.isEmpty()
                && (lastVoiceState == RoverLiveVoice.State.RESPONDING || lastVoiceState == RoverLiveVoice.State.ATTENTIVE))
            subtitle.setText(outputTranscript);
        else if (enabled && !inputTranscript.isEmpty() && lastVoiceState == RoverLiveVoice.State.LIVE)
            subtitle.setText("«" + inputTranscript + "»");
        else if (speech != null) subtitle.setText(voiceStateCaption(speech.getState(), speech.getStateMessage()));
        refreshResearchLink();
    }

    private static String transcriptTail(String text) {
        return text.length() <= 420 ? text : "…" + text.substring(text.length() - 419);
    }

    private void positionReturnButton(FrameLayout content) {
        if (returnToFace == null || content.getWidth() == 0 || content.getHeight() == 0) return;
        RoverPanelGeometry.Bounds safe = RoverDisplayProfile.load(this).geometry.safeBounds(content.getWidth(), content.getHeight());
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) returnToFace.getLayoutParams();
        int left = Math.max((int) Math.ceil(safe.left), (int) Math.floor(safe.right) - p.width);
        int top = Math.max((int) Math.ceil(safe.top), (int) Math.floor(safe.bottom) - p.height);
        if (p.leftMargin != left || p.topMargin != top) {
            p.leftMargin = left; p.topMargin = top; returnToFace.setLayoutParams(p);
        }
    }

    private void showFace(boolean value) {
        showingFace = value;
        phoenixDesktop.setVisibility(value ? View.GONE : View.VISIBLE);
        robotSurface.setVisibility(value ? View.VISIBLE : View.GONE);
        returnToFace.setVisibility(value ? View.GONE : View.VISIBLE);
        face.setAnimated(value && resumed);
        if (ambientWeather != null) { if (value && resumed) ambientWeather.start(); else ambientWeather.stop(); }
        refreshHearingIndicator();
        syncVoice();
        if (value) pauseHiddenAnimations();
        syncVision();
    }

    private void syncVision() {
        if (!visionEnabled || !resumed || !showingFace) {
            RoverVisionSession.preview(this).release(this);
            if (frontPreview != null) frontPreview.setActive(false, "دوربین خاموش");
            if (rearPreview != null) rearPreview.setActive(false, "دوربین خاموش");
            RoverVisionSession.get(this).release(this); attention.clear(); face.lookAt(.5f, .5f); lastVisionAt = -1;
            refreshVisionLabel(); return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!cameraPermissionPending) {
                cameraPermissionPending = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_ACCESS);
            }
            return;
        }
        int total = Camera.getNumberOfCameras();
        if (total == 0) { visionFailed = true; subtitle.setText("دوربین سازگار پیدا نشد"); refreshVisionLabel(); return; }
        int cameraId = RoverVisionSession.selectedCamera(this);
        int frontId = -1, rearId = -1;
        if (RoverVisionSession.verifiedV9eMapping()) { frontId = 0; rearId = 1; }
        else for (int id = 0; id < total; id++) {
            Camera.CameraInfo info = new Camera.CameraInfo(); Camera.getCameraInfo(id, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && frontId < 0) frontId = id;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK && rearId < 0) rearId = id;
        }
        final RoverCameraPreview primaryPreview = cameraId == rearId ? rearPreview : cameraId == frontId ? frontPreview : null;
        final RoverCameraPreview secondaryPreview = cameraId == rearId ? frontPreview : rearPreview;
        int secondaryId = cameraId == rearId ? frontId : rearId;
        if (primaryPreview != null) primaryPreview.setActive(true, "در انتظار تصویر");
        if (secondaryPreview != null && RoverCameraSelection.isAvailable(secondaryId, total) && secondaryId != cameraId) {
            secondaryPreview.setActive(true, "در انتظار تصویر");
            if (secondaryPreview.target() != null)
                RoverVisionSession.preview(this).acquire(this, secondaryId, getWindowManager().getDefaultDisplay().getRotation() * 90,
                        new RoverCamera.Listener() {
                            public void onFrame(Bitmap image, RoverVisionEngine.Result result) {
                                secondaryPreview.setActive(true, "در انتظار تصویر"); image.recycle();
                            }
                            public void onError(String message) { secondaryPreview.unavailable("تصویر دوربین در دسترس نیست"); }
                        }, secondaryPreview.target());
            else RoverVisionSession.preview(this).release(this);
        } else {
            RoverVisionSession.preview(this).release(this);
            if (secondaryPreview != null) secondaryPreview.unavailable("دوربین در دسترس نیست");
        }
        RoverVisionSession.get(this).acquire(this, cameraId, getWindowManager().getDefaultDisplay().getRotation() * 90,
                new RoverCamera.Listener() {
            public void onFrame(Bitmap image, RoverVisionEngine.Result result) {
                if (primaryPreview != null) primaryPreview.setActive(true, "در انتظار تصویر");
                image.recycle(); // Face UI consumes detections, never retains or displays the camera image.
                if (!visionErrorMessage.isEmpty() && visionErrorMessage.contentEquals(subtitle.getText()))
                    subtitle.setText("تصویر دوربین اصلی برقرار شد؛ آمادهٔ گفت‌وگو هستم");
                visionErrorMessage = "";
                lastVisionAt = SystemClock.elapsedRealtime(); visionFailed = false; faceCount = result.faceCount();
                RoverVisionEngine.Detection target = null;
                for (RoverVisionEngine.Detection detection : result.detections)
                    if (detection.face && (target == null || detection.box.width() * detection.box.height() > target.box.width() * target.box.height())) target = detection;
                if (target != null && attention.observe(target.box.centerX(), target.box.centerY(), target.confidence, lastVisionAt))
                    mood.greet(lastVisionAt);
            }
            public void onError(String message) {
                if (primaryPreview != null) primaryPreview.unavailable("تصویر دوربین در دسترس نیست");
                visionFailed = true; attention.clear(); visionErrorMessage = message; subtitle.setText(message); mood.error(SystemClock.elapsedRealtime());
            }
        }, primaryPreview == null ? null : primaryPreview.target());
    }
    private void refreshVisionLabel() {
        if (visionToggle == null) return;
        String caption = !visionEnabled ? "دیدن: خاموش" : visionFailed ? "دیدن: خطا"
                : lastVisionAt >= 0 && RoverVisionPolicy.LIVE.resting() ? "دیدن: آماده · پایش تغییر تصویر"
                : lastVisionAt < 0 || SystemClock.elapsedRealtime() - lastVisionAt >= 5000 ? "دیدن: آمادهٔ بررسی"
                : "دیدن: فعال · چهره " + faceCount;
        if (!caption.contentEquals(visionToggle.getText())) visionToggle.setText(caption);
        visionToggle.setTextColor(visionEnabled && !visionFailed && lastVisionAt >= 0 ? 0xFF61F2CE : 0xFF91E9FF);
    }
    private void pauseHiddenAnimations() {
        PhoenixNetworkBackgroundView background = findViewById(R.id.phoenix_network_background);
        PhoenixProtectionView protection = findViewById(R.id.desktop_imgview_shield);
        if (background != null) background.setAnimationEnabled(false);
        if (protection != null) protection.setAnimationEnabled(false);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        RoverOwnerVoiceDialog.resumed(this);
        RoverPoseSensors.get(this).start();
        if(performanceMonitor!=null) performanceMonitor.start();
        if (ambientWeather != null && showingFace) ambientWeather.start();
        refreshCaptionPreference();
        JsonObject diagnostic = ArgAiDiagnostics.status(this);
        aiStatus = ArgAgentProduct.verified(diagnostic) ? "عامل فونیکس آماده" : "عامل نیازمند تنظیم یا آزمون";
        if (face != null) { face.setAnimated(showingFace); main.removeCallbacks(tick); main.post(tick); }
        syncVision();
        syncVoice();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(face!=null)face.setAnimated(focused && resumed && showingFace);
        if (focused) RoverApplication.keepDisplayActive(this);
        if (focused) positionReturnButton(findViewById(android.R.id.content));
        if (focused) refreshCaptionPreference();
        syncVoice();
    }
    @Override protected void onPause() {
        if(faceEnrollmentDialog!=null && faceEnrollmentDialog.isShowing())faceEnrollmentDialog.dismiss();
        resumed = false; main.removeCallbacks(tick);
        RoverOwnerVoiceDialog.paused(this);
        RoverPoseSensors.get(this).stop();
        if(performanceMonitor!=null) performanceMonitor.stop();
        if (ambientWeather != null) ambientWeather.stop();
        if (face != null) face.setAnimated(false);
        syncVoice();
        syncVision();
        super.onPause();
    }
    void ensureEnrollmentCamera(){
        visionEnabled=true;getSharedPreferences("rover_perception",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();syncVision();
    }
    void openGuidedEnrollment(String name){faceEnrollmentDialog=RoverGuidedFaceDialog.show(this,name);}
    void openGuidedRefinement(long id,String name){faceEnrollmentDialog=RoverGuidedFaceDialog.refine(this,id,name,RoverLuxandSdk.DATABASE,RoverGuidedFaceCapture.LIVE);}
    void openAutomaticFaceEnrollment(String name){faceEnrollmentDialog=RoverGuidedFaceDialog.automatic(this,name,RoverLuxandSdk.DATABASE,RoverGuidedFaceCapture.LIVE);}
    @Override protected void onDestroy() {
        if(performanceMonitor!=null) performanceMonitor.stop();
        if(poseDialog!=null) poseDialog.dismiss();
        if(faceLearningDialog!=null)faceLearningDialog.dismiss();
        if(faceEnrollmentDialog!=null)faceEnrollmentDialog.dismiss();
        if (ambientWeather != null) ambientWeather.destroy();
        if (settingsPanel != null) settingsPanel.dismiss();
        if (voiceStatusDialog != null) voiceStatusDialog.dismiss();
        if (researchDialog != null) researchDialog.dismiss();
        if (conversationDialog != null) conversationDialog.dismiss();
        if (voiceChoiceDialog != null) voiceChoiceDialog.dismiss();
        if (visualLegendDialog != null) visualLegendDialog.dismiss();
        if (audioLevelsDialog != null) audioLevelsDialog.dismiss();
        if (locationDialog != null) locationDialog.dismiss();
        main.removeCallbacksAndMessages(null);
        if (speech != null && voiceListener != null) speech.removeListener(voiceListener);
        RoverVisionSession.get(this).release(this);
        RoverVisionSession.preview(this).release(this);
        unregisterReceiver(coreHeartbeat);
        super.onDestroy();
    }
    private void openPose() {
        if(poseDialog==null || !poseDialog.isShowing()) poseDialog=RoverPoseDialog.show(this);
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("rover.face", showingFace); super.onSaveInstanceState(state); }
    @Override public void onBackPressed() { if (!showingFace) showFace(true); else super.onBackPressed(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (speech != null) speech.onRequestPermissionsResult(request, permissions, results);
        if (request == 8702 && ambientWeather != null && showingFace && resumed) { ambientWeather.stop(); ambientWeather.start(); }
        if (request == CAMERA_ACCESS) {
            cameraPermissionPending = false;
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
                visionEnabled = false;
                getSharedPreferences("rover_perception", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
                subtitle.setText("دیدن به مجوز دوربین نیاز دارد");
            }
            syncVision();
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == AI_ACCESS && result == RESULT_OK && data != null
                && data.getIntExtra(ArgActivity_PersonAcc.DATA_ACCESS_RETURN, 255) < 255)
            startActivity(new Intent(this, ArgActivity_ServiceSettings.class));
    }
    private Button action(LinearLayout row, String caption, int id) {
        Button value = button(caption); value.setId(id);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.setMargins(dp(6), dp(4), dp(6), dp(4)); row.addView(value, p); return value;
    }
    private Button button(String caption) {
        Button value = new Button(this); value.setText(caption); value.setTextSize(14);
        value.setSoundEffectsEnabled(false);
        value.setMinWidth(0); value.setMinimumWidth(0); value.setMinHeight(0); value.setMinimumHeight(0);
        value.setTextColor(0xFF91E9FF); value.setAllCaps(false); value.setPadding(dp(5), 0, dp(5), 0);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFF0A1B2A); bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFF1A465D); value.setBackground(bg); return value;
    }
    private TextView text(String text, int size, int color) {
        TextView value = new TextView(this); value.setText(text); value.setTextSize(size); value.setTextColor(color); return value;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
