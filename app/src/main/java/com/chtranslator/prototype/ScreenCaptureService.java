package com.chtranslator.prototype;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class ScreenCaptureService extends Service {
    public static final String ACTION_START_PROJECTION = "com.chtranslator.prototype.START_PROJECTION";
    public static final String ACTION_CAPTURE_ONCE = "com.chtranslator.prototype.CAPTURE_ONCE";
    public static final String ACTION_WARM_UP = "com.chtranslator.prototype.WARM_UP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_SELECTION_ENABLED = "selection_enabled";
    public static final String EXTRA_SELECTION_LEFT = "selection_left";
    public static final String EXTRA_SELECTION_TOP = "selection_top";
    public static final String EXTRA_SELECTION_RIGHT = "selection_right";
    public static final String EXTRA_SELECTION_BOTTOM = "selection_bottom";

    private static final int NOTIFICATION_ID = 701;
    private static final String CHANNEL_ID = "screen_translator_capture";
    private static final int MAX_OCR_BOXES = 180;
    private static final int MIN_BOX_WIDTH_PX = 18;
    private static final int MIN_BOX_HEIGHT_PX = 14;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile boolean captureRequested = false;
    private volatile boolean ocrRunning = false;
    private volatile boolean selectionEnabled = false;
    private volatile float selectionLeft = 0f;
    private volatile float selectionTop = 0f;
    private volatile float selectionRight = 1f;
    private volatile float selectionBottom = 1f;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }

        if (ACTION_START_PROJECTION.equals(intent.getAction())) {
            startProjection(intent);
            return START_STICKY;
        }

        if (ACTION_WARM_UP.equals(intent.getAction())) {
            warmUpModels();
            return START_STICKY;
        }

        if (ACTION_CAPTURE_ONCE.equals(intent.getAction())) {
            selectionEnabled = intent.getBooleanExtra(EXTRA_SELECTION_ENABLED, false);
            selectionLeft = clamp01(intent.getFloatExtra(EXTRA_SELECTION_LEFT, 0f));
            selectionTop = clamp01(intent.getFloatExtra(EXTRA_SELECTION_TOP, 0f));
            selectionRight = clamp01(intent.getFloatExtra(EXTRA_SELECTION_RIGHT, 1f));
            selectionBottom = clamp01(intent.getFloatExtra(EXTRA_SELECTION_BOTTOM, 1f));

            if (selectionRight < selectionLeft) {
                float tmp = selectionLeft;
                selectionLeft = selectionRight;
                selectionRight = tmp;
            }
            if (selectionBottom < selectionTop) {
                float tmp = selectionTop;
                selectionTop = selectionBottom;
                selectionBottom = tmp;
            }

            requestOneFrame();
            return START_STICKY;
        }

        return START_STICKY;
    }

    private void warmUpModels() {
        try {
            TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            Bitmap warmBitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            recognizer.process(InputImage.fromBitmap(warmBitmap, 0))
                    .addOnCompleteListener(task -> {
                        try { recognizer.close(); } catch (Exception ignored) {}
                        try { warmBitmap.recycle(); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}

        try {
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.CHINESE)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build();
            Translator translator = Translation.getClient(options);
            DownloadConditions conditions = new DownloadConditions.Builder().build();
            translator.downloadModelIfNeeded(conditions)
                    .addOnCompleteListener(task -> {
                        try { translator.close(); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }

    private void startProjection(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            toast("Missing screen capture permission data");
            stopSelf();
            return;
        }

        startAsForeground();

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            toast("MediaProjectionManager unavailable");
            stopSelf();
            return;
        }

        stopProjectionOnly();
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            toast("Could not start screen capture session");
            stopSelf();
            return;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopProjectionOnly();
                stopSelf();
            }
        }, new Handler(Looper.getMainLooper()));

        startVirtualDisplaySession();
        warmUpModels();
        toast("Screen capture session active");
    }

    private void startAsForeground() {
        Notification notification = buildNotification("Screen capture session active", "Frames are OCR processed in memory and discarded.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String title, String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen capture",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the temporary screen capture session visible.");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startVirtualDisplaySession() {
        if (mediaProjection == null) return;

        ScreenSize size = getScreenSize();
        captureThread = new HandlerThread("screen-translator-capture-session");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            Bitmap rawBitmap = null;
            Bitmap bitmapForOcr = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;

                if (!captureRequested || ocrRunning) {
                    return;
                }
                captureRequested = false;
                ocrRunning = true;

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * size.width;
                int bitmapWidth = size.width + rowPadding / pixelStride;

                rawBitmap = Bitmap.createBitmap(bitmapWidth, size.height, Bitmap.Config.ARGB_8888);
                rawBitmap.copyPixelsFromBuffer(buffer);

                if (bitmapWidth != size.width) {
                    bitmapForOcr = Bitmap.createBitmap(rawBitmap, 0, 0, size.width, size.height);
                    rawBitmap.recycle();
                    rawBitmap = null;
                } else {
                    bitmapForOcr = rawBitmap;
                    rawBitmap = null;
                }

                runChineseOcr(bitmapForOcr, size.width, size.height);
                bitmapForOcr = null;
            } catch (Exception e) {
                captureRequested = false;
                ocrRunning = false;
                MainActivity.showOcrMessageFromService("OCR/capture failed: " + e.getClass().getSimpleName());
                toast("OCR/capture failed: " + e.getClass().getSimpleName());
            } finally {
                if (rawBitmap != null) rawBitmap.recycle();
                if (bitmapForOcr != null) bitmapForOcr.recycle();
                if (image != null) image.close();
            }
        }, captureHandler);

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenTranslatorSession",
                    size.width,
                    size.height,
                    size.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    captureHandler);
        } catch (Exception e) {
            toast("Virtual display failed: " + e.getClass().getSimpleName());
            stopProjectionOnly();
            stopSelf();
        }
    }

    private void runChineseOcr(Bitmap bitmap, int width, int height) {
        final Bitmap ownedBitmap = bitmap;
        Bitmap copyForOverlay = null;
        try {
            copyForOverlay = ownedBitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Exception ignored) {}
        final Bitmap overlayBitmap = copyForOverlay;

        TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        InputImage inputImage = InputImage.fromBitmap(ownedBitmap, 0);

        recognizer.process(inputImage)
                .addOnSuccessListener(text -> {
                    ArrayList<MainActivity.OcrBox> boxes = extractChineseBoxes(text);
                    boxes = filterBoxesForSelection(boxes, width, height);
                    translateBoxesAndShow(boxes, width, height, overlayBitmap);
                })
                .addOnFailureListener(e -> {
                    if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
                        try { overlayBitmap.recycle(); } catch (Exception ignored) {}
                    }
                    MainActivity.showOcrMessageFromService("OCR failed: " + e.getClass().getSimpleName());
                    toast("OCR failed: " + e.getClass().getSimpleName());
                    ocrRunning = false;
                })
                .addOnCompleteListener(task -> {
                    try { recognizer.close(); } catch (Exception ignored) {}
                    try { ownedBitmap.recycle(); } catch (Exception ignored) {}
                });
    }

    private void translateBoxesAndShow(ArrayList<MainActivity.OcrBox> boxes, int width, int height, Bitmap screenBitmap) {
        if (boxes == null || boxes.isEmpty()) {
            MainActivity.showOcrResultsFromService(new ArrayList<>(), width, height, screenBitmap);
            ocrRunning = false;
            return;
        }

        ArrayList<MainActivity.OcrBox> translated = new ArrayList<>();
        ArrayList<MainActivity.OcrBox> needsMachineTranslation = new ArrayList<>();

        for (MainActivity.OcrBox box : boxes) {
            String dictionary = dictionaryFallback(box.text);
            if (dictionary != null && !dictionary.equals(box.text)) {
                translated.add(new MainActivity.OcrBox(
                        box.text,
                        dictionary,
                        box.left,
                        box.top,
                        box.right,
                        box.bottom));
            } else {
                needsMachineTranslation.add(box);
            }
        }

        if (needsMachineTranslation.isEmpty()) {
            MainActivity.showOcrResultsFromService(translated, width, height, screenBitmap);
            ocrRunning = false;
            return;
        }

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build();
        Translator translator = Translation.getClient(options);
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> translateBoxAtIndex(translator, needsMachineTranslation, translated, 0, width, height, screenBitmap))
                .addOnFailureListener(e -> {
                    for (MainActivity.OcrBox box : needsMachineTranslation) {
                        translated.add(new MainActivity.OcrBox(
                                box.text,
                                box.text,
                                box.left,
                                box.top,
                                box.right,
                                box.bottom));
                    }
                    MainActivity.showOcrResultsFromService(translated, width, height, screenBitmap);
                    toast("Translation model unavailable; showing dictionary/original labels");
                    ocrRunning = false;
                    try { translator.close(); } catch (Exception ignored) {}
                });
    }

    private void translateBoxAtIndex(Translator translator,
                                     ArrayList<MainActivity.OcrBox> source,
                                     ArrayList<MainActivity.OcrBox> translated,
                                     int index,
                                     int width,
                                     int height,
                                     Bitmap screenBitmap) {
        if (index >= source.size()) {
            MainActivity.showOcrResultsFromService(translated, width, height, screenBitmap);
            ocrRunning = false;
            try { translator.close(); } catch (Exception ignored) {}
            return;
        }

        MainActivity.OcrBox box = source.get(index);
        translator.translate(box.text)
                .addOnSuccessListener(english -> {
                    translated.add(new MainActivity.OcrBox(
                            box.text,
                            cleanTranslation(english, box.text),
                            box.left,
                            box.top,
                            box.right,
                            box.bottom));
                    translateBoxAtIndex(translator, source, translated, index + 1, width, height, screenBitmap);
                })
                .addOnFailureListener(e -> {
                    translated.add(new MainActivity.OcrBox(
                            box.text,
                            box.text,
                            box.left,
                            box.top,
                            box.right,
                            box.bottom));
                    translateBoxAtIndex(translator, source, translated, index + 1, width, height, screenBitmap);
                });
    }

    private String cleanTranslation(String english, String original) {
        String dictionary = dictionaryFallback(original);
        if (dictionary != null && !dictionary.equals(original)) return dictionary;
        if (english == null || english.trim().isEmpty()) return original == null ? "" : original;
        return english.trim();
    }

    private String dictionaryFallback(String text) {
        if (text == null) return "";
        String compact = text.replace(" ", "").replace("\n", "").trim();
        String category = MainActivity.areAppAwareDictionariesEnabled()
                ? MainActivity.getCurrentAppCategory()
                : "general";

        String categoryHit = categoryDictionary(compact, category);
        if (categoryHit != null) return categoryHit;

        String generalHit = categoryDictionary(compact, "general");
        if (generalHit != null) return generalHit;

        return text;
    }

    private String categoryDictionary(String compact, String category) {
        if (compact == null) return null;

        if ("shopping".equals(category)) {
            if (compact.contains("立即购买")) return "Buy now";
            if (compact.contains("加入购物车")) return "Add to cart";
            if (compact.contains("确认支付")) return "Confirm payment";
            if (compact.contains("提交订单")) return "Submit order";
            if (compact.contains("下单")) return "Place order";
            if (compact.contains("查看订单")) return "View order";
            if (compact.contains("查看详情")) return "View details";
            if (compact.contains("收货地址")) return "Shipping address";
            if (compact.contains("支付成功")) return "Payment successful";
            if (compact.contains("订单已提交")) return "Order submitted";
            if (compact.contains("联系客服")) return "Contact seller";
            if (compact.contains("七天无理由退货")) return "7-day no-reason return";
            if (compact.contains("包邮")) return "Free shipping";
            if (compact.contains("已售")) return "Sold";
            if (compact.contains("月销")) return "Monthly sales";
            if (compact.contains("优惠券") || compact.equals("券")) return "Coupon";
            if (compact.contains("规格")) return "Options";
            if (compact.contains("商品名称")) return "Product name";
            if (compact.contains("价格")) return "Price";
            if (compact.contains("配送")) return "Delivery";
            if (compact.contains("售后")) return "After-sales service";
        }

        if ("social".equals(category)) {
            if (compact.contains("取消关注")) return "Unfollow";
            if (compact.contains("关注")) return "Follow";
            if (compact.contains("点赞")) return "Like";
            if (compact.contains("评论")) return "Comment";
            if (compact.contains("转发") || compact.contains("分享")) return "Share";
            if (compact.contains("私信")) return "Message";
            if (compact.contains("收藏")) return "Save";
            if (compact.contains("粉丝")) return "Followers";
            if (compact.contains("发布")) return "Post";
            if (compact.contains("回复")) return "Reply";
        }

        if ("news".equals(category)) {
            if (compact.contains("新闻")) return "News";
            if (compact.contains("头条")) return "Headlines";
            if (compact.contains("推荐")) return "Recommended";
            if (compact.contains("热点")) return "Trending";
            if (compact.contains("评论")) return "Comments";
            if (compact.contains("阅读")) return "Reads";
            if (compact.contains("来源")) return "Source";
            if (compact.contains("责任编辑")) return "Editor";
            if (compact.contains("发布于")) return "Published";
        }

        if ("documents".equals(category)) {
            if (compact.contains("另存为")) return "Save as";
            if (compact.contains("打开")) return "Open";
            if (compact.contains("保存")) return "Save";
            if (compact.contains("编辑")) return "Edit";
            if (compact.contains("复制")) return "Copy";
            if (compact.contains("粘贴")) return "Paste";
            if (compact.contains("删除")) return "Delete";
            if (compact.contains("导出")) return "Export";
            if (compact.contains("打印")) return "Print";
            if (compact.contains("批注")) return "Comment";
            if (compact.contains("文档")) return "Document";
            if (compact.contains("页面")) return "Page";
        }

        if ("general".equals(category)) {
            if (compact.contains("确认")) return "Confirm";
            if (compact.contains("取消")) return "Cancel";
            if (compact.contains("返回")) return "Back";
            if (compact.contains("下一步")) return "Next";
            if (compact.contains("完成")) return "Done";
            if (compact.contains("设置")) return "Settings";
            if (compact.contains("登录")) return "Log in";
            if (compact.contains("注册")) return "Sign up";
            if (compact.contains("搜索")) return "Search";
            if (compact.contains("支付")) return "Pay";
        }

        return null;
    }

    private ArrayList<MainActivity.OcrBox> extractChineseBoxes(Text text) {
        ArrayList<MainActivity.OcrBox> boxes = new ArrayList<>();
        if (text == null) return boxes;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText();
                Rect lineRect = line.getBoundingBox();
                if (lineText == null || lineRect == null) continue;
                if (!containsChinese(lineText)) continue;

                int chineseCount = countChineseChars(lineText);

                // Document/page text should translate as a full line. Element-level OCR
                // creates tiny fragments like "Confirm" or "Follow" and misses meaning.
                if (chineseCount >= 8 || (lineRect.width() >= 260 && chineseCount >= 5)) {
                    addWholeChineseLineBox(boxes, lineText, lineRect);
                    if (boxes.size() >= MAX_OCR_BOXES) return boxes;
                    continue;
                }

                boolean addedElement = false;
                for (Text.Element element : line.getElements()) {
                    String elementText = element.getText();
                    Rect er = element.getBoundingBox();
                    if (elementText == null || er == null) continue;
                    if (!containsChinese(elementText)) continue;

                    int before = boxes.size();
                    addSpaceAwareChineseBoxes(boxes, elementText, er);
                    if (boxes.size() > before) addedElement = true;
                    if (boxes.size() >= MAX_OCR_BOXES) return boxes;
                }

                if (!addedElement) {
                    addSpaceAwareChineseBoxes(boxes, lineText, lineRect);
                    if (boxes.size() >= MAX_OCR_BOXES) return boxes;
                }
            }
        }
        return boxes;
    }

    private void addWholeChineseLineBox(ArrayList<MainActivity.OcrBox> boxes, String value, Rect r) {
        if (boxes.size() >= MAX_OCR_BOXES) return;
        if (r == null) return;
        if (r.width() < MIN_BOX_WIDTH_PX || r.height() < MIN_BOX_HEIGHT_PX) return;

        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return;
        if (!containsChinese(trimmed)) return;

        // Allow longer document lines, but avoid sending absurd OCR blobs.
        if (trimmed.length() > 220) trimmed = trimmed.substring(0, 220);

        boxes.add(new MainActivity.OcrBox(trimmed, r.left, r.top, r.right, r.bottom));
    }

    private void addSpaceAwareChineseBoxes(ArrayList<MainActivity.OcrBox> boxes, String value, Rect r) {
        if (boxes.size() >= MAX_OCR_BOXES) return;
        if (r == null) return;
        if (r.width() < MIN_BOX_WIDTH_PX || r.height() < MIN_BOX_HEIGHT_PX) return;

        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return;
        if (trimmed.length() > 220 && !trimmed.contains(" ")) return;

        String[] parts = trimmed.split("\\s+");
        if (parts.length <= 1) {
            boxes.add(new MainActivity.OcrBox(trimmed, r.left, r.top, r.right, r.bottom));
            return;
        }

        int totalChars = 0;
        for (String part : parts) {
            if (containsChinese(part)) totalChars += Math.max(1, part.length());
        }

        if (totalChars <= 0) return;

        int currentLeft = r.left;
        int availableWidth = Math.max(1, r.width());
        for (String part : parts) {
            if (boxes.size() >= MAX_OCR_BOXES) return;
            if (!containsChinese(part)) continue;
            if (part.length() > 28) continue;

            int partWidth = Math.max(8, Math.round(availableWidth * (part.length() / (float) totalChars)));
            int right = Math.min(r.right, currentLeft + partWidth);
            if ((right - currentLeft) >= MIN_BOX_WIDTH_PX) {
                boxes.add(new MainActivity.OcrBox(part, currentLeft, r.top, right, r.bottom));
            }
            currentLeft = right + 4;
        }
    }

    private int countChineseChars(String s) {
        if (s == null) return 0;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u3400' && c <= '\u4DBF') ||
                    (c >= '\u4E00' && c <= '\u9FFF') ||
                    (c >= '\uF900' && c <= '\uFAFF')) {
                count++;
            }
        }
        return count;
    }

    private boolean containsChinese(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u3400' && c <= '\u4DBF') ||
                    (c >= '\u4E00' && c <= '\u9FFF') ||
                    (c >= '\uF900' && c <= '\uFAFF')) {
                return true;
            }
        }
        return false;
    }

    private float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }

    private ArrayList<MainActivity.OcrBox> filterBoxesForSelection(ArrayList<MainActivity.OcrBox> boxes, int width, int height) {
        if (!selectionEnabled || boxes == null || boxes.isEmpty()) return boxes;

        int left = Math.round(selectionLeft * width);
        int top = Math.round(selectionTop * height);
        int right = Math.round(selectionRight * width);
        int bottom = Math.round(selectionBottom * height);

        ArrayList<MainActivity.OcrBox> filtered = new ArrayList<>();
        for (MainActivity.OcrBox box : boxes) {
            boolean intersects = box.right >= left && box.left <= right && box.bottom >= top && box.top <= bottom;
            if (intersects) {
                filtered.add(box);
            }
        }
        return filtered;
    }

    private void requestOneFrame() {
        if (mediaProjection == null || virtualDisplay == null || imageReader == null) {
            toast("Screen capture session is not active");
            MainActivity.showOcrMessageFromService("Screen capture session is not active");
            return;
        }
        if (ocrRunning) {
            toast("OCR is still running");
            return;
        }
        captureRequested = true;
        if (captureHandler != null) {
            captureHandler.postDelayed(() -> {
                if (captureRequested) {
                    captureRequested = false;
                    MainActivity.showOcrMessageFromService("Capture timed out. Tap A⇄中 to try again.");
                    toast("Capture timed out");
                }
            }, 6000);
        }
    }

    private ScreenSize getScreenSize() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return new ScreenSize(bounds.width(), bounds.height(), metrics.densityDpi);
        }

        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            return new ScreenSize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
        }

        return new ScreenSize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
    }

    private void stopProjectionOnly() {
        captureRequested = false;
        ocrRunning = false;
        MediaProjection projectionToStop = mediaProjection;
        mediaProjection = null;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        imageReader = null;
        try { if (captureThread != null) captureThread.quitSafely(); } catch (Exception ignored) {}
        captureThread = null;
        captureHandler = null;
        try { if (projectionToStop != null) projectionToStop.stop(); } catch (Exception ignored) {}
    }

    private void toast(String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopProjectionOnly();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class ScreenSize {
        final int width;
        final int height;
        final int densityDpi;

        ScreenSize(int width, int height, int densityDpi) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
        }
    }
}
