# PragonMobile v5

## Closing apps (rewritten)
Old way: opened Settings > App info > Force stop and pressed buttons by English text, then went Back into the dead app.
New way: **"close X"** goes Home if X is on screen, then tells Android to end X's process: no Settings screens, any language, ~half a second.
If sound is still playing afterwards (music/navigation apps keep a foreground service), Pragon says so. **"force stop X"** is the strong version
(App info > Force stop) and now works in many languages (incl. Tamil/Hindi/etc.), confirms with the dialog's own button id, and ends on the home screen.
**"close all apps"** / "clean memory" ends every background app except Pragon, your launcher, keyboard and phone app.

## Privacy: nothing goes to a third party
- **Privacy Shield (ON by default)**: every connection Pragon makes is checked inside the app. Only your own network is allowed (192.168.x, 10.x, 172.16-31.x,
  127.x, Tailscale 100.64-127.x, .local/.lan names, single-word names) plus hosts you add under "Trusted hosts". Anything else is refused and logged.
  Gemini (Google) is therefore blocked while the Shield is on: use Ollama or an OpenAI-compatible server on your own machine.
  Speech recognition is asked to stay on the phone (EXTRA_PREFER_OFFLINE); install the offline speech pack in Google settings for a guarantee.
  Settings > Security shows who Pragon contacted ("who did you connect to" by voice). No analytics, ads or crash reporters are in the app.
- Everything below that reads private data or loosens protection is OFF by default, and **cannot be switched on/off by the AI model or the PC link**,
  only by you (Settings, or your own voice for turning protections ON).

## Firewall + scanner (offline)
- **Firewall**: a local VPN that goes nowhere. "Block mode": apps you tick lose internet. "Allow mode": only apps you tick keep it (Pragon always keeps its LAN link).
  Android shows its key icon; it cannot run alongside another VPN. "firewall on" by voice; turning it OFF is Settings-only.
- **Scanner** ("scan my phone" / Settings > Security > SCAN): checks every installed app offline for sideloading, debug builds, accessibility services switched on,
  SMS/call-log/mic/contacts + internet combinations, draw-over-apps, device admin... with BLOCK INTERNET / APP INFO / UNINSTALL buttons.
  It is a permissions/behaviour check, not a virus-signature engine (a signature database would mean sending your app list to a company).

## Volume, brightness, flashlight
"increase volume", "decrease volume", "volume up/down", "set volume to 50", "max volume", "raise the volume by 20", "make it louder/quieter",
and the same for **brightness** ("dim the screen", "auto brightness off") and **flashlight** strength ("increase flashlight brightness", Android 13+ flash with several levels).
Brightness needs "Modify system settings": Settings > Security > ALLOW, or once: `adb shell appops set com.pragon.mobile WRITE_SETTINGS allow`.

## Only allowed apps can be opened
Settings > APPS > ALLOWED: tick apps, switch on "Only open the apps I allow". Applies to voice, chat, sidebar and macros (alarms/timers that open no screen are exempt).
Turning the limit off is Settings-only.

## Smart sidebar
Thin handle on the screen edge. Tap or pull inwards: panel with SMART chips (media controls while music plays, voice-type when a text box is focused,
Read screen if allowed, Stop macro...), QUICK controls (torch +/-, volume +/-, brightness +/-, screenshot, home/back/recents), your favourite apps (most used first)
and your macros. "open sidebar", "sidebar off". Settings > SMART SIDEBAR (left/right edge).

## Macros
"record macro morning" ... "stop recording". "run macro morning" / "run macro morning 3 times" / "stop macro". Records taps (by button NAME), scrolls, apps you open and
Pragon's own commands; never typed text, nothing on password screens. Replay uses the same command code, so allowed-apps etc. still apply.

## Screen processing and answers (only if you allow it)
Settings > Security > "Let Pragon read my screen". Then: **"read the screen"** (phone TTS reads the visible text, no AI),
**"what's on my screen"**, **"answer the question on the screen"**, **"on screen, who wrote this"**. The screen TEXT (never a picture or password box) is sent
only to your chosen AI engine (your own server), as a one-off question that is not kept in chat history.

## TalkBack / text to speech / Live Caption (only if you allow it)
Settings > Security > "Let Pragon switch accessibility features". Then "turn on talkback", "turn off live caption", "turn on text to speech" (Select to Speak).
Android only lets Pragon flip these directly after one USB permission:
`adb shell pm grant com.pragon.mobile android.permission.WRITE_SECURE_SETTINGS`
Without it Pragon opens the right Android screen and tells you what to tap.

## Checks
Whole project compiles against android.jar (only unrelated library stubs missing). 300+ JVM checks pass (parser, calculator, typer, privacy host rules).
Not run on a real phone here: please try the on-device list in CHANGES_v4.md plus: close an app, firewall on with a test app, record + run a macro, open the sidebar.
