# Reelect — know how much you scroll

Counts Instagram Reels and YouTube Shorts and shows a draggable floating counter over any app.

## Build the APK
1. Install Android Studio (Koala or newer) and open this folder.
2. Let Gradle sync, then Build > Build Bundle(s)/APK(s) > Build APK(s).
3. APK: app/build/outputs/apk/debug/app-debug.apk  — install on your phone.

## First run
1. Open Reelect > "Open Accessibility Settings" > Reelect > turn ON.
   (Android 13+: if greyed out, App info > ⋮ > "Allow restricted settings" first.)
2. Use the "Show floating counter" switch to hide / unhide the pill. Drag the pill anywhere.

## How it works
- AccessibilityService limited to com.instagram.android and com.google.android.youtube.
- Counts +1 when you enter the Reels/Shorts player and +1 each time a swipe settles for 0.8 s.
- Overlay uses TYPE_ACCESSIBILITY_OVERLAY, so no "draw over other apps" permission is needed.
- Daily counts are saved locally; anything older than 15 days is auto-deleted.
- No internet permission, nothing leaves the device.

## If counting stops after an app update
Instagram/YouTube may rename internal view ids. Use Android Studio > Layout Inspector (or uiautomatorviewer)
on the Reels/Shorts screen and update IG_IDS / YT_IDS in CounterService.kt.

## Build online (no install)
Push this folder to a GitHub repo. The workflow in .github/workflows/build.yml builds the APK automatically.
Open the repo > Actions tab > latest run > download the "Reelect-APK" artifact.
