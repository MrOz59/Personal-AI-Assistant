# File guide

A brief map of every source file in `app/src/main/java/com/naomi/assistant/`.

## Core flow

| File | What it does |
|------|--------------|
| `MainActivity.kt` | The whole UI + wiring (Jetpack Compose). Renders the animated orb, the screens (home / memory / settings / brain), the splash animation, and the transcript. Handles mic taps, permissions, wake-word and assistant launch intents, follow-up listening & barge-in, and the "power / back / home → stop everything" reset. |
| `AssistantBrain.kt` | The orchestrator. Smart mode: every turn goes to the cloud brain (chat or action), falling back offline if it's unreachable. Otherwise: keyword router → on-device Gemma. Manages multi-turn follow-ups, conversation mode, and conversation history — and long-term memory: "remember that…" / "forget…", learning from what the owner says, a note when each conversation ends, and the related memories sent with each turn (never a guest's). |
| `CommandRouter.kt` | The offline/keyword brain **and** every device action: timers, alarms, time/date, calls, WhatsApp voice/video calls, SMS & WhatsApp messages, music, maps/navigation, rides, food, notes, email, torch, Wi-Fi, Bluetooth, volume, calendar (reading it, and adding events at the time said), reminders, voice recording, and opening apps/settings. Shared by both the keyword path and the LLM path. |

## Ears & mouth

| File | What it does |
|------|--------------|
| `VoiceInput.kt` | Speech-to-text ("ears") over Android `SpeechRecognizer`, in the English of the country the phone is in (en-AU in Australia). Asks for the recognizer's runner-up guesses and hints it with the names she knows (contacts, memories, the place just discussed); a sentence split into segments comes back whole. Fires `onSpeechStart` for barge-in and can `cancel()` mid-turn. The service gets the mic to itself — recording alongside it gets it silenced — so sentences come without audio. Debug builds can feed it test audio through `EXTRA_AUDIO_SOURCE`. |
| `Speaker.kt` | Text-to-speech ("mouth") over Android `TextToSpeech`. Picks a female en-IN voice and calls back when done (so Naomi can listen again). |
| `WakeService.kt` | Foreground mic service. Offline "Naomi" wake-word spotting (Vosk) plus a restricted-grammar listener for follow-up answers. Adds echo/noise cancellation, survives screen-off, and launches the UI from lock/background via a full-screen intent. With a voice trained, only the owner's "Naomi" wakes her; anyone else is refused out loud. In steady noise it closes the utterance itself once "naomi" shows up, accepts it amid other sounds, and checks the voice on two cuts of the audio. |
| `SpeechAudio.kt` | Small energy-based audio helpers: where the last speech in a buffer ends, trimming the silence out of a clip, and how far the voice stands out from the background (dB). |
| `WakeWord.kt` | Reading "Naomi" out of Vosk's results — also amid street noise, which comes back as `[unk]` around it — and finding the word in the recent audio from Vosk's timing. |
| `VoiceLog.kt` | A short persistent trail of what the voice pipeline did (each "Naomi" heard or passed over, voice-check scores, sentences ignored, recognizer errors), shown in Settings for diagnosing failures without a cable. |

## Brains

| File | What it does |
|------|--------------|
| `CloudBrain.kt` | Smart-mode brain. Builds Naomi's system prompt (editable personality + fixed spoken style + date/time + saved facts + recalled memories + phone actions), gets one JSON reply per turn — what to say and an optional action — fuzzy-resolves mis-heard contact names, pulls facts worth keeping out of what the owner says (checked against what was actually said), and sums up finished conversations. |
| `LlmClient.kt` | The wire protocols behind every provider: OpenAI-compatible chat completions (Groq, OpenAI, Ollama, …), Anthropic Messages, and Gemini generateContent. |
| `BrainSettings.kt` | Which provider/model is the brain, its API key (Keystore-encrypted, kept out of backups), the personality, and conversation mode. |
| `LocalBrain.kt` | Fully-offline on-device LLM (Gemma 3 1B int4 via MediaPipe). The conversational fallback when smart mode is off or there's no network. |

## Memory & extras

