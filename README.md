# Naomi — a personal voice assistant for Android

A hands-free, offline-first ("Jarvis"-style) voice assistant. Say **"Naomi"** and ask it to set a
timer, call someone, message on WhatsApp, play music, open apps, check the weather, and more — most
of it works with no internet. In smart mode she talks naturally, with a personality, through the
cloud AI you pick — and she can fall back to a fully on-device LLM.

- **Offline-first:** timers, alarms, time/date, calls, WhatsApp voice/video calls, SMS & WhatsApp
  messages, music, maps/navigation, rides, food, notes, torch, Wi-Fi/Bluetooth, volume, calendar,
  and "open \<app\>" — all handled on-device by the keyword router.
- **Smart mode:** a cloud brain of your choice — Groq, Google Gemini, OpenAI, Anthropic Claude, or
  any OpenAI-compatible server (e.g. Ollama on your own PC) — hears every turn, answers in Naomi's
  (editable) personality and runs phone actions. Set it up in the app; no rebuild needed.
- **Hands-free:** an offline **"Naomi" wake word** (Vosk) launches it from the lock screen, and a
  voice lock makes her answer only to you.
- **Conversation mode:** after she answers, the mic stays open — just keep talking.
- **Long-term memory:** she learns what you tell her about yourself (people, plans, likes, routines),
  keeps a short note of each conversation, and brings back what's relevant when you talk again.
  "Remember that…" / "forget…" work by voice, and everything she knows is on the Memory screen.

```
"Naomi" / tap 🎤 → SpeechRecognizer → AssistantBrain ┬─ CloudBrain     (smart mode: chat + actions, your pick of AI)
                                                      ├─ CommandRouter  (offline actions)
                                                      └─ LocalBrain     (offline Gemma)  → TextToSpeech 🔊
```

---

## 📲 Just want to try it? (no build needed)

Download the latest **`app-debug.apk`** from the [**Releases**](../../releases) page and install it on
an Android phone (Android 8.0+). You'll need to allow "install from unknown sources".

> The published APK ships with **no API key**. Offline commands, the "Naomi" wake word, and the
> on-device brain all work; to turn on smart mode, add your own key in the app (below).

---

## 🛠 Build from source

**Requirements:** Android Studio (latest), a phone with Android 8.0+ (API 26) and USB debugging on.

```bash
git clone https://github.com/mukeshchandu/Personal-AI-Assistant.git
cd Personal-AI-Assistant
```

Open the folder in Android Studio and press **Run ▶** (or `./gradlew :app:assembleDebug`).
It builds and runs as-is — offline features work immediately.

### Enable smart mode (optional)
In the app: **Settings → Brain & personality**. Pick who thinks for Naomi, paste its API key, tap
**Test**, then turn on **Smart mode**:

| Brain | Key | Default model (editable) |
|-------|-----|--------------------------|
| Groq | free at https://console.groq.com/keys | `llama-3.3-70b-versatile` |
| Google Gemini | free at https://aistudio.google.com/apikey | `gemini-flash-latest` |
| OpenAI | https://platform.openai.com/api-keys | `gpt-4.1-mini` |
| Anthropic Claude | https://console.anthropic.com | `claude-haiku-4-5-20251001` |
| Custom | whatever your server needs (often none) | any — e.g. Ollama at `http://<pc>.<tailnet>.ts.net:11434/v1` |

On the same screen you can rewrite her personality, tell her your name, and turn conversation
mode on or off. Keys can still come from `local.properties` (`GROQ_API_KEY`, `GEMINI_API_KEY`) as
a build-time fallback.

**Your own model (Ollama on your PC):** Ollama only listens on localhost by default. Make it listen
on the network (`sudo systemctl edit ollama` → `Environment="OLLAMA_HOST=0.0.0.0"`), allow the port
on Tailscale only (e.g. `sudo ufw allow in on tailscale0 to any port 11434 proto tcp`), then use
**Custom** with `http://<pc>.<tailnet>.ts.net:11434/v1` and the model's name. For self-hosted models
the app has the server enforce the reply format, so even 1B models keep to it — but they pick the
wrong action more often than big ones; 3B+ is noticeably better. To judge a model with Naomi's real
prompt: `NAOMI_LLM_URL=http://127.0.0.1:11434/v1 NAOMI_LLM_MODEL=<name> ./gradlew :app:testDebugUnitTest --tests '*LiveBrainTest*' -i`.

