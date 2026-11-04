# Voice Agent — project context

A free, Siri-like voice assistant for the owner's **Samsung Galaxy A16 5G** (One UI, Android 14/15, India, Indian English).
Goal: open it like Siri (side button / corner swipe), speak naturally, and have it reliably *understand and complete* tasks:
calls, messages (SMS + WhatsApp), alarms, timers, reminders, calendar, apps, music, maps, device controls, and general questions.
Hard constraint: **costs nothing to run.** No paid APIs, no subscriptions.

## Owner's rules (always follow)

- **Never add Claude/AI attribution** anywhere: no `Co-Authored-By` trailers, no "Generated with Claude" lines,
  no AI credit in commits, PRs, code comments or docs. The owner commits and pushes **themselves**.
  Do not commit or push unless the owner explicitly asks in that moment.
- Never commit API keys. Keys are entered in the app at runtime (or kept in the git-ignored `local.properties` for tests).
- Explain things in plain language; the owner is not an Android developer.

## History so far (v1.0 → v1.2, built remotely without an Android SDK or a phone)

- v1.0: Java app, no dependencies. `AssistantActivity` overlay (glowing orb), Android `SpeechRecognizer` + `TextToSpeech`,
  rule-based understanding in `Brain.java` (regexes), actions via standard Android intents.
