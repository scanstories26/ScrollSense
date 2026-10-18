# ScrollSense — know every scroll

Counts Instagram Reels and YouTube Shorts and shows a draggable floating counter over any app.

## Features
- Floating counter (drag to move, tap to collapse) + on/off switch in the app
- Counts reset to 0 at midnight; last 15 days kept (chart in the app), older data auto-deleted
- Setup screen with status for Accessibility, Battery and Restricted-settings
- Fixed signing key (app/scrollsense-debug.keystore) so every new build installs over the old one
- Diagnostics panel in the app for troubleshooting detection

## Build online
Push to GitHub; the workflow builds the APK. Actions > latest run > Artifacts > Reelect-APK.
(Workflow file lives at .github/workflows/build.yml in the repo root, project folder = Reelect.)

## First run on phone
1. Open ScrollSense > "Enable tracking" > Installed apps > ScrollSense > ON.
   Android 13+: if greyed out, App info > ⋮ > Allow restricted settings.
2. Tap "Allow unrestricted battery".
