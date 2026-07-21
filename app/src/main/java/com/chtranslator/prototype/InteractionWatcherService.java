package com.chtranslator.prototype;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

public class InteractionWatcherService extends AccessibilityService {
    private static final long USER_TOUCH_WINDOW_MS = 1600L;
    private static final long HIDE_DEBOUNCE_MS = 350L;

    private String lastWindowKey = "";
    private boolean userTouchActive = false;
    private boolean scrolledDuringTouch = false;
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
            scrolledDuringTouch = false;
            lastUserTouchMs = now;
            return;
        }

        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            if (scrolledDuringTouch) {
                hideStaleOverlay();
            }
            userTouchActive = false;
            scrolledDuringTouch = false;
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (isExplicitUserTouch(now)) {
                scrolledDuringTouch = true;
                hideStaleOverlay();
            }
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (isExplicitUserTouch(now)) {
                hideStaleOverlay();
            }
            return;
        }

        // Keep page/window changes as a safety clear, but still require a new window key.
        // This catches real navigation while avoiding repeated noisy events.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String key = pkg + "|" + safeText(event.getClassName()) + "|" + safeText(event.getContentDescription());
            if (!key.equals(lastWindowKey)) {
                lastWindowKey = key;
                hideStaleOverlay();
            }
        }
    }

    private boolean isExplicitUserTouch(long now) {
        // TYPE_TOUCH_INTERACTION_* events are not delivered to services without
        // touch-exploration mode on most devices, so also trust the in-app
        // touch watcher's real finger-down timestamp.
        return userTouchActive
                || (now - lastUserTouchMs <= USER_TOUCH_WINDOW_MS)
                || MainActivity.wasRecentScreenTouch(now);
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
        // Manual mode: no OCR work to interrupt.
    }
}
