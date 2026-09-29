# File guide

A brief map of every source file in `app/src/main/java/com/naomi/assistant/`.

## Core flow

| File | What it does |
|------|--------------|
| `MainActivity.kt` | The whole UI + wiring (Jetpack Compose). Renders the animated orb, the screens (home / memory / settings / brain), the splash animation, and the transcript. Handles mic taps, permissions, wake-word and assistant launch intents, follow-up listening & barge-in, and the "power / back / home → stop everything" reset. |
| `AssistantBrain.kt` | The orchestrator. Smart mode: every turn goes to the cloud brain (chat or action), falling back offline if it's unreachable. Otherwise: keyword router → on-device Gemma. Manages multi-turn follow-ups, conversation mode, and conversation history — and long-term memory: "remember that…" / "forget…", learning from what the owner says, a note when each conversation ends, and the related memories sent with each turn (never a guest's). |
| `CommandRouter.kt` | The offline/keyword brain **and** every device action: timers, alarms, time/date, calls, WhatsApp voice/video calls, SMS & WhatsApp messages, music, maps/navigation, rides, food, notes, email, torch, Wi-Fi, Bluetooth, volume, calendar, voice recording, and opening apps/settings. Shared by both the keyword path and the LLM path. |

## Ears & mouth

| File | What it does |
|------|--------------|
| `VoiceInput.kt` | Speech-to-text ("ears") over Android `SpeechRecognizer`. Prefers en-IN, fires `onSpeechStart` for barge-in, and can `cancel()` mid-turn. Where the speech service accepts it (Android 13+ `EXTRA_AUDIO_SOURCE`), captures the mic itself and keeps each utterance's audio so the speaker can be identified; checks once whether that works, else leaves the mic to the service. |
| `Speaker.kt` | Text-to-speech ("mouth") over Android `TextToSpeech`. Picks a female en-IN voice and calls back when done (so Naomi can listen again). |
| `WakeService.kt` | Foreground mic service. Offline "Naomi" wake-word spotting (Vosk) plus a restricted-grammar listener for follow-up answers. Adds echo/noise cancellation, survives screen-off, and launches the UI from lock/background via a full-screen intent. With a voice trained, only the owner's "Naomi" wakes her; anyone else is refused out loud. |
| `SpeechAudio.kt` | Small energy-based audio helpers: where the last speech in a buffer ends, trimming the silence out of a clip, and a plain mic recording for training. |

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
| `DistanceClient.kt` | "How far am I from X": finds X near the phone (platform geocoder), then the driving distance and time from OSRM's public router, or the straight-line distance. |
| `DeviceLocation.kt` | The phone's location and neighbourhood name (fused location + geocoder), cached for a few minutes. |
| `VoiceRecorder.kt` | In-app voice memo recorder — saves M4A files to public storage via MediaStore. |
| `WhatsAppSender.kt` | Accessibility service. Auto-taps WhatsApp's Send button after Naomi opens a chat, and provides general on-screen control (tap / scroll / type by voice) for other apps. |
| `BootReceiver.kt` | Re-arms the wake listener after a reboot, if the user had it enabled. |

## Voice verification (per-user wake)

| File | What it does |
|------|--------------|
| `SpeakerVerifier.kt` | Speaker embedder backed by the bundled WeSpeaker CAM++ ONNX model (VoxCeleb, CC BY 4.0) — turns a voice clip into a voiceprint. |
| `VoiceEnrollment.kt` | The owner's voiceprints: one from the trained "Naomi" clips (checks wake phrases), one from ~15 s of free speech (checks any sentence). Scores audio by cosine similarity. |
| `FBankExtractor.kt` | Kaldi-compatible 80-dim log-mel filterbank + CMN, matching WeSpeaker's front-end (tested against kaldi-native-fbank). Pure Kotlin, no deps. |

Once a voice is trained, `WakeService` cuts every wake phrase out of the mic stream (its length from
Vosk, its end from the audio), verifies it, and refuses anyone else out loud. A tap on the orb while
the phone is locked asks for the unlock instead. Each sentence after that is voice-checked too:
someone else talking — to the owner, not to Naomi — is ignored, and a guest who says "Naomi" is
answered as a guest (never called by the owner's name, and no personal actions).

## Theme

| File | What it does |
|------|--------------|
| `ui/theme/Color.kt` | Colour palette. |
| `ui/theme/Theme.kt` | Compose Material theme. |
| `ui/theme/Type.kt` | Typography (fonts). |

---

> Not in this repo (intentionally): `local.properties` (holds your API keys) and the optional
> on-device Gemma model. See `README.md` for setup.
