# Voice Agent

A free, Siri-style voice assistant for Android, made for the **Samsung Galaxy A16 5G** (works on any Android 8+ phone).

- **100% free.** It needs no API keys, subscriptions or accounts. Speech recognition and the voice use the engines already on your phone.
- **Private.** Your notes and reminders stay on the phone. Only "who is…", definitions and weather go online, to free public services (Wikipedia, dictionaryapi.dev, Open-Meteo).
- **Siri-like.** A glowing orb pops up over whatever you're doing, listens right away, answers out loud, and asks follow-up questions ("What should it say?").

## What you can say

| Area | Examples |
|---|---|
| Calls & messages | "Call Mom" · "Text Alex I'm running late" · "WhatsApp Priya see you at 8" · "Let Dad know I reached home" |
| Alarms & timers | "Wake me up at 6:30" · "Set an alarm for 7 tomorrow" · "Set a timer for 10 minutes" · "Show my alarms" |
| Reminders | "Remind me to take my pills in 2 hours" · "Remind me to pay rent tomorrow" · "Remind me at 5 pm to buy milk" |
| Calendar | "What's on my calendar tomorrow?" · "Add dentist appointment Friday at 3 pm to my calendar" |
| Apps & music | "Open Instagram" · "Play Arijit Singh" · "Play lo-fi on Spotify" · "Pause" · "Next song" |
| Phone controls | "Turn on the flashlight" · "Volume up" · "Set volume to 50 percent" · "Brightness 30 percent" · "Do not disturb on" · "Silent mode" · "Wi-Fi" · "Bluetooth" · "Battery" |
| Maps | "Navigate to the airport" · "Take me home" · "Find petrol pumps near me" |
| Answers | "Who is Sundar Pichai?" · "What is a black hole?" · "Define serendipity" · "What's the weather tomorrow?" · "Weather in Delhi" · "What time is it in London?" |
| Math | "What's 18 percent of 2450?" · "Square root of 144" · "12 times 7" |
| Notes | "Take a note buy eggs" · "Remember that I parked on level 3" · "Read my notes" |
| Other | "Translate good morning to Hindi" · "Take a selfie" · "Tell me a joke" · "Flip a coin" · "Roll a dice" |

If it doesn't understand something, it opens a Google search for it.

---

## Setup: everything from your phone

### 1. Put the code on GitHub (you push it yourself)

You only need to do this once. It uses **Termux**, a free terminal app.

1. Install **Termux** from **F-Droid** (https://f-droid.org/packages/com.termux/). The Play Store version is outdated.
2. Make a GitHub **Personal access token**: github.com → Settings → Developer settings → Personal access tokens → *Tokens (classic)* → Generate new token. Tick **`repo`** and **`workflow`**, then copy the token. You'll paste it as your password.
3. Download `voice-agent-android.zip` to your phone's **Download** folder.
4. In Termux, run these commands one at a time (replace the name and email with yours):

```sh
termux-setup-storage            # tap "Allow"
pkg install -y git unzip
cd ~ && unzip -o /sdcard/Download/voice-agent-android.zip
cd voice-agent-android
git init -b main
git config user.name  "Your Name"
git config user.email "you@example.com"
git add .
git commit -m "Voice Agent app"
git remote add origin https://github.com/LHarsha/voice-agent-android.git
git push -u origin main         # username: LHarsha, password: paste the token
```

### 2. Get the app (GitHub builds it for free)

Pushing starts the **Build APK** workflow automatically. It takes about 3–5 minutes.

1. Open `github.com/LHarsha/voice-agent-android` in Chrome or Samsung Internet. Check the **Actions** tab to see when the build is green.
2. Go to **Releases** → **Voice Agent (latest build)** → tap **VoiceAgent.apk**.
3. Open the downloaded file. If Android asks, allow **Install unknown apps** for your browser or My Files, then tap **Install**.
   - If Play Protect warns about an unknown app, tap **More details → Install anyway**. It warns because the app isn't from the Play Store.

Every later push builds a fresh APK, and you can install it over the old one.

### 3. Make it your assistant (the "Siri" part)

Open **Voice Agent** once, allow the microphone, and tap the **"Make me your phone's assistant"** chip. You can also go there manually:

**Settings → Apps → Choose default apps → Digital assistant app → Device assistance app → Voice Agent**

Then call it like Siri:

- **Gesture navigation:** swipe up diagonally from either bottom corner.
- **Button navigation:** touch and hold the Home button.
- **Side button (recommended):** Settings → Advanced features → Side button → **Double press → Open app → Voice Agent**. Some One UI versions also let **Press and hold** open the digital assistant.
- **Quick Settings tile:** swipe down twice → ✏️ edit → drag **Voice Agent** into your tiles.

### 4. Optional: talk without internet

Settings → General management → Keyboard list and default → Google voice typing → **Offline speech recognition** → download your language. If you don't see this option, install the **Google** app or **Speech Recognition & Synthesis** from the Play Store.

You can change the voice under Settings → General management → **Text-to-speech output**.

---

## Permissions it asks for (only when first needed)

| Permission | Why |
|---|---|
| Microphone | To hear you |
| Contacts | "Call Mom", "Text Alex" |
| Phone | Calling directly. Without it, the dialer opens and you tap call |
| Calendar | Reading your schedule |
| Approximate location | Local weather |
| Notifications | Showing reminders |
| Modify system settings / Do Not Disturb access | Only if you ask to change brightness or DND |

## Honest limitations

- **No always-on "Hey…" wake word.** Free always-listening wake words drain the battery and need third-party models or keys. Use the side button, corner swipe or tile instead. They're instant.
- **Texts open ready to send, and you tap send.** This is safer than sending blindly, and it needs no SMS permission.
- **Wi-Fi and Bluetooth can't be switched by apps** on modern Android. It opens the quick control panel instead.
- The answers come from Wikipedia, so they're short facts, not chatty AI conversation. Anything else goes to a Google search.
- The commands are in English. Indian English (en-IN) works if that's your phone's language.

## Project layout

```
app/src/main/java/com/voiceagent/
  AssistantActivity.java   overlay UI, speech recognition, text-to-speech
  Brain.java               understands commands and carries them out
  OrbView.java             animated glowing orb
  TimeParser.java          "tomorrow at 7:30 pm", "in 20 minutes"
  MathEval.java            spoken math
  WebSkills.java           Wikipedia, dictionary, Open-Meteo weather
  Contacts.java / Apps.java  fuzzy matching for names and apps
  Reminders.java + receivers  on-device reminders that survive reboot
  AssistTileService.java   Quick Settings tile
.github/workflows/build-apk.yml   free cloud build → Releases
```

To add a command, add a method in `Brain.java` and call it from `handle()`.

Building on a computer instead: install Android Studio, open this folder, and press Run. Or run `./gradlew assembleDebug`.
