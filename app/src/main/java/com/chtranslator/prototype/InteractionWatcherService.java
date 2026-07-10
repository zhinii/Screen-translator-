package com.chtranslator.prototype;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

public class InteractionWatcherService extends AccessibilityService {
    private static final long USER_TOUCH_WINDOW_MS = 1800L;
    private static final long HIDE_DEBOUNCE_MS = 500L;
    private static final int MIN_UP_SCROLL_DELTA = 12;

    private String lastWindowKey = "";
    private String lastScrollKey = "";
    private int lastScrollY = -1;
    private boolean userTouchActive = false;
    private long lastUserTouchMs = 0L;
    private long lastHideMs = 0L;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence packageName = event.getPackageName();
        String pkg = packageName == null ? "" : packageName.toString();

        if (pkg.equals(getPackageName())) {
            return;
        }

        if (!pkg.isEmpty()) {
            MainActivity.updateForegroundPackage(pkg);
        }

        int type = event.getEventType();
        long now = System.currentTimeMillis();

        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            userTouchActive = true;
            lastUserTouchMs = now;
            return;
        }

        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            userTouchActive = false;
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (isExplicitUserTouch(now) && isLikelySwipeUpScroll(event)) {
                hideStaleOverlay();
            }
            return;
        }

        // Do not clear on ordinary taps/clicks. The user asked for translations
        // to stay onscreen until an upward swipe or the floating × close button.

        // For real page/window changes, keep the translation onscreen unless the
        // app reports a genuinely different top window. This avoids Taobao-style
        // simulated content updates clearing the overlay.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String key = pkg + "|" + safeText(event.getClassName()) + "|" + safeText(event.getContentDescription());
            if (!key.equals(lastWindowKey)) {
                lastWindowKey = key;
                // Do not clear here in persistent mode. User can clear with swipe-up or ×.
            }
        }
    }

    private boolean isExplicitUserTouch(long now) {
        return userTouchActive
                || (now - lastUserTouchMs <= USER_TOUCH_WINDOW_MS)
                || MainActivity.wasRecentScreenTouch(now);
    }

    private boolean isLikelySwipeUpScroll(AccessibilityEvent event) {
        // User swipe-up usually makes the underlying content scroll downward,
        // which Android commonly reports as positive deltaY/scrollY movement.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                int dy = event.getScrollDeltaY();
                if (dy != 0) return dy > MIN_UP_SCROLL_DELTA;
            } catch (Exception ignored) {}
        }

        String key = safeText(event.getPackageName()) + "|" + safeText(event.getClassName());
        int y;
        try {
            y = event.getScrollY();
        } catch (Exception ignored) {
            // Some apps do not expose scroll direction. If it is a real touch scroll
            // and direction is unknown, treat it as explicit clear rather than keeping stale text.
            return true;
        }

        if (!key.equals(lastScrollKey)) {
            lastScrollKey = key;
            lastScrollY = y;
            return false;
        }

        if (lastScrollY < 0) {
            lastScrollY = y;
            return false;
        }

        int delta = y - lastScrollY;
        lastScrollY = y;

        if (Math.abs(delta) > 2500) return false;
        return delta > MIN_UP_SCROLL_DELTA;
    }

    private void hideStaleOverlay() {
        long now = System.currentTimeMillis();
        if (now - lastHideMs < HIDE_DEBOUNCE_MS) return;
        lastHideMs = now;
        MainActivity.hideOverlayForPageChange();
    }

    private String safeText(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    @Override
    public void onInterrupt() {
        // Manual persistent mode: no OCR work to interrupt.
    }
}
