import com.pragon.mobile.*
var fails2 = 0
fun t(inp: String, action: String?, value: String? = null) {
    val c = VoiceParser.cleanCommand(inp)
    val r = VoiceParser.actionFor(c)
    val ok = if (action == null) r == null else (r != null && r.action == action && (value == null || r.value == value))
    if (!ok) { fails2++; println("FAIL '$inp' [$c] -> ${r?.action}/${r?.value} (want $action/$value)") }
}
fun main() {
    // volume
    t("increase volume", "volume", "+"); t("pragon increase volume", "volume", "+"); t("pragon decrease volume", "volume", "-")
    t("turn up the volume", "volume", "+"); t("turn down the volume", "volume", "-"); t("raise the volume by 20", "volume", "+20")
    t("lower volume by 30 percent", "volume", "-30"); t("volume up", "volume", "+"); t("volume down", "volume", "-")
    t("set volume to 50", "volume", "set:50"); t("volume 70 percent", "volume", "set:70"); t("max volume", "volume", "max"); t("volume max", "volume", "max")
    t("minimum volume", "volume", "min"); t("make it louder", "volume", "+"); t("quieter", "volume", "-"); t("increase the sound", "volume", "+")
    t("set the volume to 30 percent", "volume", "set:30"); t("increase volume to 80", "volume", "set:80"); t("half volume", "volume", "set:50")
    // brightness
    t("increase brightness", "brightness", "+"); t("decrease brightness", "brightness", "-"); t("pragon increase the brightness", "brightness", "+")
    t("brighten the screen", "brightness", "+"); t("dim the screen", "brightness", "-"); t("make it brighter", "brightness", "+"); t("dimmer", "brightness", "-")
    t("set brightness to 70", "brightness", "set:70"); t("brightness 40 percent", "brightness", "set:40"); t("max brightness", "brightness", "max")
    t("lowest brightness", "brightness", "min"); t("brightness up", "brightness", "+"); t("lower the screen brightness", "brightness", "-")
    t("auto brightness on", "brightness", "auto:on"); t("turn off automatic brightness", "brightness", "auto:off"); t("decrease brightness by 25", "brightness", "-25")
    // flashlight
    t("increase flashlight brightness", "flashlight_level", "+"); t("decrease flashlight brightness", "flashlight_level", "-"); t("flashlight brighter", "flashlight_level", "+")
    t("torch max", "flashlight_level", "max"); t("set flashlight to 50", "flashlight_level", "set:50"); t("dim the flashlight", "flashlight_level", "-"); t("max flashlight", "flashlight_level", "max")
    t("flashlight on", "flashlight", "on"); t("turn off the torch", "flashlight", "off"); t("flashlight", "flashlight")
    // close
    t("close youtube", "close_app", "youtube"); t("close", "close_app", ""); t("close this app", "close_app", "this"); t("quit instagram", "close_app", "instagram")
    t("force stop youtube", "force_stop", "youtube"); t("force close chrome", "force_stop", "chrome"); t("close all apps", "close_all"); t("clear recent apps", "close_all"); t("clean memory", "close_all")
    t("kill chrome completely", "force_stop", "chrome"); t("close all the background apps", "close_all")
    // macros
    t("record macro morning", "macro_record", "morning"); t("start recording macro work", "macro_record", "work"); t("record a macro", "macro_record", "")
    t("start recording", "macro_record", ""); t("stop recording", "macro_stop"); t("save macro", "macro_stop"); t("stop macro", "macro_stop")
    t("run macro morning", "macro_run", "morning"); t("play macro work", "macro_run", "work"); t("macro morning", "macro_run", "morning"); t("run morning macro", "macro_run", "morning")
    t("delete macro morning", "macro_delete", "morning"); t("list macros", "macro_list"); t("what macros do i have", "macro_list"); t("cancel macro", "macro_cancel")
    t("run macro morning 3 times", "macro_run", "morning 3 times"); t("play despacito", "youtube_search"); t("play", "key", "play")
    // screen
    t("read the screen", "screen_read"); t("read screen aloud", "screen_read"); t("what is on my screen", "screen_ask"); t("what's on the screen", "screen_ask")
    t("summarize this page", "screen_ask"); t("describe the screen", "screen_ask"); t("answer the question on the screen", "screen_ask"); t("solve this problem", "screen_ask")
    t("on screen who wrote this", "screen_ask", "who wrote this"); t("what is the price on the screen", "screen_ask", "what is the price"); t("how many items are on this page", "screen_ask")
    t("what is the capital of france", null); t("read a book", null)
    // security
    t("scan my phone", "scan"); t("scan for viruses", "scan"); t("antivirus scan", "scan"); t("check my apps", "scan"); t("run a security scan", "scan")
    t("firewall on", "firewall", "on"); t("turn off the firewall", "firewall", "off"); t("enable firewall", "firewall", "on"); t("firewall status", "firewall", "status"); t("is the firewall on", "firewall", "status")
    t("privacy shield on", "privacy", "on"); t("privacy", "privacy", "status"); t("disable privacy shield", "privacy", "off"); t("show the privacy log", "privacy", "log"); t("who did you connect to", "privacy", "log")
    t("allowed apps on", "allowlist", "on"); t("allowed apps", "allowlist", "status"); t("turn off allowed apps", "allowlist", "off"); t("only open allowed apps", "allowlist", "on")
    t("open sidebar", "sidebar", "open"); t("show the smart sidebar", "sidebar", "open"); t("sidebar on", "sidebar", "on"); t("turn off sidebar", "sidebar", "off"); t("hide sidebar", "sidebar", "close")
    t("turn on talkback", "a11y_feature", "talkback:on"); t("turn off talk back", "a11y_feature", "talkback:off"); t("live caption on", "a11y_feature", "caption:on")
    t("enable live captions", "a11y_feature", "caption:on"); t("turn on text to speech", "a11y_feature", "tts:on"); t("disable talkback", "a11y_feature", "talkback:off")
    // regressions
    t("open privacy settings", "settings", "privacy"); t("pause", "key", "pause"); t("click subscribe", "click", "subscribe"); t("calculate 5 plus 5", "calc")
    t("open youtube", "open_app", "youtube"); t("go home", "key", "home"); t("typer mode", "typer", "on"); t("start stopwatch", "stopwatch", "start")
    t("set volume", null); t("volume", null); t("turn up", null)
    println(if (fails2 == 0) "ALL PARSER2 OK" else "$fails2 failures")
}
