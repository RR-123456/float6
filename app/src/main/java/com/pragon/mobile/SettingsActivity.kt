package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Settings screen (assets/settings.html): pairing (QR / manual), permissions, forget this PC. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pairing = false

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val uri = Uri.parse(text.trim())
        val host = uri.host
        val key = uri.getQueryParameter("key")
        if (host.isNullOrBlank() || key.isNullOrBlank()) {
            toast("That QR code isn't from Pragon."); return@registerForActivityResult
        }
        startPairing(host, if (uri.port > 0) uri.port else 8000, key)
    }

    /** Android's own "Allow Pragon to set up a VPN connection?" screen (needed once for the firewall). */
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            Firewall.start(this)
            toast("Firewall on. " + Firewall.describe(this))
        } else {
            Guard.setFirewallOn(this, false)
            toast("The firewall needs your OK to run.")
        }
        js("loadSec()")
    }

    private fun askVpn() {
        val need = Firewall.prepareIntent(this)
        if (need == null) { Firewall.start(this); js("loadSec()") } else vpnConsent.launch(need)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("ask_vpn", false) == true) {
            // came from "firewall on" by voice: show Android's consent screen straight away
            if (Guard.firewallApps(this).isNotEmpty()) window.decorView.post { askVpn() }
        }
        web = WebView(this).apply {
            setBackgroundColor(0xFF0A0A0F.toInt())
            settings.javaScriptEnabled = true
            addJavascriptInterface(Bridge2(), "PragonNative")
            loadUrl("file:///android_asset/settings.html")
        }
        setContentView(web)
    }

    inner class Bridge2 {
        @JavascriptInterface fun getState(): String {
            val pm = getSystemService(PowerManager::class.java)
            val host = Prefs.host(this@SettingsActivity)
            return JSONObject()
                .put("paired", Bridge.status.startsWith("Connected"))
                .put("host", if (host.isBlank()) "" else "$host:${Prefs.port(this@SettingsActivity)}")
                .put("status", Bridge.status)
                .put("a11y", PragonAccessibilityService.instance != null)
                .put("overlay", Settings.canDrawOverlays(this@SettingsActivity))
                .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                .toString()
        }
        @JavascriptInterface fun scanQr() {
            runOnUiThread {
                scan.launch(
                    ScanOptions().setPrompt("Scan the QR shown in Pragon > Remote - PhoneView")
                        .setBeepEnabled(false).setOrientationLocked(false)
                )
            }
        }
        @JavascriptInterface fun connect(hostPort: String, key: String) {
            val parts = hostPort.trim().split(":")
            val host = parts.getOrNull(0).orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 8000
            if (host.isBlank() || key.isBlank()) toast("Enter the PC address and the key.")
            else runOnUiThread { startPairing(host, port, key) }
        }
        @JavascriptInterface fun getAi(): String {
            val own = Prefs.aiKey(this@SettingsActivity)
            return JSONObject()
                .put("own", own.isNotBlank())
                .put("pc", Prefs.pcAiKey(this@SettingsActivity).isNotBlank())
                .put("model", Prefs.aiModel(this@SettingsActivity))
                .put("hint", if (own.length > 8) own.take(4) + "..." + own.takeLast(3) else "")
                .toString()
        }
        @JavascriptInterface fun saveAi(key: String, model: String) {
            val cur = Prefs.aiKey(this@SettingsActivity)
            Prefs.saveAi(this@SettingsActivity, if (key.isBlank()) cur else key.trim(), model.trim())
            toast("Saved.")
        }
        // ---- AI engine (Gemini online / Ollama / custom OpenAI-compatible) ----
        @JavascriptInterface fun getEngine(): String {
            val c = this@SettingsActivity
            return JSONObject()
                .put("engine", Prefs.engine(c))
                .put("ollamaHost", Prefs.ollamaHost(c))
                .put("ollamaModel", Prefs.ollamaModel(c))
                .put("openaiBase", Prefs.openaiBase(c))
                .put("openaiModel", Prefs.openaiModel(c))
                .put("openaiKeySet", Prefs.openaiKey(c).isNotBlank())
                .toString()
        }
        @JavascriptInterface fun saveEngine(engine: String, oHost: String, oModel: String, base: String, key: String, cModel: String) {
            val c = this@SettingsActivity
            Prefs.saveEngine(
                c, engine, oHost.trim(), oModel.trim(), base.trim(),
                if (key.isBlank()) null else key.trim(), cModel.trim()
            )
            toast("Saved. Engine: " + Prefs.engineLabel(c))
        }
        @JavascriptInterface fun clearEngineKey() {
            val c = this@SettingsActivity
            Prefs.saveEngine(c, Prefs.engine(c), Prefs.ollamaHost(c), Prefs.ollamaModel(c), Prefs.openaiBase(c), "", Prefs.openaiModel(c))
            toast("Custom server key removed.")
        }
        /** Lists the models the server has (Ollama /api/tags or OpenAI /models). Answers via pmModels(json). */
        @JavascriptInterface fun listModels(engine: String, oHost: String, base: String, key: String) {
            Thread {
                val names = JSONArray()
                var err = ""
                try {
                    if (engine == "openai") {
                        val rb = Request.Builder().url(LocalLlmClient.openaiBase(base) + "/models")
                        val k = if (key.isBlank()) Prefs.openaiKey(this@SettingsActivity) else key
                        if (k.isNotBlank()) rb.header("Authorization", "Bearer $k")
                        LocalLlmClient.http.newCall(rb.build()).execute().use { r ->
                            val raw = r.body?.string() ?: ""
                            if (!r.isSuccessful) err = "HTTP ${r.code}"
                            else {
                                val arr = JSONObject(raw).optJSONArray("data")
                                if (arr != null) for (i in 0 until arr.length()) names.put(arr.getJSONObject(i).optString("id"))
                            }
                        }
                    } else {
                        val req = Request.Builder().url(LocalLlmClient.ollamaBase(oHost) + "/api/tags").build()
                        LocalLlmClient.http.newCall(req).execute().use { r ->
                            val raw = r.body?.string() ?: ""
                            if (!r.isSuccessful) err = "HTTP ${r.code}"
                            else {
                                val arr = JSONObject(raw).optJSONArray("models")
                                if (arr != null) for (i in 0 until arr.length()) names.put(arr.getJSONObject(i).optString("name"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    err = e.message ?: "can't connect"
                }
                js("pmModels(" + JSONObject().put("models", names).put("error", err).toString() + ")")
            }.start()
        }
        /** Lists installed Ollama models with sizes. Answers via pmInstalled(json). */
        @JavascriptInterface fun listOllama(oHost: String) {
            Thread {
                val arr = JSONArray()
                var err = ""
                try {
                    for (m in OllamaTools.list(OllamaTools.base(this@SettingsActivity, oHost)))
                        arr.put(JSONObject().put("name", m.name).put("size", OllamaTools.fmtSize(m.size)))
                } catch (e: Exception) { err = e.message ?: "can't connect" }
                js("pmInstalled(" + JSONObject().put("models", arr).put("error", err).put("current", Prefs.ollamaModel(this@SettingsActivity)).toString() + ")")
            }.start()
        }
        /** Downloads a model on the Ollama server (anything `ollama pull` accepts). Progress via pmPullProgress(pct, text, done). */
        @JavascriptInterface fun pullModel(oHost: String, model: String) {
            val name = model.trim()
            if (name.isEmpty()) { toast("Type a model name to pull."); return }
            Thread {
                var last = 0L
                val err = OllamaTools.pull(OllamaTools.base(this@SettingsActivity, oHost), name) { st, pct, done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 350 || st == "success") {
                        last = now
                        val size = if (total > 0) " - " + OllamaTools.fmtSize(done) + " of " + OllamaTools.fmtSize(total) else ""
                        js("pmPullProgress(" + pct + "," + JSONObject.quote(st + size) + ",false)")
                    }
                }
                if (err == null) js("pmPullProgress(100," + JSONObject.quote("Done - $name is ready.") + ",true)")
                else js("pmPullProgress(-1," + JSONObject.quote("Failed: $err") + ",true)")
            }.start()
        }
        @JavascriptInterface fun deleteModel(oHost: String, model: String) {
            Thread {
                val err = OllamaTools.delete(OllamaTools.base(this@SettingsActivity, oHost), model.trim())
                toast(if (err == null) "Deleted ${model.trim()}." else "Couldn't delete: $err")
                listOllama(oHost)
            }.start()
        }
        /** Makes this the model Pragon uses (and switches the engine to Ollama). */
        @JavascriptInterface fun useModel(oHost: String, model: String) {
            val c = this@SettingsActivity
            Prefs.saveEngine(c, "ollama", oHost.trim().ifBlank { Prefs.ollamaHost(c) }, model.trim(), Prefs.openaiBase(c), null, Prefs.openaiModel(c))
            Brain.warmup(c)
            toast("Now using ${model.trim()} on Ollama.")
        }

        // ---- security: privacy shield, scanner, firewall, allowed apps, sidebar, macros ----
        @JavascriptInterface fun getSecurity(): String {
            val c = this@SettingsActivity
            val log = JSONArray()
            Guard.connectionLog().take(25).forEach { log.put(JSONObject().put("host", it.host).put("allowed", it.allowed).put("count", it.count)) }
            return Guard.summary(c)
                .put("log", log)
                .put("fwRunning", Firewall.running())
                .put("fwDropped", Firewall.droppedPackets)
                .put("fwApps", Guard.firewallApps(c).size)
                .put("allowedCount", Guard.allowedApps(c).size)
                .put("secure", AccessFeatures.canWriteSecure(c))
                .put("writeSettings", Settings.System.canWrite(c))
                .put("grantCmd", AccessFeatures.GRANT_CMD)
                .put("sidebarOn", SidebarService.running)
                .put("sidebarLeft", SidebarService.leftSide(c))
                .toString()
        }
        @JavascriptInterface fun setSwitch(name: String, on: Boolean) {
            val c = this@SettingsActivity
            when (name) {
                "privacy" -> Guard.setPrivacy(c, on)
                "allowOnly" -> {
                    if (on && Guard.allowedApps(c).isEmpty()) { toast("Tick at least one allowed app first."); return }
                    Guard.setAllowlist(c, on)
                }
                "screenAI" -> Guard.setScreenReading(c, on)
                "a11y" -> Guard.setA11yFeatures(c, on)
                "macros" -> Guard.setMacros(c, on)
            }
        }
        @JavascriptInterface fun saveTrusted(csv: String) {
            Guard.setTrustedHosts(this@SettingsActivity, csv.split(",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() })
            toast("Trusted hosts saved.")
        }
        @JavascriptInterface fun clearLog() { Guard.clearLog() }

        /** Every app with a launcher icon, and which lists it is on. */
        @JavascriptInterface fun getApps(): String {
            val c = this@SettingsActivity
            val pm = packageManager
            val allowed = Guard.allowedApps(c); val side = Guard.sidebarApps(c).toSet(); val fw = Guard.firewallApps(c)
            val arr = JSONArray()
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .filter { it.second != packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
                .forEach { arr.put(JSONObject().put("pkg", it.second).put("label", it.first)
                    .put("allowed", it.second in allowed).put("sidebar", it.second in side).put("fw", it.second in fw)) }
            return arr.toString()
        }
        @JavascriptInterface fun saveApps(kind: String, jsonArr: String) {
            val c = this@SettingsActivity
            val l = try { JSONArray(jsonArr).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { return }
            when (kind) {
                "allowed" -> { Guard.setAllowedApps(c, l); if (l.isEmpty()) Guard.setAllowlist(c, false) }
                "sidebar" -> Guard.setSidebarApps(c, l)
                "fw" -> {
                    Guard.setFirewallApps(c, l)
                    if (Guard.firewallOn(c) && (l.isNotEmpty() || Guard.firewallMode(c) == "allow")) Firewall.start(c)
                    else if (l.isEmpty() && Guard.firewallMode(c) == "block") Firewall.stop(c)
                }
            }
            toast("Saved.")
        }
        @JavascriptInterface fun setFirewall(on: Boolean) {
            val c = this@SettingsActivity
            if (!on) { Firewall.stop(c); toast("Firewall off. Every app has internet again."); return }
            if (Guard.firewallApps(c).isEmpty() && Guard.firewallMode(c) == "block") { toast("Tick the apps to block first."); js("loadSec()"); return }
            runOnUiThread { askVpn() }
        }
        @JavascriptInterface fun setFirewallMode(mode: String) {
            val c = this@SettingsActivity
            Guard.setFirewallMode(c, mode)
            if (Guard.firewallOn(c)) Firewall.start(c)
        }
        @JavascriptInterface fun runScan() {
            Thread {
                val f = try { Scanner.scan(this@SettingsActivity) } catch (e: Exception) { emptyList() }
                js("pmScan(" + Scanner.toJson(f).toString() + ")")
            }.start()
        }
        @JavascriptInterface fun appInfo(pkg: String) {
            runOnUiThread { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))) }
        }
        @JavascriptInterface fun uninstall(pkg: String) {
            runOnUiThread { startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))) }
        }
        @JavascriptInterface fun blockApp(pkg: String) {
            val c = this@SettingsActivity
            Guard.setFirewallMode(c, "block")
            Guard.setFirewallApps(c, Guard.firewallApps(c) + pkg)
            toast("Added to the firewall list. Switch the firewall on to block it.")
            js("loadSec()")
        }
        @JavascriptInterface fun allowWriteSettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        @JavascriptInterface fun setSidebar(on: Boolean, left: Boolean) {
            val c = this@SettingsActivity
            val sideChanged = SidebarService.leftSide(c) != left
            SidebarService.setLeft(c, left)
            SidebarService.setAutoStart(c, on)
            if (on) {
                if (SidebarService.running && sideChanged) { SidebarService.stop(c); Thread.sleep(300) }
                SidebarService.start(c)?.let { toast(it) } ?: toast("Sidebar on. Tap the thin bar on the screen edge.")
            } else { SidebarService.stop(c); toast("Sidebar off.") }
            js("loadSec()")
        }
        @JavascriptInterface fun listMacros(): String {
            val c = this@SettingsActivity
            val a = JSONArray()
            Macros.names(c).forEach { a.put(JSONObject().put("name", it).put("steps", Macros.load(c, it).size).put("text", Macros.describe(c, it))) }
            return JSONObject().put("macros", a).put("recording", Macros.recordingName ?: "").put("playing", Macros.playing ?: "").toString()
        }
        @JavascriptInterface fun macroRun(name: String) { Thread { toast(Macros.run(this@SettingsActivity, name)) }.start() }
        @JavascriptInterface fun macroDelete(name: String) { Macros.delete(this@SettingsActivity, name); js("loadMacros()") }
        @JavascriptInterface fun macroRecord(name: String) { toast(Macros.start(this@SettingsActivity, name.trim())); js("loadMacros()") }
        @JavascriptInterface fun macroStop() {
            toast(if (Macros.isRecording) Macros.stop(this@SettingsActivity) else { Macros.cancelPlayback(); "Stopped." })
            js("loadMacros()")
        }

        // ---- battery reminders ----
        @JavascriptInterface fun getBattery(): String {
            val c = this@SettingsActivity
            return JSONObject().put("lowOn", BatteryReminder.lowOn(c)).put("levels", BatteryReminder.lowLevelsText(c))
                .put("target", BatteryReminder.chargeTarget(c)).put("level", BatteryReminder.level(c))
                .put("summary", BatteryReminder.describe(c)).toString()
        }
        @JavascriptInterface fun saveBattery(lowOn: Boolean, levels: String, target: Int) {
            val c = this@SettingsActivity
            BatteryReminder.setLow(c, lowOn, levels)
            BatteryReminder.setChargeTarget(c, target)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
            toast("Battery reminders saved.")
        }
        // ---- mode, voice and memory ----
        @JavascriptInterface fun getMode(): String = Prefs.mode(this@SettingsActivity).key
        @JavascriptInterface fun saveMode(m: String) {
            Prefs.saveMode(this@SettingsActivity, PMode.from(m))
            toast("Mode: " + Prefs.mode(this@SettingsActivity).label)
        }
        @JavascriptInterface fun getVoice(): String {
            val c = this@SettingsActivity
            return JSONObject()
                .put("callMe", Prefs.callMe(c)).put("speechLang", Prefs.speechLang(c)).put("replyLang", Prefs.replyLang(c))
                .put("rate", Prefs.ttsRate(c).toDouble()).put("pitch", Prefs.ttsPitch(c).toDouble())
                .put("voice", Prefs.ttsVoice(c)).put("speakTyped", Prefs.speakTyped(c))
                .put("floatNeedsWake", Prefs.floatNeedsWake(c)).toString()
        }
        @JavascriptInterface fun saveVoice(
            callMe: String, speechLang: String, replyLang: String, rate: Double, pitch: Double,
            voice: String, speakTyped: Boolean, floatNeedsWake: Boolean,
        ) {
            val c = this@SettingsActivity
            Prefs.saveVoice(c, callMe, speechLang, replyLang, rate.toFloat(), pitch.toFloat(), voice, speakTyped, floatNeedsWake)
            Speaker.get(c).reload()
            toast("Voice settings saved.")
        }
        @JavascriptInterface fun listVoices(): String = Speaker.get(this@SettingsActivity).voicesJson()
        @JavascriptInterface fun nextVoice() {
            val sp = Speaker.get(this@SettingsActivity)
            val t = sp.changeVoice(reset = false)
            sp.speak(t)
            toast(t)
        }
        @JavascriptInterface fun testVoice() { Speaker.get(this@SettingsActivity).also { it.reload(); it.test() } }
        @JavascriptInterface fun getMemory(): String {
            val c = this@SettingsActivity
            return JSONObject().put("count", PragonMemory.count(c)).put("text", PragonMemory.promptBlock(c)).toString()
        }
        @JavascriptInterface fun clearMemory() {
            PragonMemory.clear(this@SettingsActivity)
            toast("Memory cleared.")
        }
        @JavascriptInterface fun clearAiKey() {
            Prefs.saveAi(this@SettingsActivity, "", Prefs.aiModel(this@SettingsActivity))
            toast("Your key was removed.")
        }
        @JavascriptInterface fun openA11y() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Find PragonMobile in the list and turn it on.")
            }
        }
        @JavascriptInterface fun openOverlay() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        @JavascriptInterface fun openBattery() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        @JavascriptInterface fun forget() {
            runOnUiThread {
                ContextCompat.startForegroundService(
                    this@SettingsActivity,
                    Intent(this@SettingsActivity, PragonService::class.java).setAction(PragonService.ACTION_STOP)
                )
                Prefs.clear(this@SettingsActivity)
                RemoteBridge.socket = null
                Bridge.set("Not paired yet - scan the QR from Pragon")
            }
        }
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
    }

    private fun startPairing(host: String, port: Int, key: String) {
        pairing = true
        Prefs.save(this, host, port, "")
        ContextCompat.startForegroundService(
            this, Intent(this, PragonService::class.java).putExtra(PragonService.EXTRA_KEY, key.trim().uppercase())
        )
        Bridge.set("Pairing with $host:$port ...")
    }

    override fun onResume() {
        super.onResume()
        // After a successful pairing, go straight back to PhoneView.
        Bridge.listener = { s -> if (pairing && s.startsWith("Connected")) finish() }
    }

    override fun onPause() { Bridge.listener = null; super.onPause() }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
}
