# Swift Browser

A lightweight Android browser starter project with a Brave-inspired dark UI, Android System WebView, tabs, bookmarks, history, downloads, share, private mode, and a small built-in tracker blocklist.

## Requirements

- Linux (Ubuntu 22.04/24.04 should work)
- JDK 17
- Android SDK (Platform 35 and Build Tools 35.0.0)
- Android Studio command-line tools, or Android Studio once to install SDK components
- Gradle wrapper can be generated locally (the wrapper JAR is intentionally not committed)

## Open in VSCodium

Open this folder in VSCodium. Install the Kotlin and Gradle extensions if desired. Set `ANDROID_HOME` to your Android SDK path and make sure `platform-tools` is on `PATH`.

Example for Ubuntu:
```bash
sudo apt update
sudo apt install openjdk-17-jdk unzip
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"
```

Install Android SDK packages:
```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Generate the Gradle wrapper if Gradle is installed:
```bash
gradle wrapper --gradle-version 8.11.1
```

Build a debug APK:
```bash
./gradlew assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

Install on a connected Android device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project size

The source project is intentionally small (no Chromium checkout, NDK, or bundled browser engine). The WebView renderer comes from Android System WebView / Chrome installed on the device. Gradle dependencies and the generated APK are separate from this source tree.

## Included controls

- Address/search bar and Go
- Back, forward, reload, home
- Tab switcher and new tab
- Bookmark toggle and bookmarks list
- History list and clear history
- Downloads through Android DownloadManager
- Share current page
- Privacy shield with blocking count and enable/disable switch
- Private browsing toggle (does not save local history/bookmarks; it is not a guarantee of anonymity)
- Settings panel
- Dark Brave-inspired browser chrome

## Tracker blocking notes

`WebViewClient.shouldInterceptRequest()` is used to block known tracking hosts and common analytics endpoints. This is a lightweight application-level filter, not a Chromium network-stack modification. WebView interception has limitations: it is not a complete replacement for uBlock Origin or Brave Shields, and redirects / service workers / some browser-internal requests may not be covered. Keep the blocklist maintained and test sites before release.

The included list is intentionally conservative to reduce breakage. No filter list is downloaded automatically. You can edit `app/src/main/assets/trackers.txt`.

## Important release work

Before publishing:
- Add a real app icon and final branding.
- Add automated tests for URL handling, filtering, tab lifecycle and downloads.
- Review WebView safe browsing and file access settings.
- Add a privacy policy and disclose the limited filtering behavior.
- Test on Android 8 through current Android releases and multiple System WebView versions.
- Consider using a maintained filter engine if you need EasyList syntax and cosmetic filtering.


## UI upgrade (v1.1 source iteration)

This iteration adds:
- Home/start page with search prompt, quick links, bookmarks and recent pages.
- Animated tab switcher with active-tab styling, tab counts and close/new-tab actions.
- Privacy shield dashboard with tracker count, protection toggle and private-mode toggle.
- Address bar clear/go actions, copy-current-link action, and suggestions from local bookmarks/history.
- Animated panel transitions and improved touch targets.

The tabs are UI-level tab records backed by a single WebView instance in this starter. They do not yet preserve independent page state, cookies, or back/forward stacks per tab. The private-mode switch is also a basic privacy convenience, not a hardened separate profile: cookie clearing affects the shared WebView cookie store. These limitations should be addressed before production release.
