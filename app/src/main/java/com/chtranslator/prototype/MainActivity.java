package com.chtranslator.prototype;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class MainActivity extends Activity {
    private static final int REQUEST_SCREEN_CAPTURE = 4101;
    private static final String PREFS = "screen_translator_prefs";
    private static final String KEY_AUTO_CAPTURE = "auto_capture";
    private static final String KEY_APP_AWARE_DICTIONARIES = "app_aware_dictionaries";
    private static final String KEY_REGION_MODE = "region_mode";
    private static final String REGION_WHOLE = "whole";
    private static final String REGION_SELECT = "select";

    private static final int STATE_IDLE = 0;
    private static final int STATE_SCANNING = 1;
    private static final int STATE_ACTIVE = 2;
    private static final int DRAG_THRESHOLD_DP = 8;
    private static final int MIN_SELECTION_DP = 36;
    private static final int CONTROL_RESTART_LONG_PRESS_MS = 650;

    private static MainActivity activeInstance;
    private static volatile String currentAppCategory = "general";

    private WindowManager wm;
    private TranslatorControlView control;
    private WindowManager.LayoutParams controlParams;
    private View translatorOverlay;
    private TextView status;
    private CheckBox autoCaptureCheckBox;
    private CheckBox appAwareDictionariesCheckBox;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private float downRawX;
    private float downRawY;
    private float downLocalX;
    private float downLocalY;
    private int downX;
    private int downY;
    private boolean controlMoved;
    private boolean controlLongPressTriggered;
    private Runnable pendingControlLongPressRunnable;
    private View selectionOverlay;
    private int dragThresholdPx;
    private boolean screenCaptureSessionReady = false;
    private int translatorState = STATE_IDLE;
    private boolean acceptOcrResults = false;

    public static class OcrBox {
        public final String text;
        public final String english;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public OcrBox(String text, int left, int top, int right, int bottom) {
            this(text, "", left, top, right, bottom);
        }

        public OcrBox(String text, String english, int left, int top, int right, int bottom) {
            this.text = text == null ? "" : text;
            this.english = english == null ? "" : english;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    public static void updateForegroundPackage(String packageName) {
        if (packageName == null) return;
        currentAppCategory = categoryForPackage(packageName);
    }

    public static String getCurrentAppCategory() {
        return currentAppCategory == null ? "general" : currentAppCategory;
    }

    public static boolean areAppAwareDictionariesEnabled() {
        MainActivity instance = activeInstance;
        return instance != null && instance.isAppAwareDictionariesEnabledLocal();
    }

    public static String getRegionMode() {
        MainActivity instance = activeInstance;
        return instance == null ? REGION_WHOLE : instance.getRegionModeLocal();
    }

    public static void shiftCurrentOverlayForScroll(int deltaY) {
        MainActivity instance = activeInstance;
        if (instance == null) return;
        if (deltaY == 0) return;
        instance.mainHandler.post(() -> instance.shiftOcrOverlay(-deltaY));
    }

    public static void hideOverlayForPageChange() {
        MainActivity instance = activeInstance;
        if (instance == null) return;
        instance.mainHandler.post(instance::hideTranslatorOverlayOnly);
    }

    public static void hideFromUserInteraction() {
        // Manual static mode: screen/app interactions do not hide or refresh the overlay.
    }

    public static void refreshFromScreenAction() {
        // Manual static mode: no automatic refresh.
    }

    public static void refreshFromUserMotion() {
        // Manual static mode: no automatic refresh.
    }

    public static void showOcrResultsFromService(ArrayList<OcrBox> boxes, int imageWidth, int imageHeight) {
        showOcrResultsFromService(boxes, imageWidth, imageHeight, null);
    }

    public static void showOcrResultsFromService(ArrayList<OcrBox> boxes, int imageWidth, int imageHeight, Bitmap screenBitmap) {
        MainActivity instance = activeInstance;
        if (instance == null) {
            if (screenBitmap != null && !screenBitmap.isRecycled()) {
                try { screenBitmap.recycle(); } catch (Exception ignored) {}
            }
            return;
        }
        instance.mainHandler.post(() -> {
            if (!instance.acceptOcrResults) {
                if (screenBitmap != null && !screenBitmap.isRecycled()) {
                    try { screenBitmap.recycle(); } catch (Exception ignored) {}
                }
                return;
            }
            instance.acceptOcrResults = false;
            instance.showOcrOverlay(boxes, imageWidth, imageHeight, screenBitmap);
            instance.setTranslatorState(STATE_ACTIVE);
        });
    }

    public static void showOcrMessageFromService(String message) {
        MainActivity instance = activeInstance;
        if (instance == null) return;
        instance.mainHandler.post(() -> {
            instance.acceptOcrResults = false;
            if (instance.translatorOverlay == null) {
                instance.showMessageOverlay(message == null ? "OCR message" : message);
                instance.setTranslatorState(STATE_ACTIVE);
            } else {
                instance.setTranslatorState(STATE_ACTIVE);
            }
            Toast.makeText(instance, message == null ? "OCR message" : message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeInstance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        dragThresholdPx = dp(DRAG_THRESHOLD_DP);
        buildScreen();
        warmUpOcrAndTranslation();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Screen Translator Prototype");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(16);
        status.setTextColor(Color.DKGRAY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 32, 0, 32);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button permissionButton = new Button(this);
        permissionButton.setText("Open overlay permission settings");
        permissionButton.setOnClickListener(v -> openOverlaySettings());
        root.addView(permissionButton, new LinearLayout.LayoutParams(-1, -2));

        Button accessibilityButton = new Button(this);
        accessibilityButton.setText("Open accessibility settings for app-aware dictionaries");
        accessibilityButton.setOnClickListener(v -> openAccessibilitySettings());
        root.addView(accessibilityButton, new LinearLayout.LayoutParams(-1, -2));

        autoCaptureCheckBox = new CheckBox(this);
        autoCaptureCheckBox.setText("Enable manual screen OCR when A⇄中 is tapped");
        autoCaptureCheckBox.setTextSize(16);
        autoCaptureCheckBox.setTextColor(Color.BLACK);
        autoCaptureCheckBox.setChecked(getPrefs().getBoolean(KEY_AUTO_CAPTURE, true));
        autoCaptureCheckBox.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            getPrefs().edit().putBoolean(KEY_AUTO_CAPTURE, isChecked).apply();
            updateStatus();
        });
        root.addView(autoCaptureCheckBox, new LinearLayout.LayoutParams(-1, -2));

        appAwareDictionariesCheckBox = new CheckBox(this);
        appAwareDictionariesCheckBox.setText("Use app-aware dictionaries when Accessibility is enabled");
        appAwareDictionariesCheckBox.setTextSize(16);
        appAwareDictionariesCheckBox.setTextColor(Color.BLACK);
        appAwareDictionariesCheckBox.setChecked(getPrefs().getBoolean(KEY_APP_AWARE_DICTIONARIES, true));
        appAwareDictionariesCheckBox.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            getPrefs().edit().putBoolean(KEY_APP_AWARE_DICTIONARIES, isChecked).apply();
            updateStatus();
        });
        root.addView(appAwareDictionariesCheckBox, new LinearLayout.LayoutParams(-1, -2));

        TextView modeNote = new TextView(this);
        modeNote.setText("Translation region is controlled from the floating button: tap Whole/Select to switch. Select mode lets you draw a box.");
        modeNote.setTextSize(14);
        modeNote.setTextColor(Color.DKGRAY);
        modeNote.setGravity(Gravity.CENTER);
        modeNote.setPadding(0, 16, 0, 8);
        root.addView(modeNote, new LinearLayout.LayoutParams(-1, -2));

        Button capturePermissionButton = new Button(this);
        capturePermissionButton.setText("Grant screen capture for this session");
        capturePermissionButton.setOnClickListener(v -> requestScreenCapturePermission());
        root.addView(capturePermissionButton, new LinearLayout.LayoutParams(-1, -2));

        Button stopCaptureButton = new Button(this);
        stopCaptureButton.setText("Stop screen capture session");
        stopCaptureButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            screenCaptureSessionReady = false;
            Toast.makeText(this, "Screen capture session stopped", Toast.LENGTH_SHORT).show();
            updateStatus();
        });
        root.addView(stopCaptureButton, new LinearLayout.LayoutParams(-1, -2));

        Button showButton = new Button(this);
        showButton.setText("Show floating translator control");
        showButton.setOnClickListener(v -> {
            if (!hasOverlayPermission()) {
                openOverlaySettings();
                return;
            }
            showControl();
            moveTaskToBack(true);
        });
        root.addView(showButton, new LinearLayout.LayoutParams(-1, -2));

        TextView privacyNote = new TextView(this);
        privacyNote.setText("Manual static version: tap A⇄中 to OCR/translate the current screen, tap A⇄中 again to reload, tap × to close. No automatic refresh. Captures are one-shot, processed in memory, and discarded. No screenshot is saved.");
        privacyNote.setTextSize(14);
        privacyNote.setTextColor(Color.GRAY);
        privacyNote.setGravity(Gravity.CENTER);
        privacyNote.setPadding(0, 28, 0, 0);
        root.addView(privacyNote, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        updateStatus();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isAutoCaptureEnabled() {
        return getPrefs().getBoolean(KEY_AUTO_CAPTURE, true);
    }

    private boolean isAppAwareDictionariesEnabledLocal() {
        return getPrefs().getBoolean(KEY_APP_AWARE_DICTIONARIES, true);
    }

    private String getRegionModeLocal() {
        String mode = getPrefs().getString(KEY_REGION_MODE, REGION_WHOLE);
        return REGION_SELECT.equals(mode) ? REGION_SELECT : REGION_WHOLE;
    }

    private int getTranslationCoveragePercentLocal() {
        return 100;
    }

    private void toggleRegionMode() {
        String next = REGION_SELECT.equals(getRegionModeLocal()) ? REGION_WHOLE : REGION_SELECT;
        getPrefs().edit().putString(KEY_REGION_MODE, next).apply();
        if (control != null) control.setMode(next);
        Toast.makeText(this, REGION_SELECT.equals(next) ? "Selection mode" : "Whole screen mode", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void updateStatus() {
        if (status == null) return;
        String overlayText = hasOverlayPermission()
                ? "Overlay permission is enabled."
                : "Overlay permission is not enabled. Enable Display over other apps.";
        String autoText = isAutoCaptureEnabled()
                ? "Manual screen capture/OCR is ON."
                : "Manual screen capture/OCR is OFF.";
        String sessionText = screenCaptureSessionReady
                ? "Screen capture session is ready."
                : "Screen capture session is not ready. Grant it before OCR.";
        String dictionaryText = isAppAwareDictionariesEnabledLocal()
                ? "Dictionary mode: app-aware (" + getCurrentAppCategory() + ")."
                : "Dictionary mode: general only.";
        String regionText = "Region: "
                + (REGION_SELECT.equals(getRegionModeLocal()) ? "Selection box" : "Whole screen") + ".";
        String modeText = "Mode: manual. Tap A⇄中 to translate/reload; Whole/Select changes region; × closes; long-press × restarts tool.";
        status.setText(overlayText + "\n"
                + autoText + "\n"
                + sessionText + "\n"
                + dictionaryText + "\n"
                + regionText + "\n"
                + modeText);
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }

    private void requestScreenCapturePermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "MediaProjection is unavailable on this device", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SCREEN_CAPTURE) return;

        if (resultCode == RESULT_OK && data != null) {
            Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
            serviceIntent.setAction(ScreenCaptureService.ACTION_START_PROJECTION);
            serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            screenCaptureSessionReady = true;
            Toast.makeText(this, "Screen capture ready for this session", Toast.LENGTH_SHORT).show();
            warmUpOcrAndTranslation();
        } else {
            screenCaptureSessionReady = false;
            Toast.makeText(this, "Screen capture permission was not granted", Toast.LENGTH_SHORT).show();
        }
        updateStatus();
    }

    private void warmUpOcrAndTranslation() {
        try {
            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.setAction(ScreenCaptureService.ACTION_WARM_UP);
            startService(intent);
        } catch (Exception ignored) {
            // Warm-up is optional; the first real OCR request can still initialize models.
        }
    }

    private void showControl() {
        if (control != null) return;
        control = new TranslatorControlView(this);
        control.setState(translatorState);
        control.setMode(getRegionModeLocal());
        control.setClickable(true);

        controlParams = new WindowManager.LayoutParams(
                dp(64),
                dp(172),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        controlParams.gravity = Gravity.TOP | Gravity.START;
        controlParams.x = getPreferences(MODE_PRIVATE).getInt("control_x", 40);
        controlParams.y = getPreferences(MODE_PRIVATE).getInt("control_y", 240);

        control.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                lastControlTouchMs = System.currentTimeMillis();
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downLocalX = event.getX();
                    downLocalY = event.getY();
                    downX = controlParams.x;
                    downY = controlParams.y;
                    controlMoved = false;
                    controlLongPressTriggered = false;

                    if (control.isCloseTap(downLocalX, downLocalY)) {
                        pendingControlLongPressRunnable = () -> {
                            if (!controlMoved && control != null && control.isCloseTap(downLocalX, downLocalY)) {
                                controlLongPressTriggered = true;
                                restartTranslatorTool();
                            }
                        };
                        mainHandler.postDelayed(pendingControlLongPressRunnable, CONTROL_RESTART_LONG_PRESS_MS);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    if (Math.abs(dx) > dragThresholdPx || Math.abs(dy) > dragThresholdPx) {
                        controlMoved = true;
                        if (pendingControlLongPressRunnable != null) {
                            mainHandler.removeCallbacks(pendingControlLongPressRunnable);
                            pendingControlLongPressRunnable = null;
                        }
                        controlParams.x = downX + dx;
                        controlParams.y = downY + dy;
                        try { wm.updateViewLayout(control, controlParams); } catch (Exception ignored) {}
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pendingControlLongPressRunnable != null) {
                        mainHandler.removeCallbacks(pendingControlLongPressRunnable);
                        pendingControlLongPressRunnable = null;
                    }
                    getPreferences(MODE_PRIVATE).edit()
                            .putInt("control_x", controlParams.x)
                            .putInt("control_y", controlParams.y)
                            .apply();
                    if (!controlMoved && !controlLongPressTriggered) {
                        if (control.isCloseTap(downLocalX, downLocalY)) {
                            hideTranslatorOverlay();
                        } else if (control.isModeTap(downLocalX, downLocalY)) {
                            toggleRegionMode();
                        } else if (control.isTranslateTap(downLocalX, downLocalY)) {
                            requestManualOcrCapture();
                        }
                    }
                    view.performClick();
                    return true;
            }
            return false;
        });

        wm.addView(control, controlParams);
        addTouchWatcher();
    }

    private void restartTranslatorTool() {
        acceptOcrResults = false;
        removeSelectionOverlay();
        removeOverlayOnly();

        if (control != null) {
            try { wm.removeView(control);
            removeTouchWatcher(); } catch (Exception ignored) {}
            control = null;
        }

        translatorState = STATE_IDLE;
        screenCaptureSessionReady = false;

        Toast.makeText(this, "Translator tool restarted", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(() -> {
            showControl();
            updateStatus();
            requestScreenCapturePermission();
        }, 250L);
    }

    private void requestManualOcrCapture() {
        if (REGION_SELECT.equals(getRegionModeLocal())) {
            startSelectionOverlay();
            return;
        }
        requestManualOcrCapture(null);
    }

    private void requestManualOcrCapture(RectF selectionNorm) {
        if (translatorState == STATE_SCANNING) {
            Toast.makeText(this, "Already scanning", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isAutoCaptureEnabled()) {
            showMessageOverlay("TRANSLATOR ACTIVE\nManual OCR is off");
            setTranslatorState(STATE_ACTIVE);
            return;
        }

        if (!screenCaptureSessionReady) {
            Toast.makeText(this, "Grant screen capture first", Toast.LENGTH_LONG).show();
            return;
        }

        acceptOcrResults = true;
        setTranslatorState(STATE_SCANNING);

        if (translatorOverlay == null) {
            showMessageOverlay(selectionNorm == null ? "OCR SCANNING" : "OCR SCANNING SELECTED AREA");
        } else {
            Toast.makeText(this, selectionNorm == null ? "Refreshing translation" : "Translating selection", Toast.LENGTH_SHORT).show();
        }

        Intent captureIntent = new Intent(this, ScreenCaptureService.class);
        captureIntent.setAction(ScreenCaptureService.ACTION_CAPTURE_ONCE);
        if (selectionNorm != null) {
            captureIntent.putExtra(ScreenCaptureService.EXTRA_SELECTION_ENABLED, true);
            captureIntent.putExtra(ScreenCaptureService.EXTRA_SELECTION_LEFT, selectionNorm.left);
            captureIntent.putExtra(ScreenCaptureService.EXTRA_SELECTION_TOP, selectionNorm.top);
            captureIntent.putExtra(ScreenCaptureService.EXTRA_SELECTION_RIGHT, selectionNorm.right);
            captureIntent.putExtra(ScreenCaptureService.EXTRA_SELECTION_BOTTOM, selectionNorm.bottom);
        }
        startService(captureIntent);
    }

    private void startSelectionOverlay() {
        if (selectionOverlay != null) return;
        removeOverlayOnly();

        SelectionBoxView selectView = new SelectionBoxView(this, rect -> {
            removeSelectionOverlay();
            if (rect == null) {
                Toast.makeText(this, "Selection cancelled", Toast.LENGTH_SHORT).show();
                return;
            }
            requestManualOcrCapture(rect);
        });

        selectionOverlay = selectView;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        wm.addView(selectionOverlay, p);
        Toast.makeText(this, "Drag a box around the area to translate", Toast.LENGTH_SHORT).show();
    }

    private void removeSelectionOverlay() {
        if (selectionOverlay != null) {
            try { wm.removeView(selectionOverlay); } catch (Exception ignored) {}
            selectionOverlay = null;
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void setTranslatorState(int state) {
        translatorState = state;
        if (control != null) control.setState(state);
    }

    private void showMessageOverlay(String message) {
        showOverlayView(new StatusBorderView(this, message));
    }

    private void showOcrOverlay(ArrayList<OcrBox> boxes, int imageWidth, int imageHeight, Bitmap screenBitmap) {
        showOverlayView(new OcrBorderView(this, boxes, imageWidth, imageHeight, screenBitmap,
                getTranslationCoveragePercentLocal(), currentControlBounds()));
    }

    private RectF currentControlBounds() {
        if (control == null || controlParams == null) return null;
        int width = control.getWidth() > 0 ? control.getWidth() : dp(64);
        int height = control.getHeight() > 0 ? control.getHeight() : dp(172);
        RectF bounds = new RectF(controlParams.x, controlParams.y, controlParams.x + width, controlParams.y + height);
        bounds.inset(-dp(2), -dp(2));
        return bounds;
    }

    private void showOverlayView(View overlayView) {
        removeOverlayOnly();
        translatorOverlay = overlayView;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.alpha = 1.0f;
        wm.addView(translatorOverlay, p);
    }

    private void removeOverlayOnly() {
        if (translatorOverlay != null) {
            try { wm.removeView(translatorOverlay); } catch (Exception ignored) {}
            translatorOverlay = null;
        }
    }

    private void shiftOcrOverlay(int deltaY) {
        hideTranslatorOverlayOnly();
    }

    private void hideTranslatorOverlayOnly() {
        // Page/window changed: hide stale translation labels.
        // Keep the connected floating control visible.
        hideTranslatorOverlay();
    }

    private void hideTranslatorOverlay() {
        acceptOcrResults = false;
        removeSelectionOverlay();
        removeOverlayOnly();
        setTranslatorState(STATE_IDLE);
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (translatorOverlay != null) wm.removeView(translatorOverlay); } catch (Exception ignored) {}
        try { if (control != null) wm.removeView(control);
            removeTouchWatcher(); } catch (Exception ignored) {}
        translatorOverlay = null;
        control = null;
        if (activeInstance == this) activeInstance = null;
    }

    public static String categoryForPackage(String packageName) {
        if (packageName == null) return "general";
        String p = packageName.toLowerCase();

        if (p.contains("taobao") || p.contains("tmall") || p.contains("jingdong") ||
                p.contains("jd") || p.contains("pinduoduo") || p.contains("xianyu") ||
                p.contains("amazon") || p.contains("alipay") || p.contains("meituan") ||
                p.contains("eleme") || p.contains("dianping")) {
            return "shopping";
        }

        if (p.contains("wechat") || p.contains("tencent.mm") || p.contains("weibo") ||
                p.contains("xiaohongshu") || p.contains("rednote") || p.contains("douyin") ||
                p.contains("tiktok") || p.contains("kuaishou") || p.contains("qq")) {
            return "social";
        }

        if (p.contains("news") || p.contains("toutiao") || p.contains("netease") ||
                p.contains("sohu") || p.contains("ifeng") || p.contains("thepaper") ||
                p.contains("caixin") || p.contains("xinhuanet")) {
            return "news";
        }

        if (p.contains("docs") || p.contains("document") || p.contains("office") ||
                p.contains("word") || p.contains("wps") || p.contains("pdf") ||
                p.contains("adobe") || p.contains("reader") || p.contains("drive")) {
            return "documents";
        }

        return "general";
    }

    private static class TranslatorControlView extends View {
        private final Paint leftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF fullRect = new RectF();
        private final RectF modeRect = new RectF();
        private final RectF translateRect = new RectF();
        private final RectF closeRect = new RectF();
        private int state = STATE_IDLE;
        private String mode = REGION_WHOLE;
        private ValueAnimator scanAnimator;
        private float scanProgress = 0f;

        TranslatorControlView(Context context) {
            super(context);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            textPaint.setColor(Color.WHITE);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(3f);
            borderPaint.setColor(Color.argb(230, 255, 255, 255));
            dividerPaint.setStyle(Paint.Style.STROKE);
            dividerPaint.setStrokeWidth(2f);
            dividerPaint.setColor(Color.argb(190, 255, 255, 255));
        }

        void setState(int state) {
            if (this.state == state) {
                if (state == STATE_SCANNING && scanAnimator == null) {
                    startScanAnimation();
                }
                invalidate();
                return;
            }

            this.state = state;

            if (state == STATE_SCANNING) {
                startScanAnimation();
            } else {
                stopScanAnimation();
            }

            invalidate();
        }

        void setMode(String mode) {
            this.mode = REGION_SELECT.equals(mode) ? REGION_SELECT : REGION_WHOLE;
            invalidate();
        }

        private void startScanAnimation() {
            stopScanAnimation();
            scanAnimator = ValueAnimator.ofFloat(0f, 1f);
            scanAnimator.setDuration(850L);
            scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
            scanAnimator.setRepeatMode(ValueAnimator.REVERSE);
            scanAnimator.setInterpolator(new LinearInterpolator());
            scanAnimator.addUpdateListener(animation -> {
                scanProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            scanAnimator.start();
        }

        private void stopScanAnimation() {
            if (scanAnimator != null) {
                scanAnimator.cancel();
                scanAnimator = null;
            }
            scanProgress = 0f;
        }

        private int blendColors(int from, int to, float ratio) {
            float r = Math.max(0f, Math.min(1f, ratio));
            int red = (int) (Color.red(from) + r * (Color.red(to) - Color.red(from)));
            int green = (int) (Color.green(from) + r * (Color.green(to) - Color.green(from)));
            int blue = (int) (Color.blue(from) + r * (Color.blue(to) - Color.blue(from)));
            return Color.rgb(red, green, blue);
        }

        @Override
        protected void onDetachedFromWindow() {
            stopScanAnimation();
            super.onDetachedFromWindow();
        }

        boolean isModeTap(float x, float y) {
            return modeRect.contains(x, y);
        }

        boolean isTranslateTap(float x, float y) {
            return translateRect.contains(x, y);
        }

        boolean isCloseTap(float x, float y) {
            return closeRect.contains(x, y);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float density = getResources().getDisplayMetrics().density;
            float closeH = 44f * density;
            float modeH = 52f * density;
            float splitModeY = modeH;
            float splitCloseY = h - closeH;
            float radius = w / 2f;

            fullRect.set(0f, 0f, w, h);
            modeRect.set(0f, 0f, w, splitModeY);
            translateRect.set(0f, splitModeY, w, splitCloseY);
            closeRect.set(0f, splitCloseY, w, h);

            int orange = Color.rgb(238, 118, 0);
            int blue = Color.rgb(0, 175, 215);
            int leftColor;
            if (state == STATE_IDLE) {
                leftColor = orange;
            } else if (state == STATE_SCANNING) {
                leftColor = blendColors(orange, blue, scanProgress);
            } else {
                leftColor = blue;
            }
            int rightColor = Color.rgb(205, 40, 40);
            int modeColor = REGION_SELECT.equals(mode) ? Color.rgb(95, 95, 95) : Color.rgb(70, 70, 70);

            leftPaint.setStyle(Paint.Style.FILL);
            leftPaint.setColor(leftColor);
            int orangeGlow = Color.rgb(255, 145, 0);
            int blueGlow = Color.rgb(0, 220, 255);
            int glowColor = state == STATE_IDLE
                    ? orangeGlow
                    : (state == STATE_SCANNING ? blendColors(orangeGlow, blueGlow, scanProgress) : blueGlow);
            float glowRadius = state == STATE_IDLE ? 18f : 28f;
            leftPaint.setShadowLayer(glowRadius, 0f, 0f, glowColor);
            canvas.drawRoundRect(fullRect, radius, radius, leftPaint);

            rightPaint.setStyle(Paint.Style.FILL);
            rightPaint.setColor(modeColor);
            canvas.save();
            canvas.clipRect(0f, 0f, w, splitModeY);
            canvas.drawRoundRect(fullRect, radius, radius, rightPaint);
            canvas.restore();

            rightPaint.setColor(rightColor);
            canvas.save();
            canvas.clipRect(0f, splitCloseY, w, h);
            canvas.drawRoundRect(fullRect, radius, radius, rightPaint);
            canvas.restore();

            canvas.drawLine(8f, splitModeY, w - 8f, splitModeY, dividerPaint);
            canvas.drawLine(8f, splitCloseY, w - 8f, splitCloseY, dividerPaint);
            canvas.drawRoundRect(fullRect, radius, radius, borderPaint);

            Paint.FontMetrics fm;
            float textY;

            float centerX = w / 2f;

            textPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
            fm = textPaint.getFontMetrics();
            textY = splitModeY / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(REGION_SELECT.equals(mode) ? "Select" : "Whole", centerX, textY, textPaint);

            textPaint.setTextSize(17f * getResources().getDisplayMetrics().scaledDensity);
            fm = textPaint.getFontMetrics();
            textY = splitModeY + (splitCloseY - splitModeY) / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText("A⇄中", centerX, textY, textPaint);

            textPaint.setTextSize(28f * getResources().getDisplayMetrics().scaledDensity);
            fm = textPaint.getFontMetrics();
            textY = splitCloseY + (h - splitCloseY) / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText("×", centerX, textY, textPaint);
        }
    }


    private interface SelectionCompleteListener {
        void onSelectionComplete(RectF normalizedRect);
    }

    private class SelectionBoxView extends View {
        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF selection = new RectF();
        private final SelectionCompleteListener listener;
        private float startX;
        private float startY;
        private boolean dragging = false;

        SelectionBoxView(Context context, SelectionCompleteListener listener) {
            super(context);
            this.listener = listener;
            setWillNotDraw(false);
            setBackgroundColor(Color.TRANSPARENT);

            dimPaint.setStyle(Paint.Style.FILL);
            dimPaint.setColor(Color.argb(82, 0, 0, 0));

            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(dp(3));
            boxPaint.setColor(Color.rgb(0, 210, 255));
            boxPaint.setShadowLayer(10f, 0f, 0f, Color.rgb(0, 210, 255));

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(16));
            textPaint.setFakeBoldText(true);
            textPaint.setShadowLayer(6f, 0f, 0f, Color.BLACK);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), dimPaint);

            if (dragging || selection.width() > 0f) {
                canvas.drawRoundRect(selection, dp(8), dp(8), boxPaint);
                canvas.drawText("Translate this area", selection.centerX(), Math.max(dp(28), selection.top - dp(12)), textPaint);
            } else {
                canvas.drawText("Drag a box around the area to translate", getWidth() / 2f, getHeight() / 2f, textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    selection.set(startX, startY, startX, startY);
                    dragging = true;
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    selection.set(
                            Math.min(startX, event.getX()),
                            Math.min(startY, event.getY()),
                            Math.max(startX, event.getX()),
                            Math.max(startY, event.getY()));
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                    dragging = false;
                    selection.set(
                            Math.min(startX, event.getX()),
                            Math.min(startY, event.getY()),
                            Math.max(startX, event.getX()),
                            Math.max(startY, event.getY()));

                    if (selection.width() < dp(MIN_SELECTION_DP) || selection.height() < dp(MIN_SELECTION_DP)) {
                        listener.onSelectionComplete(null);
                        return true;
                    }

                    int[] loc = new int[2];
                    try {
                        getLocationOnScreen(loc);
                    } catch (Exception ignored) {
                        loc[0] = 0;
                        loc[1] = 0;
                    }

                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    float displayW = Math.max(Math.max(1f, getWidth() + loc[0]), dm.widthPixels);
                    float displayH = Math.max(Math.max(1f, getHeight() + loc[1]), dm.heightPixels);

                    RectF normalized = new RectF(
                            (selection.left + loc[0]) / displayW,
                            (selection.top + loc[1]) / displayH,
                            (selection.right + loc[0]) / displayW,
                            (selection.bottom + loc[1]) / displayH);
                    listener.onSelectionComplete(normalized);
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    listener.onSelectionComplete(null);
                    return true;
            }
            return true;
        }
    }

    private abstract static class BaseBorderView extends View {
        final Paint outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();

        BaseBorderView(Context context) {
            super(context);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            outerPaint.setStyle(Paint.Style.STROKE);
            outerPaint.setStrokeWidth(18f);
            outerPaint.setColor(Color.rgb(0, 220, 255));
            outerPaint.setShadowLayer(28f, 0f, 0f, Color.rgb(0, 220, 255));

            innerPaint.setStyle(Paint.Style.STROKE);
            innerPaint.setStrokeWidth(5f);
            innerPaint.setColor(Color.WHITE);
            innerPaint.setAlpha(230);

            labelPaint.setColor(Color.WHITE);
            labelPaint.setTextSize(32f);
            labelPaint.setFakeBoldText(true);
            labelPaint.setShadowLayer(10f, 0f, 0f, Color.rgb(0, 160, 220));
            labelPaint.setTextAlign(Paint.Align.CENTER);
        }

        void drawBaseBorder(Canvas canvas, String label) {
            float inset = 18f;
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawRoundRect(rect, 28f, 28f, outerPaint);
            canvas.drawRoundRect(rect, 28f, 28f, innerPaint);
            canvas.drawText(label, getWidth() / 2f, 64f, labelPaint);
        }
    }

    private static class StatusBorderView extends BaseBorderView {
        private final String message;
        private final Paint smallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StatusBorderView(Context context, String message) {
            super(context);
            this.message = message == null ? "TRANSLATOR ACTIVE" : message;
            smallTextPaint.setColor(Color.WHITE);
            smallTextPaint.setTextSize(28f);
            smallTextPaint.setFakeBoldText(true);
            smallTextPaint.setShadowLayer(10f, 0f, 0f, Color.rgb(0, 160, 220));
            smallTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawBaseBorder(canvas, "TRANSLATOR ACTIVE");
            String[] lines = message.split("\\n");
            float y = 112f;
            for (String line : lines) {
                canvas.drawText(line, getWidth() / 2f, y, smallTextPaint);
                y += 34f;
            }
        }
    }




    private static class OcrBorderView extends BaseBorderView {
        private static final int MAX_VISIBLE_REPLACEMENTS = 60;
        private static final float MIN_TEXT_SIZE = 9f;
        private static final float MAX_TEXT_SIZE = 26f;
        private static final float PAD_X = 4f;
        private static final float PAD_Y = 3f;
        private static final float SAFE_MARGIN = 4f;
        private static final float STATUS_BAR_SKIP_PX = 72f;

        private float scrollOffsetY = 0f;
        private final ArrayList<OcrBox> boxes;
        private final int imageWidth;
        private final int imageHeight;
        private Bitmap screenBitmap;
        private final int coveragePercent;
        private final RectF ignoreRect;

        private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fallbackBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fallbackTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF workRect = new RectF();

        private static class ReplacementItem {
            final OcrBox box;
            final RectF sourceRect;
            final String text;
            final int priority;

            ReplacementItem(OcrBox box, RectF sourceRect, String text, int priority) {
                this.box = box;
                this.sourceRect = sourceRect;
                this.text = text;
                this.priority = priority;
            }
        }

        private static class TextFit {
            final ArrayList<String> lines;
            final float textSize;
            final boolean fits;

            TextFit(ArrayList<String> lines, float textSize, boolean fits) {
                this.lines = lines;
                this.textSize = textSize;
                this.fits = fits;
            }
        }

        private static class ScreenMap {
            final float scaleX;
            final float scaleY;
            final float offsetX;
            final float offsetY;

            ScreenMap(float scaleX, float scaleY, float offsetX, float offsetY) {
                this.scaleX = scaleX;
                this.scaleY = scaleY;
                this.offsetX = offsetX;
                this.offsetY = offsetY;
            }
        }

        private ScreenMap screenMap() {
            float imgW = Math.max(1f, imageWidth);
            float imgH = Math.max(1f, imageHeight);

            int[] loc = new int[2];
            try {
                getLocationOnScreen(loc);
            } catch (Exception ignored) {
                loc[0] = 0;
                loc[1] = 0;
            }

            DisplayMetrics dm = getResources().getDisplayMetrics();
            float displayW = Math.max(Math.max(1f, getWidth() + loc[0]), dm.widthPixels);
            float displayH = Math.max(Math.max(1f, getHeight() + loc[1]), dm.heightPixels);

            // Map OCR bitmap coordinates into real display coordinates, then subtract
            // the overlay view's actual screen position. This handles fold/open layouts,
            // split display sizes, and status/navigation-bar offsets better than assuming
            // the overlay view starts at 0,0.
            return new ScreenMap(displayW / imgW, displayH / imgH, -loc[0], -loc[1]);
        }

        private RectF mapBox(OcrBox box, ScreenMap map, float yOffset) {
            return new RectF(
                    map.offsetX + box.left * map.scaleX,
                    map.offsetY + box.top * map.scaleY + yOffset,
                    map.offsetX + box.right * map.scaleX,
                    map.offsetY + box.bottom * map.scaleY + yOffset);
        }

        OcrBorderView(Context context, ArrayList<OcrBox> boxes, int imageWidth, int imageHeight,
                      Bitmap screenBitmap, int coveragePercent, RectF ignoreRect) {
            super(context);
            this.boxes = boxes == null ? new ArrayList<>() : boxes;
            this.imageWidth = Math.max(1, imageWidth);
            this.imageHeight = Math.max(1, imageHeight);
            this.screenBitmap = screenBitmap;
            this.coveragePercent = Math.max(1, Math.min(100, coveragePercent));
            this.ignoreRect = ignoreRect == null ? null : new RectF(ignoreRect);

            coverPaint.setStyle(Paint.Style.FILL);

            textPaint.setAntiAlias(true);
            textPaint.setFakeBoldText(false);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setSubpixelText(true);
            textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);

            fallbackBgPaint.setStyle(Paint.Style.FILL);
            fallbackBgPaint.setColor(Color.argb(255, 18, 18, 18));

            fallbackTextPaint.setColor(Color.WHITE);
            fallbackTextPaint.setTextSize(20f);
            fallbackTextPaint.setFakeBoldText(true);
            fallbackTextPaint.setTextAlign(Paint.Align.CENTER);
            fallbackTextPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);

            emptyPaint.setColor(Color.WHITE);
            emptyPaint.setTextSize(28f);
            emptyPaint.setFakeBoldText(true);
            emptyPaint.setTextAlign(Paint.Align.CENTER);
            emptyPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }

        void shiftBy(int deltaY) {
            // All replacements are scrollable. No fixed header/footer/menu guessing.
            scrollOffsetY += deltaY;
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (screenBitmap != null && !screenBitmap.isRecycled()) {
                try { screenBitmap.recycle(); } catch (Exception ignored) {}
            }
            screenBitmap = null;
            super.onDetachedFromWindow();
        }

        private boolean hasChinese(String value) {
            if (value == null) return false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c >= '\u4E00' && c <= '\u9FFF') ||
                        (c >= '\u3400' && c <= '\u4DBF') ||
                        (c >= '\uF900' && c <= '\uFAFF')) {
                    return true;
                }
            }
            return false;
        }

        private int chineseCount(String value) {
            if (value == null) return 0;
            int count = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c >= '\u4E00' && c <= '\u9FFF') ||
                        (c >= '\u3400' && c <= '\u4DBF') ||
                        (c >= '\uF900' && c <= '\uFAFF')) {
                    count++;
                }
            }
            return count;
        }

        private boolean looksLikeTranslatorControlText(String original, String joined, RectF sourceRect) {
            if (ignoreRect == null || !RectF.intersects(sourceRect, ignoreRect)) return false;

            String compact = original == null ? "" : original.replace(" ", "").trim();
            int cjk = chineseCount(compact);

            // Ignore OCR from the floating control itself, but do not ignore nearby real
            // Chinese content. The control normally produces only a tiny isolated "中"
            // or mixed fragments from "A⇄中".
            if (cjk <= 1 && (compact.equals("中") || joined.contains("a⇄") || joined.contains("a e") || joined.contains("ae"))) {
                return true;
            }

            return false;
        }

        private boolean isLikelyNoise(OcrBox box, RectF sourceRect) {
            String original = box.text == null ? "" : box.text.trim();
            String english = box.english == null ? "" : box.english.trim();
            String joined = (original + " " + english).toLowerCase();

            if (!hasChinese(original)) return true;
            if (looksLikeTranslatorControlText(original, joined, sourceRect)) return true;
            if (sourceRect.bottom < STATUS_BAR_SKIP_PX) return true;
            if (sourceRect.width() < 14f || sourceRect.height() < 10f) return true;

            // Do not translate status/editor/phone/control OCR.
            return joined.contains("vpn") || joined.contains("battery") ||
                    joined.contains("phone") || joined.contains("iphone") ||
                    joined.contains("pixelate") || joined.contains("markup") ||
                    joined.contains("crop") || joined.contains("advanced edit") ||
                    joined.contains("a⇄") || joined.contains("ocr");
        }

        private String cleanedText(OcrBox box) {
            String value = box.english == null || box.english.trim().isEmpty()
                    ? box.text
                    : box.english;
            value = value.replace('\n', ' ').trim().replaceAll("\\s+", " ");
            if (value.isEmpty()) return "";

            // Keep UI replacement short enough to fit into the original visual footprint.
            int cjk = chineseCount(box.text);
            int maxChars = cjk <= 4 ? 18 : (cjk <= 10 ? 32 : 58);
            if (value.length() > maxChars) value = value.substring(0, maxChars - 1) + "…";
            return value;
        }

        private boolean isDocumentLikeItem(ReplacementItem item) {
            // Only treat true long text lines as document-like. Wide map labels and
            // card titles should stay tight or they cover neighboring Chinese text.
            int cjk = chineseCount(item.box.text);
            return cjk >= 14 && item.sourceRect.width() > getWidth() * 0.45f;
        }

        private int scoreBox(OcrBox box, RectF rect, String text) {
            int score = 0;
            int cjk = chineseCount(box.text);
            float area = rect.width() * rect.height();

            if (!text.equals(box.text)) score += 35;
            if (cjk <= 6) score += 12;
            if (rect.height() >= 18f) score += 10;
            if (rect.width() >= 42f) score += 8;
            if (area >= 700f) score += 8;
            if (area < 240f) score -= 18;

            String original = box.text == null ? "" : box.text;
            if (original.contains("购买") || original.contains("关注") || original.contains("评论") ||
                    original.contains("搜索") || original.contains("分享") || original.contains("收藏") ||
                    original.contains("支付") || original.contains("订单") || original.contains("发送")) {
                score += 22;
            }
            return score;
        }

        private ArrayList<ReplacementItem> buildItems(ScreenMap map) {
            ArrayList<ReplacementItem> items = new ArrayList<>();
            float h = getHeight();

            for (OcrBox box : boxes) {
                RectF sourceRect = mapBox(box, map, scrollOffsetY);

                // Use unscrolled rect only for noise/status test.
                RectF rawRect = mapBox(box, map, 0f);
                if (isLikelyNoise(box, rawRect)) continue;
                if (sourceRect.bottom < -40f || sourceRect.top > h + 40f) continue;

                String text = cleanedText(box);
                if (text.isEmpty()) continue;

                int priority = scoreBox(box, sourceRect, text);
                if (priority < 8) continue;

                items.add(new ReplacementItem(box, sourceRect, text, priority));
            }

            Collections.sort(items, new Comparator<ReplacementItem>() {
                @Override
                public int compare(ReplacementItem a, ReplacementItem b) {
                    return Integer.compare(b.priority, a.priority);
                }
            });

            int coverageLimit = Math.max(1, Math.round(items.size() * (coveragePercent / 100f)));
            int finalLimit = Math.min(MAX_VISIBLE_REPLACEMENTS, coverageLimit);
            if (items.size() > finalLimit) {
                return new ArrayList<>(items.subList(0, finalLimit));
            }
            return items;
        }

        private int sampleBackgroundColor(OcrBox box) {
            if (screenBitmap == null || screenBitmap.isRecycled()) {
                return Color.argb(210, 255, 255, 255);
            }

            int bw = screenBitmap.getWidth();
            int bh = screenBitmap.getHeight();
            int left = Math.max(0, Math.min(bw - 1, box.left));
            int top = Math.max(0, Math.min(bh - 1, box.top));
            int right = Math.max(left + 1, Math.min(bw, box.right));
            int bottom = Math.max(top + 1, Math.min(bh, box.bottom));

            long r = 0, g = 0, b = 0;
            int count = 0;

            int stepX = Math.max(1, (right - left) / 6);
            int stepY = Math.max(1, (bottom - top) / 4);

            // Sample around edges and just outside the box, avoiding the text-heavy center.
            for (int x = left; x < right; x += stepX) {
                int c1 = safePixel(screenBitmap, x, Math.max(0, top - 2));
                r += Color.red(c1); g += Color.green(c1); b += Color.blue(c1);
                count++;

                int y2 = Math.min(bh - 1, bottom + 2);
                int c2 = safePixel(screenBitmap, x, y2);
                r += Color.red(c2); g += Color.green(c2); b += Color.blue(c2);
                count++;
            }

            for (int y = top; y < bottom; y += stepY) {
                int c1 = safePixel(screenBitmap, Math.max(0, left - 2), y);
                r += Color.red(c1); g += Color.green(c1); b += Color.blue(c1);
                count++;

                int c2 = safePixel(screenBitmap, Math.min(bw - 1, right + 2), y);
                r += Color.red(c2); g += Color.green(c2); b += Color.blue(c2);
                count++;
            }

            if (count <= 0) return Color.argb(220, 255, 255, 255);
            return Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
        }

        private int safePixel(Bitmap bitmap, int x, int y) {
            try {
                return bitmap.getPixel(
                        Math.max(0, Math.min(bitmap.getWidth() - 1, x)),
                        Math.max(0, Math.min(bitmap.getHeight() - 1, y)));
            } catch (Exception e) {
                return Color.WHITE;
            }
        }


        private float luminance(int color) {
            return 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color);
        }

        private int choosePanelColor(int sampledBgColor) {
            return luminance(sampledBgColor) > 150f
                    ? Color.argb(255, 255, 255, 255)
                    : Color.argb(255, 12, 12, 12);
        }

        private int chooseTextColor(int panelColor) {
            return luminance(panelColor) > 150f ? Color.rgb(28, 28, 28) : Color.WHITE;
        }

        private int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        private ArrayList<String> wrapWords(String text, float maxWidth, int maxLines) {
            ArrayList<String> lines = new ArrayList<>();
            if (text == null || text.length() == 0) return lines;

            String[] words = text.split(" ");
            String current = "";

            for (String word : words) {
                if (word.length() == 0) continue;
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (textPaint.measureText(candidate) <= maxWidth) {
                    current = candidate;
                } else {
                    if (current.length() > 0) {
                        lines.add(current);
                    }
                    current = word;
                    if (lines.size() >= maxLines) break;
                }
            }

            if (lines.size() < maxLines && current.length() > 0) {
                lines.add(current);
            }

            if (lines.isEmpty()) {
                lines.add(text);
            }

            if (lines.size() == maxLines) {
                String last = lines.get(lines.size() - 1);
                while (last.length() > 3 && textPaint.measureText(last + "…") > maxWidth) {
                    last = last.substring(0, last.length() - 1);
                }
                if (!last.equals(lines.get(lines.size() - 1))) {
                    lines.set(lines.size() - 1, last + "…");
                }
            }
            return lines;
        }

        private TextFit fitText(String text, RectF rect, boolean documentLike) {
            float availableW = Math.max(1f, rect.width() - PAD_X * 2f);
            float availableH = Math.max(1f, rect.height() - PAD_Y * 2f);

            int maxLines;
            if (documentLike) {
                maxLines = availableH > 42f ? 2 : 1;
            } else {
                maxLines = 1;
                if (availableW > 180f && availableH > 34f && text.length() > 24) {
                    maxLines = 2;
                }
            }

            float startSize = Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, rect.height() * 0.70f));
            if (documentLike) {
                startSize = Math.min(startSize, 18f);
            }

            for (float size = startSize; size >= MIN_TEXT_SIZE; size -= 1f) {
                textPaint.setTextSize(size);
                ArrayList<String> lines = wrapWords(text, availableW, maxLines);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float lineH = fm.descent - fm.ascent + 1f;
                boolean widthOk = true;
                for (String line : lines) {
                    if (textPaint.measureText(line) > availableW + 0.5f) {
                        widthOk = false;
                        break;
                    }
                }
                if (widthOk && lineH * lines.size() <= availableH + 0.5f) {
                    return new TextFit(lines, size, true);
                }
            }

            textPaint.setTextSize(MIN_TEXT_SIZE);
            ArrayList<String> fallback = wrapWords(text, availableW, 1);
            if (!fallback.isEmpty()) {
                String line = fallback.get(0);
                while (line.length() > 3 && textPaint.measureText(line + "…") > availableW) {
                    line = line.substring(0, line.length() - 1);
                }
                fallback.clear();
                fallback.add(line + (line.length() < text.length() ? "…" : ""));
            }
            return new TextFit(fallback, MIN_TEXT_SIZE, false);
        }

        private void drawFallbackLabel(Canvas canvas, ReplacementItem item) {
            String text = item.text;
            fallbackTextPaint.setTextSize(20f);

            float width = Math.min(getWidth() - SAFE_MARGIN * 2f,
                    Math.max(84f, fallbackTextPaint.measureText(text) + 20f));
            float height = 32f;
            float left = Math.max(SAFE_MARGIN, Math.min(item.sourceRect.left, getWidth() - SAFE_MARGIN - width));
            float top = Math.max(SAFE_MARGIN, Math.min(item.sourceRect.top, getHeight() - SAFE_MARGIN - height));

            workRect.set(left, top, left + width, top + height);
            fallbackBgPaint.setColor(Color.argb(255, 24, 24, 24));
            canvas.drawRoundRect(workRect, 8f, 8f, fallbackBgPaint);
            Paint.FontMetrics fm = fallbackTextPaint.getFontMetrics();
            float y = workRect.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, workRect.centerX(), y, fallbackTextPaint);
        }

        private void drawReplacement(Canvas canvas, ReplacementItem item) {
            RectF source = new RectF(item.sourceRect);
            boolean documentLike = isDocumentLikeItem(item);

            float sourceH = Math.max(1f, source.height());
            float sourceW = Math.max(1f, source.width());

            // Adaptive sizing:
            // - short labels/buttons: cover only the OCR text plus small antialiasing margin
            // - long document lines: modestly taller, but no giant band
            float expandX = documentLike
                    ? Math.max(6f, sourceW * 0.025f)
                    : Math.max(3f, Math.min(8f, sourceW * 0.055f));
            float expandY = documentLike
                    ? Math.max(3f, Math.min(8f, sourceH * 0.28f))
                    : Math.max(2f, Math.min(6f, sourceH * 0.26f));

            RectF target = new RectF(source.left - expandX, source.top - expandY,
                    source.right + expandX, source.bottom + expandY);

            target.left = Math.max(0f, target.left);
            target.top = Math.max(0f, target.top);
            target.right = Math.min(getWidth(), target.right);
            target.bottom = Math.min(getHeight(), target.bottom);

            if (target.width() < 10f || target.height() < 8f) return;

            int sampledBg = sampleBackgroundColor(item.box);
            int panelColor = choosePanelColor(sampledBg);

            coverPaint.setStyle(Paint.Style.FILL);
            coverPaint.setAlpha(255);
            coverPaint.setColor(panelColor);
            float radius = Math.min(documentLike ? 4f : 7f, target.height() / 3f);
            canvas.drawRoundRect(target, radius, radius, coverPaint);

            int fgColor = chooseTextColor(panelColor);
            textPaint.setColor(fgColor);
            textPaint.setFakeBoldText(target.height() < 20f);

            RectF textArea = new RectF(target.left + PAD_X, target.top + PAD_Y,
                    target.right - PAD_X, target.bottom - PAD_Y);
            TextFit fit = fitText(item.text, textArea, documentLike);

            if (fit.lines.isEmpty()) return;
            if (!fit.fits && target.width() < 42f) {
                drawFallbackLabel(canvas, item);
                return;
            }

            textPaint.setTextSize(fit.textSize);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float lineH = fm.descent - fm.ascent + 1f;
            float totalH = lineH * fit.lines.size();
            float y = textArea.centerY() - totalH / 2f - fm.ascent;

            for (String line : fit.lines) {
                canvas.drawText(line, textArea.centerX(), y, textPaint);
                y += lineH;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // Do not call BaseBorderView.onDraw/drawBaseBorder here. Translation
            // overlay should only draw replacement boxes, not a full-screen wash/border.
            if (boxes.isEmpty()) {
                canvas.drawText("No Chinese text found", getWidth() / 2f, 92f, emptyPaint);
                return;
            }

            ScreenMap map = screenMap();
            ArrayList<ReplacementItem> items = buildItems(map);

            // Draw lower-priority items first, higher-priority items last.
            Collections.sort(items, new Comparator<ReplacementItem>() {
                @Override
                public int compare(ReplacementItem a, ReplacementItem b) {
                    return Integer.compare(a.priority, b.priority);
                }
            });

            for (ReplacementItem item : items) {
                drawReplacement(canvas, item);
            }
        }
    }

    // ===== Real-user-touch watcher =====
    // A 1x1 passive window with FLAG_WATCH_OUTSIDE_TOUCH receives ACTION_OUTSIDE for
    // any real finger-down anywhere on screen. Simulated app scroll/content events
    // never generate touches, so they can never clear the overlay. This replaces the
    // accessibility TYPE_TOUCH_INTERACTION_* gating, which most devices never deliver
    // to non-touch-exploration services (root cause of overlay never clearing).
    private android.view.View touchWatcherView;
    static volatile long lastControlTouchMs = 0L;
    static volatile long lastScreenTouchMs = 0L;
    private final android.os.Handler touchWatcherHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    static boolean wasRecentScreenTouch(long now) {
        return now - lastScreenTouchMs <= 1600L;
    }

    private void addTouchWatcher() {
        if (touchWatcherView != null) return;
        try {
            android.view.View v = new android.view.View(this);
            WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                    1, 1,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            wp.gravity = Gravity.TOP | Gravity.START;
            wp.x = 0;
            wp.y = 0;
            v.setOnTouchListener((view, event) -> {
                int a = event.getAction();
                if (a == MotionEvent.ACTION_OUTSIDE || a == MotionEvent.ACTION_DOWN) {
                    lastScreenTouchMs = System.currentTimeMillis();
                    // Small delay so a touch on the floating control (separate window)
                    // can register first and veto the clear.
                    touchWatcherHandler.postDelayed(() -> {
                        if (System.currentTimeMillis() - lastControlTouchMs > 600L) {
                            hideOverlayForPageChange();
                        }
                    }, 250L);
                }
                return false;
            });
            wm.addView(v, wp);
            touchWatcherView = v;
        } catch (Exception ignored) {}
    }

    private void removeTouchWatcher() {
        if (touchWatcherView == null) return;
        try { wm.removeView(touchWatcherView); } catch (Exception ignored) {}
        touchWatcherView = null;
    }

}
