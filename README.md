# FloatMind AI 2.0.0

FloatMind is an Android floating study companion. It captures a screen frame only after the user taps Scan, performs OCR locally with ML Kit, and sends extracted text to the AI provider selected by the user.

## Highlights

- Redesigned native Android interface with a dark gradient and translucent glass-style cards.
- Provider support:
  - OpenRouter (OpenAI-compatible Chat Completions and automatic model discovery).
  - Google Gemini API (native Gemini models endpoint and generateContent API).
  - 9Router, OmniRoute, and other OpenAI-compatible gateways through a configurable HTTPS Base URL.
- Automatic model discovery. OpenRouter models marked with `:free` or zero prompt/completion pricing are prioritized. Other OpenAI-compatible endpoints prioritize IDs ending in `:free`.
- Gemini model discovery lists models that support `generateContent`. The Gemini API model-list response does not expose pricing, so free-tier availability must be checked against Google's current pricing and account quota.
- Optional two-pass answer refinement: first response draft, second response review/correction. This uses two model requests and may double latency and token usage.
- Check config verifies model discovery and sends a small test prompt before starting a floating session.
- API keys are encrypted with AES-GCM using Android Keystore.
- Model identifiers are stored as returned by the provider, including suffixes such as `:free`.

## Provider setup

1. Select OpenRouter, Gemini API, or OpenAI-compatible.
2. Enter the provider API key. For a local gateway that does not require a key, leave it blank.
3. For 9Router / OmniRoute, paste the **HTTPS Public URL** from Cloudflare Quick Tunnel (for example `https://random-name.trycloudflare.com`) into the Base URL field. You do not need to add `/v1`; FloatMind appends it automatically. If the tunnel URL changes after restarting the free Quick Tunnel, update the URL in the app and scan models again.
4. Tap **Scan model**, select a model, then tap **Check config**.
5. Save the configuration and activate Floating AI.

Only paste a tunnel URL that you control or trust. API requests, including any configured API key, are sent to that endpoint. Keep the Cloudflare tunnel online while using the app. A model appearing in the provider's model list does not guarantee that it is free, enabled for your account, or suitable for chat completions. Provider pricing, quotas, regional access, and rate limits can change. Always verify current terms with the provider. OpenRouter free models are prioritized when the API reports free pricing.

## Two-pass response mode

When enabled, FloatMind sends the OCR text to the selected model to create a draft. It then sends the source text and draft to the same model for review. The second stage checks unsupported claims, reasoning gaps, and clarity. This improves the opportunity to catch mistakes but cannot guarantee correctness. Disable it to reduce latency and usage.

## Privacy and intended use

- Android's MediaProjection consent is required for each capture session.
- A frame is processed only after the user taps Scan.
- OCR runs on-device. The screenshot itself is not uploaded; extracted text is sent to the selected provider.
- Do not scan passwords, private messages, payment screens, or sensitive information.
- API keys are encrypted with Android Keystore. A rooted or compromised device may still expose app data.
- The app does not bypass screenshot protections.
- Use the app to understand study material, not to copy answers during a live graded assessment.

## Build

Requirements: JDK 17, Android SDK Platform 35, Build Tools 35.0.0, Android Gradle Plugin 8.7.3, Gradle 8.9.

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## About

Developed by Arif, a student developer from Indonesia.