| File | What it does |
|------|--------------|
| `MemoryStore.kt` | Persistent user facts (`naomi_facts.json`), e.g. `mom → Amma`, `home → HSR Layout`. Normalises keys and resolves spoken names to real contacts. |
| `MemoryBank.kt` | Long-term memory (`naomi_memories.json`): facts learned about the owner and notes on past conversations. A changed fact replaces the old one; relative dates are pinned ("on Friday" → the date); recall is on-device, by shared rare words (IDF) with word endings folded. |
| `WeatherClient.kt` | Free weather via open-meteo.com — for a named city or the phone's location, now or up to 6 days ahead ("tomorrow", "on Friday") — as one spoken sentence. |
| `DistanceClient.kt` | "How far am I from X": finds X near the phone (platform geocoder), then the driving distance and time from OSRM's public router, or the straight-line distance. "Where's the nearest bus stop / Woolworths": searches around the phone in OpenStreetMap (Nominatim), widening until one turns up, and names a chain store by the shopping centre it's in. Routes on foot or by car (OSRM), as asked or by distance. |
| `TransitClient.kt` | "How do I get there by bus?": public transport trips from Transitous (free, over the agencies' own timetables — Sydney's included), the one arriving first said step by step: where to walk, which bus towards where and when, where to get off, when you'd arrive. |
| `DeviceLocation.kt` | The phone's location and neighbourhood name (fused location + geocoder), cached for a few minutes. |
| `VoiceRecorder.kt` | In-app voice memo recorder — saves M4A files to public storage via MediaStore. |
| `WhatsAppSender.kt` | Accessibility service. Auto-taps WhatsApp's Send button after Naomi opens a chat, and provides general on-screen control (tap / scroll / type by voice) for other apps. |
| `ReminderParser.kt` | Reading a reminder out of what was said, in English or Portuguese: what it's about, and when ("tomorrow at 9", "in 20 minutes", "na sexta às 3 da tarde", "every weekday at 7:30", "todo dia 10"). An hour said without AM/PM is the next one that makes sense. Also tells a list or cancel request from a new reminder. |
| `ReminderStore.kt` | Reminders that are set (`naomi_reminders.json`), soonest first, with repeats (daily, weekdays, weekly, monthly) moved on to their next time once they go off; finds the one meant by a few of its words. |
| `ReminderCommands.kt` | Setting, listing and cancelling reminders by voice — asking what or when when either was left out. |
| `ReminderReceiver.kt` | Schedules each reminder as an exact alarm, and when one goes off shows a notification (snooze 10 min / done) and says it out loud unless the phone is silenced, in a call or on Do Not Disturb. Re-arms them all after an update. |
| `ReminderText.kt` | Everything she says about reminders and new calendar events, in the language they were asked in ("tomorrow at 9:00 AM", "amanhã às 9:00"). |
| `BootReceiver.kt` | Re-arms reminders after a reboot, and the wake listener if the user had it enabled. |

## Voice verification (per-user wake)

| File | What it does |
|------|--------------|
| `SpeakerVerifier.kt` | Speaker embedder backed by the bundled WeSpeaker CAM++ ONNX model (VoxCeleb, CC BY 4.0) — turns a voice clip into a voiceprint. |
| `VoiceDebug.kt` | Debug builds only: keeps the audio behind recent voice decisions (wake attempts, training clips, recognizer tests) with where each cut landed and the scores, for checking on a computer over adb. |
| `VoiceEnrollment.kt` | The owner's voiceprint from the trained "Naomi" clips, which checks wake phrases by cosine similarity. Clips of background rather than voice are left out, also from a print made before that check. (A free-speech print for checking whole sentences is supported, for a speech service that shares the audio.) |
| `FBankExtractor.kt` | Kaldi-compatible 80-dim log-mel filterbank + CMN, matching WeSpeaker's front-end (tested against kaldi-native-fbank). Pure Kotlin, no deps. |

Once a voice is trained, `WakeService` cuts every wake phrase out of the mic stream (where Vosk's
timing puts it, and where the audio says the speech ended), verifies it, and refuses anyone else out
loud. A tap on the orb while the phone is locked asks for the unlock instead. Clips that are
background rather than voice never make it into a voiceprint, and are dropped from an old one when
it loads. The sentence after a verified "Naomi" is taken as the owner's; later sentences can't be
voice-checked, as the speech service needs the mic to itself (see `VoiceInput.kt`).

## Theme

| File | What it does |
|------|--------------|
| `ui/theme/Color.kt` | Colour palette. |
| `ui/theme/Theme.kt` | Compose Material theme. |
| `ui/theme/Type.kt` | Typography (fonts). |

---

> Not in this repo (intentionally): `local.properties` (holds your API keys) and the optional
> on-device Gemma model. See `README.md` for setup.
