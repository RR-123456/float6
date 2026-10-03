# PragonMobile v3.0 - what changed

## New: three modes (Settings > MODE, the chip at the top of the chat, or just say it)
| Mode | Behaviour |
|---|---|
| **ASSISTANT** | Only talks and holds a conversation. Never touches the phone; if asked to, it says it is in Assistant mode. In float mode everything you say after "I'm home" is conversation - no need to say "Pragon". |
| **MASTER** (default) | Assistant + Agent. Talks, **acts, and tells you what it did**: say *open YouTube* -> YouTube opens -> Pragon says *"YouTube opened, sir."* |
| **AGENT** | Does the action and keeps quiet. Silent when it works; speaks only when something fails or you ask a question. |

Say **"agent mode"**, **"assistant mode"** or **"master mode"** at any time (typed or spoken) to switch.
Plain commands (open/close app, search, volume, home/back, timer, alarm, flashlight, screenshot, ...) are recognised on the phone and run instantly - no AI round trip, no API key, works offline. Everything else goes to the AI.

## New: the PC "Pragon model" brain, ported to Standalone
From `pragoncore/protocol.txt` and `pragoncore/model.py`:
- **Persona/protocol** - identity, calm "sir" tone, spoken-style rules, tool discipline (one request = one tool call, never claim an action worked without a result), decision order. The address word is configurable ("sir", "boss", ...).
- **Long-term memory** - Pragon silently remembers lasting facts (name, city, likes ...) with a `remember` tool and uses them later. View/clear in Settings.
- **Language handling** - replies in the language you speak (Tamil, Hindi, Telugu ... ), or lock one in Settings. The voice switches to the reply's language automatically.
- **Streaming + sentence-by-sentence speech** - Pragon starts talking with the first sentence while the rest is still being written (Gemini, Ollama and OpenAI-compatible servers).
- **Warm-up + keep-alive for Ollama/custom servers** - model is loaded and the persona prompt cached when the app opens, so the first answer is fast.
- **Better voice** - picks the best installed voice, your speed/pitch, strips markdown/links/emoji before speaking, ducks music while talking, and in float mode the mic only re-opens after Pragon has really finished talking.
- Shared conversation: the chat screen and the float bubble now share one context.

## New: voice changer
Say **"Pragon, change your voice"** (also "switch to a different voice", "next voice", "use another voice") and Pragon moves to the next installed voice for your language, then answers in it: *"Voice changed, sir. This is voice 3 of 7."* Each repeat goes to the next one. Say **"reset your voice"** / **"original voice"** to go back. If your phone has only one voice installed, it steps through four pitch presets instead so the change is always audible. Works in all three modes and from the float bubble. The choice is saved; there is also a CHANGE VOICE button in Settings. (More voices: Settings > System > Languages > Text-to-speech > install voice data.)

## Bugs fixed
1. **Gemini default model is being shut down** (`gemini-2.5-flash`, mid/late Oct 2026), and Settings re-saved it into your prefs. Default is now `gemini-3.8-flash`; saved retired names are ignored; if a model returns 404 the app automatically falls back through `gemini-3.5-flash` -> `3.1-flash-lite` -> `2.5-flash` and remembers the one that works.
2. **Thinking models were slow for voice** - Gemini 3.x now runs with `thinkingLevel: low` (Gemini 3 rejects `thinkingBudget: 0`), 2.5 Flash with thinking off; retried without if a model refuses.
3. **Float mode "no speech recognizer" on Android 11+** - manifest was missing the `<queries>` entries for `RecognitionService` / `RECOGNIZE_SPEECH`.
4. **"I am going home" woke Pragon** ("I'm home" matched any sentence with *home* up to 5 words). The wake phrase is now strict.
5. **Saying "end" or "shut" closed the current app.** Bare verbs no longer close anything; "shut down" alone is ignored.
6. **"Pragon close" / typing "close pragon" could close the foreground app** (the name was stripped first). Self-close is now checked before cleaning.
7. **"show me the weather" / "start recording" tried to open an app of that name.** Vague verbs now fall through to the AI when no such app exists.
8. **Short app names matched random apps** (`x` matched anything containing x). Fuzzy matching now needs 3+ letters.
9. **"Opened X" was said even when Android silently blocked the launch.** With the accessibility service on, Pragon waits until the app is really on screen and otherwise says it could not confirm.
10. **Real app names in confirmations** ("YouTube", not "youtube").
11. **The PC link was torn down and rebuilt on every screen resume** (messages could be lost in the gap). A healthy connection is now kept.
12. **Chat and float bubble had separate histories**, and history was wiped entirely at 30 messages. Now shared, trimmed from the oldest turn without breaking tool exchanges.
13. **"dragon"/"bragon" counted as the wake word anywhere** in a sentence ("play dragon ball"). Only at the start now.
14. **`Prefs.clear()` whitelist** silently lost any new setting when forgetting the PC; it now removes only the PC keys.
15. Camera permission was requested at start even when never used; now only when paired.
16. Duplicate system prompts in two clients replaced by one persona.

## Other additions
Voice commands: *set a timer for 5 minutes*, *set an alarm for 7:30 am*, *flashlight on/off*, *take a screenshot*, *play <song>* (YouTube), *battery*. Settings: listening language, reply language, voice picker, speed, pitch, test voice, "speak typed replies", "float: require the word Pragon".

## How this was checked
All Kotlin compiles with kotlinc 1.9.24 against android.jar (third-party libraries stubbed). 119 parser/language checks and 34 streaming checks (sentence splitting, `<think>` filtering, Ollama / OpenAI / Gemini wire formats including Gemini 3 thought signatures and fragmented tool calls) pass on a plain JVM. The APK itself and on-device behaviour (TTS voices, microphone, accessibility) could not be run in the build sandbox - build it with GitHub Actions or Android Studio as before and try the checklist below.

## Quick on-device checklist
1. Chat: type `open youtube` in MASTER mode -> opens, then says "YouTube opened, sir." In AGENT mode it opens silently. In ASSISTANT mode it refuses politely.
2. Say `agent mode` / `master mode` / `assistant mode`.
3. Float bubble: "I'm home" -> "open Instagram" / "how are you?".
4. Settings > Voice: pick a language/voice, press SAVE + TEST VOICE.
5. Tell it "my name is ..." then ask "what's my name?" later.