---

## First run

Grant the mic (and, for calls/messages, contacts + phone) permissions. Then try:
- "set a timer for 2 minutes"  *(offline)*
- "call \<contact\>" / "WhatsApp video call \<contact\>"  *(offline)*
- "what's the weather in Bangalore"
- "play \<song\>"  •  "open settings"
- "remember that I parked on level 3" → later, "where did I park?"  •  "forget that"
- Turn on the **"Naomi" wake word** and say "Naomi" from the lock screen.
- **Voice lock:** Settings → *Train my voice*: say "Naomi" 8 times, then talk freely for 15 seconds.
  From then on only your voice wakes her — anyone else hears "Sorry, I don't recognize your voice.
  You're not authorized to use me." In a conversation she tells voices apart: people talking to
  *you* are ignored, and a guest who says "Naomi" is answered as a guest, never called by your name.
  Tune *Voice match strictness* in Settings if she rejects you or lets others through.

The wake word and voice models are bundled in `app/src/main/assets/` (Vosk + ONNX), so it works out
of the box. The larger on-device chat model (Gemma) is optional and loaded from device storage
separately.

---

## Code map

See **[`FILES.md`](FILES.md)** for a one-line description of every source file. The essentials:

| File | Role |
|------|------|
| `MainActivity.kt`   | Compose UI + wiring: orb, screens, wake intents, follow-ups, splash |
| `VoiceInput.kt`     | Speech-to-text (the "ears") |
| `Speaker.kt`        | Text-to-speech (the "mouth") |
| `WakeService.kt`    | Offline "Naomi" wake word + follow-up listening |
| `CommandRouter.kt`  | **Offline brain** + all device actions — add more commands here |
| `CloudBrain.kt`     | **Cloud brain** — Naomi's personality, conversation + actions, any provider |
| `MemoryBank.kt`     | **Long-term memory** — learned facts and conversation notes, recalled on-device |
| `LocalBrain.kt`     | **Offline LLM** fallback (Gemma) |
| `AssistantBrain.kt` | Orchestrates router ↔ cloud ↔ offline, and multi-turn follow-ups |

---

## Notes & privacy

- Keys you enter in the app are encrypted with the Android Keystore and excluded from backups. Keys
  in `local.properties` (git-ignored) are compiled into *your* build — don't share that APK.
- Smart mode sends what you say, the recent conversation, your saved facts and the memories related
  to it to the brain you picked. With it off, nothing leaves the phone.
- Memories live on the phone (`naomi_memories.json`); picking which ones matter for a sentence is
  done on-device. Only what you say yourself is learned from — never a guest's words — and guests
  get no memories or personal facts at all. Turn off *Learn as we talk* on the Memory screen to stop
  learning; edit or delete any memory there.
- The "Naomi" wake word runs fully on-device (Vosk); audio for wake detection isn't sent anywhere.
  Voice verification is on-device too, with the [WeSpeaker](https://github.com/wenet-e2e/wespeaker)
  CAM++ model trained on VoxCeleb (CC BY 4.0).
- Sentences are voice-checked by capturing the mic ourselves and handing the audio to Android's
  speech recognizer (Android 13+). If the phone's speech service won't take audio that way, only the
  wake phrase is checked — Settings shows which.
- This is a personal/hobby project — the WhatsApp calling and accessibility features depend on those
  apps' current layouts and may need tweaks over time.

## Roadmap
1. **Smaller APK** — R8 shrinking (the icon library alone is most of the dex), once tested on device.
2. **Bigger on-device model** — Phi-3.5 / newer Gemma once it fits the task-model size limit.
3. **True WhatsApp auto-send** — via the accessibility service, beyond pre-filled chats.
