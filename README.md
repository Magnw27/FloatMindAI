# FloatMind AI 1.1.0 — Android screen study assistant

A user-controlled floating study helper for Android. It requires explicit Android screen-capture consent for each session, shows an ongoing notification with a Stop action, captures a fresh frame only after the user taps Scan, performs OCR on-device with ML Kit, then sends extracted text (not the screenshot) to the user's OpenRouter account for an explanation.

## Privacy and intended use
- Designed for learning, reading and practice. It is not intended to provide answers to copy during a live graded exam or bypass a website's rules.
- No silent capture: Android's MediaProjection consent dialog is required for each session.
- A fresh frame is processed only after the user taps Scan. No screenshot is uploaded; only OCR text is sent to OpenRouter.
- Do not scan private messages, passwords, banking/payment screens, or sensitive information.
- OpenRouter key is encrypted with AES-GCM using an Android Keystore key. A rooted/compromised device can still expose app data; do not share a build containing your key.
- Some apps block screenshots. The app does not bypass protected-screen restrictions.

## Build prerequisites
- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Android Gradle Plugin 8.7.3 and Gradle 8.9
- Network access to download Gradle and Maven dependencies

AGP 8.7 requires Gradle 8.9 and JDK 17. If building with a local Gradle installation, verify `gradle --version` and use Gradle 8.9.

## Build on Debian proot (Termux)

```bash
apt update
apt install -y openjdk-17-jdk unzip wget
java -version
```

Install Android command-line tools from the official Android developer site. Place them at `$HOME/android-sdk/cmdline-tools/latest`, then:

```bash
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Install Gradle 8.9 and run from the project root:

```bash
gradle --version
gradle :app:assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

Copy to shared storage (after `termux-setup-storage` in Termux):

```bash
mkdir -p /sdcard/Download
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/FloatMindAI-1.1.0.apk
```

## First use on Android 15 / ColorOS
1. Install and open FloatMind AI.
2. Paste your own OpenRouter API key and enter a model slug enabled in your OpenRouter account.
3. Save settings. The key is encrypted locally with Android Keystore.
4. Tap Activate Floating AI and allow display-over-other-apps.
5. Accept Android's screen-sharing consent dialog.
6. Open a study/practice page, tap the AI bubble, then tap Scan.
7. Stop the session using the notification's Stop action or the app's Stop button.

If ColorOS suspends the app, allow FloatMind to run in the background / use the least restrictive battery setting available on your device. Do not disable Android's screen-capture or privacy indicators.

## Current limitations
- Prototype UI uses native Android views; it is intentionally dependency-light.
- OCR quality depends on screen resolution, font size, contrast and language.
- Text sent to the model is capped at 14,000 characters and response length at 900 tokens to control usage.
- Model availability, pricing and account limits are controlled by OpenRouter. The default model text is only a starting example; replace it with a model slug enabled in your account.
- This source package has not been compiled or device-tested in this environment. Build and test on the target phone before relying on it.
