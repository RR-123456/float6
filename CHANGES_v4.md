# PragonMobile v4 - what changed

## Bug fixed: play and pause were one button
"play", "pause", "resume" and "stop" all sent the same toggle key, so saying "pause" while paused started playback.
They are now separate keys: **pause** can only pause, **play / resume** can only play. Pragon also checks what the phone is
really doing first, so it answers "Nothing is playing right now" or "It's already playing" instead of toggling by mistake.
"play pause" / "toggle playback" still sends the old toggle if you want it. The AI tool has `play` and `pause` as well.

## New voice commands
| Say | What happens |
|---|---|
| **"Pragon click"** / "click" / "tap" | presses the focused item, or taps the middle of the screen |
| **"click subscribe"**, "tap on search", "press send" | finds a button/text with that name on screen and presses it |
| "double tap" | two taps in the middle of the screen |
| **"start stopwatch"**, "stop stopwatch", "lap", "reset stopwatch", "what's the stopwatch" | Pragon's own stopwatch with a live notification clock (Android has no stopwatch intent) |
| **"set alarm for 7 30 am"**, "alarm at 6", "alarm in 30 minutes", "show alarms", "cancel alarm" | alarms (clock app) |
| "set a 10 minute timer", "timer 1.5 hours", "show timers", "cancel timer" | timers (more phrasings) |
| **"calculate 25 times 4"**, "what is 15 percent of 200", "square root of 144", "two lakh plus 50 thousand" | answered instantly and spoken, offline |
| "calculate 6 plus 6 **on the calculator**" | opens the calculator app and presses the keys too (needs the accessibility service) |
| **"open wifi settings"**, bluetooth / display / sound / battery / location / apps / developer / airplane ... | opens that settings screen. "open your settings" or "Pragon settings" opens Pragon's own. |
| **"typer mode"** / "start typing" / "voice typing" | everything you say is typed into the focused text box |
| **"battery reminder on"**, "remind me to unplug at 80 percent", "remind me when battery is 30 percent", "cancel battery reminders" | battery reminders (below) |
| **"ollama pull llama3.2:3b"**, "pull model qwen2.5", "ollama list", "use model hermes3" | manage Ollama models from chat |

### Typer mode (voice typing)
Say "typer mode", tap a text box, then just talk. It works from the float bubble, in any app.
Say: **comma, full stop, question mark, exclamation mark, colon, new line, new paragraph, open/close bracket, at the rate, dot com**.
Edit: **"delete that"** (undoes the last dictation), "delete last word", "backspace", "clear all", "select all".
**"send"** / "enter" / "search" presses the keyboard's action key (or a Send button).
End with **"stop typing"** (or "typer mode off"). Inside typer mode nothing is treated as a command, so you can dictate the word
"mute" or "goodbye" safely; only "close Pragon" and "Pragon goodbye" still work. It switches itself off after 10 minutes of silence.
Needs the accessibility service and float mode (it starts float mode for you).

### Battery reminders
- **Low battery**: spoken + notification at 20 / 10 / 5 percent (editable) when not charging.
- **Unplug**: "remind me to unplug at 80 percent" (or "when the battery is full").
- **Level**: "remind me when battery is 30 percent" (works up or down, depending on whether you are charging).
A small quiet notification "Pragon is watching the battery" shows only while a reminder is on. They come back after a restart.
Also in **Settings > BATTERY REMINDERS**.

## Ollama: pull models from the phone
**Settings > AI engine > Ollama** now has a model manager: LIST INSTALLED shows every model with its size and **USE / DELETE** buttons,
**PULL** has quick-pick chips and a real progress bar (percent, GB done of total). In chat: "ollama pull llama3.2:3b" downloads in the
background with a progress notification and tells you when it is done. The download runs on the Ollama server (your PC), so keep it awake.
The server needs `OLLAMA_HOST=0.0.0.0` so the phone can reach it.

## Checks
All Kotlin compiles against android.jar (only unrelated library stubs missing). 140+ parser/calculator/typer checks pass on a plain JVM
(see `tests/`). On-device behaviour (clicking, typing into other apps, notifications) could not be run in the build sandbox:
try the checklist below after installing.

## On-device checklist
1. Play a YouTube video. Say "pause" (pauses), "pause" again (says nothing is playing), "play" (plays).
2. Say "Pragon click" on a screen with a button; "click subscribe" on YouTube.
3. "start stopwatch", wait, "lap", "stop stopwatch" ("stop" alone still pauses media).
4. "calculate 25 times 4" -> "25 times 4 is 100". "calculate 6 plus 6 on the calculator".
5. Open a chat app, tap the message box, say "typer mode", "hello comma how are you question mark", "delete that", "stop typing".
6. "battery reminder on"; "remind me when battery is 90 percent".
7. Settings > Ollama: LIST, PULL a small model (llama3.2:1b), USE it.
