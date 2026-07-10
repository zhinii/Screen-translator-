# Screen Translator Prototype - OCR Test with Normal/Taobao Modes

This version keeps the working interaction model and adds an OCR test step.

## What works in this build

- Floating Translate button.
- Translate button is movable and remembers its last position.
- Tap Translate once to activate.
- Tap Translate again to stop.
- Normal mode: tap/click/scroll hides the overlay when the Accessibility service is enabled.
- Taobao/stable mode: app events are ignored and the overlay stays until you tap Translate again or move the Translate button.
- No screen dimming layer.
- Screen border glows while active.
- One-shot screen capture through MediaProjection.
- Chinese OCR test using ML Kit's bundled Chinese text recognizer.
- Draws boxes around detected Chinese text lines.
- Captured frame is processed in memory and discarded. It is not saved to storage, Photos, or Downloads.

## What does not work yet

- English translation.
- Replacing Chinese text with English.

## Required permissions/settings

1. Enable Display over other apps / Appear on top.
2. Enable this app's Accessibility service for Normal mode tap/scroll stop behavior. Taobao/stable mode does not depend on auto-stop events.
3. In the app, enable Auto screen capture/OCR.
4. Grant screen capture for the session.
5. Show the floating Translate button.

## Build

Push this project to GitHub. The included GitHub Actions workflow builds `app-debug.apk` and uploads it as an artifact.


## Version 0.14 OCR stay fix
- OCR boxes remain visible until the user taps, clicks, or scrolls.
- Accessibility no longer listens to generic window/content-change events because OCR overlays, toast windows, split-screen changes, and app refreshes can trigger those without a user action.
- No auto-hide timer.


## Version 0.15 Normal/Taobao stop modes
- Added a Taobao/stable mode checkbox.
- Normal mode auto-stops on tap/click/scroll through Accessibility.
- Taobao/stable mode ignores noisy app refreshes and keeps OCR boxes visible until Translate is tapped again or the floating button is moved.
- Removed the extra strict mode idea; it was not useful for this workflow.


Update: floating Translate button is now a glowing circular translator bubble using the Chinese character "译". It shows a faint glow when idle and a stronger glow when active.


Update 0.15:
- Floating button is now a round A/中 translation bubble.
- Button is orange with orange glow when idle/off.
- Button changes to cyan/blue with stronger cyan glow when translation/OCR overlay is active.
- OCR boxes now show English translation labels when the ML Kit Chinese-to-English model is available.
- If the translation model is unavailable, common shopping/action phrases use a small built-in fallback dictionary and other text falls back to the original OCR text.
- Screenshots are still processed one-shot in memory and discarded.


Build fix: corrected ML Kit DownloadConditions import to com.google.mlkit.common.model.DownloadConditions.


## Manual connected-control build

Built from the stable early translation-label base rather than the later automatic-refresh versions.

Behavior:
- One connected floating pill: `[ A⇄中 | × ]`.
- The left side translates/reloads the current screen.
- The right side closes the translation overlay.
- The whole pill moves together as one overlay control.
- There is no automatic refresh from tap, scroll, swipe, Taobao updates, accessibility events, or overlay redraws.
- Old translation overlay remains visible while a manual reload is running.
- OCR/translation warm-up starts when the app opens and again after screen-capture permission is granted.
- Accessibility only updates foreground app category for app-aware dictionaries.

## Scroll-follow overlay build

Manual translation remains unchanged:
- Tap A⇄中 to translate/reload.
- Tap × to close.
- No automatic OCR refresh.

New passive overlay behavior:
- Accessibility scroll events shift the existing OCR translation overlay vertically with the screen.
- Window/page changes hide the translation overlay because the old boxes no longer match the new page.
- Accessibility still does not trigger OCR or translation.

## Build fix

Fixed scroll-follow code against the working connected-control base:
- Removed references to old automatic-refresh variables.
- Page/window change now calls the normal overlay hide method.
- Floating connected control remains visible.

## Color transition build

Connected control states:
- IDLE: left A⇄中 section is orange.
- SCANNING: left A⇄中 section animates smoothly between orange and blue while OCR/translation runs.
- ACTIVE: left A⇄中 section is blue.
- The × side stays red.


## Final clean overlay build

Replaces the OCR debugging overlay with a clean translation-label overlay:
- no debug title
- no "Chinese text boxes" counter
- no yellow OCR rectangles
- compact dark rounded translation pills with white text and a cyan accent bar
- collision reduction and visible-label limit
- scroll-follow remains supported

## Smart clean overlay build

Improves final overlay presentation:
- skips non-Chinese OCR noise such as VPN/status-bar/editor labels and the translator control itself
- uses compact pills for short UI labels and wider two-line cards for longer text
- lowers visible label count to reduce clutter
- uses per-label fixed-vs-scroll behavior so static header/footer/menu labels do not move with scrolling content
- improves scroll delta fallback so absolute scrollY is not treated as a movement delta

## In-place replacement overlay build