- v1.1: better speech handling (prefers Google's recognizer, engine fallback, N-best guesses, partial results, `en-IN`),
  natural-phrasing rewrites, sound-alike contact matching, unit/currency conversions.
- v1.2: optional **smart mode**: Gemini (free API key from aistudio.google.com) turns speech into one JSON action;
  `Brain.perform()` executes it. On the phone this failed with "couldn't reach Gemini".
- Uncommitted fix after v1.2 (in this repo now, untested against the real API): the Gemini client was sending
  `thinkingBudget: 0`, which Gemini 3.x rejects. The retry ran without a thinking setting, thinking used up
  `maxOutputTokens: 700`, and the reply came back empty. Now it tries `thinkingLevel: "low"`, then none, then
  `thinkingBudget: 0`, with a 4096-token cap. It falls back across models on 404/429 and reports Google's real error.
  It was verified only against a local mock server.

The owner reports the app is still **bad at understanding and completing tasks**. Treat everything below as the brief.

## Known flaws (why it's bad)

1. **Built blind.** No SDK, emulator or device was available. Nothing has been run end-to-end on the phone, and every bug was found by the owner.
   → Fix the dev loop first: build locally, install over adb, read `adb logcat`, test with the real key.
2. **Understanding is regex rules** (`Brain.java`, ~1,800 lines). Brittle by nature. Smart mode was bolted on as a second path.
3. **LLM integration is fragile.** It uses a JSON-mode prompt instead of native function calling, hardcodes model names,
   has no streaming and no proper error surfacing (partly fixed).
4. **Not a real assistant integration.** It's an `ACTION_ASSIST` activity, not a `VoiceInteractionService`, so it doesn't
   work on the lock screen, can't use screen context, and is listed inconsistently under Samsung's digital assistant setting. No wake word.
5. **Speech pipeline is guesswork.** Engine choice, silence timeouts and biasing were never measured on the device.
   Latency stacks up: speech → network → TTS, with no streaming or barge-in.
6. **Task completion gaps.** Texts open a pre-filled composer instead of sending. WhatsApp needs the number in international (E.164) format.
   It can't read messages or notifications. Alarms and timers depend on the Samsung Clock intent behaviour, which is unverified.
7. **Quality.** UI is built in code (no Compose), hand-rolled HTTP/JSON, Java. Tests only cover text routing
   (`Probe` harness). No instrumentation tests, and CI only builds.
8. **Config.** `app/debug.keystore` is committed so every build can update in place. That's OK for a private personal app, but rotate it
   if the repo is public.

## Recommended plan

1. **Dev loop (day 1):** Android Studio (SDK + JDK 17). Phone in Developer options → **Wireless debugging**
   (or USB). `./gradlew installDebug`, `adb logcat -s VoiceAgent`. Add structured logging (`Log.d("VoiceAgent", ...)`)
   for every stage: heard text + alternatives, chosen action, intent fired, errors.
2. **Get smart mode working with the real key.** Use `curl` from the laptop first, then the app. Confirm current model
   ids and thinking config (`thinkingLevel` for Gemini 3+). Switch to **native function calling** (`tools.functionDeclarations`),
   one function per action, `toolConfig` mode ANY/AUTO. Keep conversation history for follow-ups.
3. **Provider abstraction.** Gemini plus any OpenAI-compatible free endpoint (Groq, OpenRouter `:free` models), chosen in
   settings. OpenAI/Anthropic APIs are paid-only, so they're out of scope. Keep the offline rules as the no-network fallback.
4. **Rebuild the app shell in Kotlin + Jetpack Compose** (or refactor incrementally if faster). Keep and port the
   proven action code (`Brain` intent builders, `TimeParser`, `Contacts`/`Apps` fuzzy matching, `Conversions`, `Reminders`).
5. **Proper assistant:** implement `VoiceInteractionService` + `VoiceInteractionSessionService` (+ `RecognitionService` stub
   if required) so it can be the default digital assistant: Samsung side button long-press → "Digital assistant", corner swipe, lock screen.
6. **Finish tasks for real:** send SMS directly after a spoken confirmation ("Send it?" → `SmsManager`, needs `SEND_SMS`).
   Format numbers as E.164 for WhatsApp. Verify alarm, timer and reminder delivery on the A16. Optionally use a `NotificationListenerService` to read messages aloud.
7. **Speech:** measure accuracy and latency on the device. Try the on-device recognizer (`createOnDeviceSpeechRecognizer`) and
   Google's, and pick by data. Add barge-in (tap or speak to interrupt TTS) and streaming partial UI.
8. **Tests:** keep the phrase table (see `Probe` cases below) as unit tests (JUnit + Robolectric). Add a small instrumented smoke test.
   Make CI run the tests before building the APK.

## Build & release

- Gradle wrapper 8.14.3, AGP 8.7.3, compileSdk/targetSdk 35, minSdk 26, Java 17. `./gradlew assembleDebug`.
- `.github/workflows/build-apk.yml` builds on every push and publishes `VoiceAgent.apk` to the **`latest`** GitHub
  release (owner installs from Releases on the phone). Bump `versionCode` on every release.
- Package `com.voiceagent`. Fixed debug key `app/debug.keystore` (passwords `android`) so updates install over the old app.

## Code map (current)

```
app/src/main/java/com/voiceagent/
  AssistantActivity.java  overlay UI, SpeechRecognizer (engine list + fallback), TTS, smart-mode settings dialog
  Brain.java              handle() → fastPath | smart (Gemini) | local rules; perform(action JSON); all intent builders
  Gemini.java             REST client (x-goog-api-key), system prompt with action schema, history, model/thinking fallback
  TimeParser.java         spoken times/durations;  MathEval.java  spoken math;  Conversions.java  units + currency
  Contacts.java / Apps.java  fuzzy + phonetic matching, relation aliases (mom/amma/…)
  WebSkills.java          Wikipedia, DuckDuckGo instant answers, dictionary, Open-Meteo weather (all key-less)
  Reminders.java + ReminderReceiver + BootReceiver   AlarmManager reminders that survive reboot
  AssistTileService.java  Quick Settings tile;  OrbView.java  animated orb
```

## Phrases that must work (regression list)

"Hey Google, call mom." · "Give Priya a call" · "Text Alex, I'm running late." · "Send John a message saying hi" ·
"WhatsApp Priya see you at 8" · "Set an alarm for 730 a.m." · "Wake me up at 7" (→ 7 AM) · "Set a 5-minute timer" ·
"Remind me to call mom at 6 p.m." · "Don't let me forget to buy milk tomorrow" · "What's the weather tomorrow?" ·
"Play Arijit Singh songs" · "Navigate to Charminar" · "Find petrol pumps near me" · "Turn on the flashlight" ·
"Set volume to 50 percent" · "How tall is Mount Everest?" · "Convert 10 km to miles" · "100 dollars in rupees" ·
"Take a selfie" · "Read my notes" · "Translate good morning to Hindi" · follow-ups like "make it 8 instead".
