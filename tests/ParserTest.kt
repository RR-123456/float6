import com.pragon.mobile.*
var fails = 0
fun a(inp: String, action: String?, value: String? = null) {
    val c = VoiceParser.cleanCommand(inp)
    val r = VoiceParser.actionFor(c)
    val ok = if (action == null) r == null else (r != null && r.action == action && (value == null || r.value == value))
    if (!ok) { fails++; println("FAIL '$inp' [$c] -> ${r?.action}/${r?.value} (want $action/$value)") }
}
fun main() {
    // media: play and pause are different
    a("pause", "key", "pause"); a("pragon pause", "key", "pause"); a("pause the video", "key", "pause"); a("stop the music", "key", "pause")
    a("play", "key", "play"); a("resume", "key", "play"); a("play the song", "key", "play"); a("pragon play", "key", "play")
    a("play pause", "key", "play_pause"); a("press play", "key", "play"); a("click pause", "key", "pause")
    a("play despacito", "youtube_search"); a("play shape of you on youtube", "youtube_search")
    a("next", "key", "next"); a("previous", "key", "previous")
    // click
    a("pragon click", "click", ""); a("click", "click", ""); a("tap", "click", ""); a("click here", "click", "")
    a("click subscribe", "click", "subscribe"); a("tap on search", "click", "search"); a("press the send button", "click", "send")
    a("double click", "double_click"); a("double tap", "double_click"); a("press home", "key", "home"); a("click back", "key", "back")
    // typer
    a("typer mode", "typer", "on"); a("start typing", "typer", "on"); a("voice typing", "typer", "on"); a("dictation mode", "typer", "on")
    a("typing mode on", "typer", "on"); a("stop typing", "typer", "off"); a("typer mode off", "typer", "off"); a("done typing", "typer", "off")
    a("type hello world", "type_text"); a("type", null); a("stop", "key", "pause")
    // calc
    a("calculate 25 times 4", "calc"); a("what is 15 percent of 200", "calc"); a("25*4", "calc"); a("2.5 + 3.5", "calc")
    a("calculate 6 plus 6 on the calculator", "calc_screen"); a("open calculator", "open_app", "calculator")
    a("what is the capital of france", null); a("how are you", null)
    // stopwatch
    a("start stopwatch", "stopwatch", "start"); a("start the stop watch", "stopwatch", "start"); a("stop the stopwatch", "stopwatch", "stop")
    a("pause stopwatch", "stopwatch", "stop"); a("reset stopwatch", "stopwatch", "reset"); a("lap", "stopwatch", "lap")
    a("stopwatch", "stopwatch", "open"); a("how long has the stopwatch been running", "stopwatch", "status"); a("resume stopwatch", "stopwatch", "start")
    // timer / alarm
    a("set a timer for 5 minutes", "timer", "300"); a("timer for 1 hour 30 minutes", "timer", "5400"); a("set a 10 minute timer", "timer", "600")
    a("5 minute timer", "timer", "300"); a("timer 1.5 hours", "timer", "5400"); a("set timer for 90 seconds", "timer", "90")
    a("cancel timer", "timer_cancel"); a("show timers", "timer_show")
    a("set alarm for 7 30 am", "alarm", "07:30"); a("set an alarm at 6 pm", "alarm", "18:00"); a("alarm for 7.30 am", "alarm", "07:30")
    a("set alarm 5 am", "alarm", "05:00"); a("wake me up at 6", "alarm", "06:00"); a("alarm in 30 minutes", "alarm", "in:1800")
    a("set alarm for 7 am tomorrow", "alarm", "07:00"); a("cancel alarm", "alarm_cancel"); a("delete my alarm", "alarm_cancel"); a("show alarms", "alarm_show")
    // settings
    a("open wifi settings", "settings"); a("bluetooth settings", "settings", "bluetooth"); a("open display settings", "settings", "display")
    a("turn on wifi", "settings", "wifi"); a("bluetooth off", "settings", "bluetooth"); a("open settings", "open_app", "settings")
    a("phone settings", "settings", "main"); a("open your settings", "settings", "pragon"); a("app settings", "settings", "pragon")
    // battery
    a("battery", "status"); a("battery reminder on", "battery_alert", "on"); a("turn off battery alerts", "battery_alert", "off")
    a("disable low battery warnings", "battery_alert", "off"); a("battery reminders", "battery_alert", "status"); a("cancel battery reminders", "battery_alert", "clear")
    a("remind me when battery is 30 percent", "battery_remind", "30"); a("remind me when the battery reaches 80", "battery_remind", "80")
    a("notify me at 50 percent battery", "battery_remind", "50"); a("remind me to unplug at 80 percent", "battery_unplug", "80")
    a("remind me to unplug the charger", "battery_unplug", "100"); a("tell me when the battery is full", "battery_unplug", "100")
    // regression: old behaviours
    a("open youtube", "open_app", "youtube"); a("close instagram", "close_app"); a("go home", "key", "home"); a("scroll down", "swipe", "up")
    a("flashlight on", "flashlight", "on"); a("volume up", "volume", "+"); a("take a screenshot", "key", "screenshot")
    a("call 98765 43210", "call"); a("search for pizza", "web_search"); a("search lofi on youtube", "youtube_search")
    a("show me the weather", "open_app"); a("how much battery is left", "status")
    // raw action
    val ra = VoiceParser.rawAction(VoiceParser.norm("Pragon settings")); if (ra?.value != "pragon") { fails++; println("FAIL rawAction pragon settings") }
    val ra2 = VoiceParser.rawAction(VoiceParser.norm("open settings")); if (ra2 != null) { fails++; println("FAIL rawAction open settings") }
    // parse(): typer bypass in float
    com.pragon.mobile.Typer.start()
    val p1 = VoiceParser.parse(listOf("I will mute this and say goodbye later"), true, PMode.AGENT, true)
    if (p1 !is VoiceParser.Cmd.Handle) { fails++; println("FAIL typer bypass: $p1") }
    val p2 = VoiceParser.parse(listOf("pragon goodbye"), true, PMode.MASTER, false)
    if (p2 !is VoiceParser.Cmd.Goodbye) { fails++; println("FAIL typer goodbye: $p2") }
    com.pragon.mobile.Typer.stop()
    val p3 = VoiceParser.parse(listOf("hello there my friend"), true, PMode.AGENT, false)
    if (p3 !is VoiceParser.Cmd.None) { fails++; println("FAIL agent no-wake: $p3") }
    // Typer text
    fun d(raw: String, exp: String) { val g = Typer.dictate(raw); if (g != exp) { fails++; println("FAIL dictate '$raw' -> '$g' (want '$exp')") } }
    d("hello comma how are you question mark", "hello, how are you?")
    d("meet me at 5 period see you there", "meet me at 5. See you there")
    d("first line new line second line", "first line\nsecond line")
    d("john at the rate gmail dot com", "john@gmail.com")
    d("open bracket hello close bracket", "(hello)")
    d("the dot product", "the dot product")
    d("thanks exclamation mark", "thanks!")
    fun j(e: String, p: String, exp: String) { val g = Typer.join(e, p); if (g != exp) { fails++; println("FAIL join '$e'+'$p' -> '$g' (want '$exp')") } }
    j("", "hello there", "Hello there"); j("Hello", "there", "Hello there"); j("Hello.", "how are you", "Hello. How are you")
    j("Hello", ", friend", "Hello, friend"); j("Hi\n", "there", "Hi\nThere"); j("Hi", "\nbye", "Hi\nbye"); j("Hi ", "there", "Hi there")
    fun s(raw: String, cls: Class<*>) { val g = Typer.interpret(raw); if (!cls.isInstance(g)) { fails++; println("FAIL interpret '$raw' -> $g") } }
    s("stop typing", Typer.Step.Stop::class.java); s("delete that", Typer.Step.DeleteLast::class.java); s("send", Typer.Step.Enter::class.java)
    s("clear all", Typer.Step.ClearAll::class.java); s("hello world", Typer.Step.Text::class.java); s("Pragon stop typing", Typer.Step.Stop::class.java)
    fun o(raw: String, act: String?, v: String? = null) { val r = VoiceParser.ollamaCommand(raw); val ok = if (act == null) r == null else r != null && r.action == act && (v == null || r.value == v); if (!ok) { fails++; println("FAIL ollama '$raw' -> ${r?.action}/${r?.value}") } }
    o("ollama pull llama3.2:3b", "ollama_pull", "llama3.2:3b"); o("Ollama pull hermes3", "ollama_pull", "hermes3"); o("pull model qwen2.5", "ollama_pull", "qwen2.5")
    o("pull ollama model gemma3:4b", "ollama_pull", "gemma3:4b"); o("pragon pull the model phi4-mini", "ollama_pull", "phi4-mini"); o("download model llama 3 point 2", "ollama_pull", "llama3.2")
    o("ollama list", "ollama_list"); o("list my models", "ollama_list"); o("which models do I have", "ollama_list"); o("use model hermes3", "ollama_use", "hermes3")
    o("pull the plug", null); o("how are you", null); o("open youtube", null)
    println(if (fails == 0) "ALL PARSER OK" else "$fails failures")
}