Replaces floating translation pills with an in-place UI replacement renderer:
- covers the original Chinese OCR area
- samples nearby screenshot pixels to approximate the original background color
- draws English inside the original text bounds
- dynamically shrinks/wraps English to fit the available area
- treats every OCR item as scrollable; no static menu/header/footer classification
- user manually taps A⇄中 to retranslate if the page has moved or changed

## OCR coverage adjustment

For in-place replacement, OCR coverage is increased:
- MAX_OCR_BOXES raised to 70
- long Chinese lines are allowed up to 80 characters before filtering
- visible in-place replacements raised to 60

## In-place replacement alignment fix

Fixes visible issues from first in-place replacement pass:
- replacement backgrounds are now fully opaque
- replacement rectangles expand around OCR boxes to fully cover Chinese text
- overlay mapping uses aspect-correct screen scaling with offsets instead of independent x/y scale
- text fitting gets slightly more space after the expanded cover area
- all items remain scrollable; no static menu classification

## Opaque panel style build

Makes the in-place overlay look closer to the example:
- fully opaque matte replacement panels
- dark/light panel selection based on the sampled area
- contrasting text color
- stronger cover expansion so the original Chinese does not show through

## Medium/High button build

Fixes the broken slider approach:
- removes numeric slider
- Medium/High translation amount is controlled from the floating button
- tap A⇄中 translates/reloads with the current mode
- long-press A⇄中 switches Medium/High
- button label shows M A⇄中 or H A⇄中
- Medium = 55% of detected OCR regions; High = 100%, capped by max visible replacements
- no hide on content-change events, so busy apps like Taobao/Meituan should not immediately clear the overlay
- scroll/click/window change hides stale overlay; user taps A⇄中 again to retranslate

## Document completeness / solid panel build

Changes:
- overlay no longer hides on scroll/click; it stays touch-through so the user can interact and scroll after translation
- overlay hides only on clearer window/page changes or ×
- panels use a 99% alpha matte color and are drawn twice for a more solid mask
- document-style Chinese lines are OCR-translated as whole lines instead of fragments
- MAX_OCR_BOXES increased to 100
- Medium mode increased to 70%; High remains 100%
- long/document replacements can use wider/taller cover boxes and up to 3 text lines

## Whole/Select region button build

Replaces Medium/High with region control:
- Floating control is now [ Whole/Select | A⇄中 | × ]
- Tap Whole/Select to switch between whole-screen and selection mode
- Whole mode: tap A⇄中 to translate/reload the full screen
- Select mode: tap A⇄中, drag a box around the area, release to OCR only that region
- The selection overlay temporarily captures touch only while drawing the box, then returns to touch-through translation overlay
- Removed Medium/High coverage behavior; coverage is 100% within the chosen region

## Whole/Select alignment fix

Changes:
- replaces aspect-fit overlay mapping with direct X/Y coordinate mapping
- reduces overly large replacement expansion so translated boxes sit closer to the original text
- selection mode keeps any OCR box intersecting the selected region
- keeps Whole/Select mode, opaque panels, and touch-through overlay behavior

## Alpha 255 / fold alignment build

Changes:
- all replacement panels now use alpha 255
- fallback panels use alpha 255
- overlay mapping now accounts for the overlay view's actual screen location
- selection-box normalization now also accounts for the overlay view's actual screen location
- this is intended to improve alignment on fold/open layouts and layouts with system-bar offsets

## Tight opaque boxes build

Changes:
- replacement panels are true alpha 255
- OcrBorderView no longer draws any full-screen border/wash
- replacement box expansion is adaptive and much tighter
- long/document classification is stricter so map labels, titles, and card text do not become huge bands
- text fit uses fewer lines and smaller document text to avoid overlapping neighboring UI text

## Restart and control-overlap fix

Changes:
- long-press × restarts the translator tool
- restart clears overlay, recreates the floating control, resets capture state, and asks for screen capture again
- text near the floating button is no longer ignored just because it overlaps the button region
- only OCR that looks like the translator control itself is ignored
- control ignore margin reduced from 12dp to 2dp

## Explicit user clear build

Changes:
- Translation overlay clears on explicit user scroll only when a real touch interaction is active/recent.
- Translation overlay clears on explicit user tap/click only when a real touch interaction is active/recent.
- App-generated Taobao/Meituan scroll/content events without a recent user touch are ignored.
- Window/page changes still clear stale overlay.
- × still closes overlay, and long-press × still restarts the tool.

## Swipe-up persistent / close-app / more OCR build

Changes:
- Whole-screen translation capacity increased:
  - MAX_OCR_BOXES: 180
  - MAX_VISIBLE_REPLACEMENTS: 120
  - longer OCR lines preserved up to 220 characters
  - longer split OCR parts allowed up to 28 characters
- Translation overlay remains onscreen after ordinary taps and page/app events.
- Translation overlay clears only on an explicit user upward scroll/swipe.
- App-generated Taobao/Meituan fake scroll/content events are ignored unless paired with a real recent user touch.
- The floating × now closes the translator application/tool instead of only hiding the current translation layer.
- Long-press × still restarts the translator tool.
