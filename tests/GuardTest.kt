import com.pragon.mobile.Guard
fun main() {
    var f = 0
    fun c(h: String, want: Boolean) { if (Guard.isLocalHost(h) != want) { f++; println("FAIL isLocalHost($h) want $want") } }
    for (h in listOf("192.168.1.20", "10.0.0.5", "172.16.4.4", "172.31.255.1", "127.0.0.1", "localhost", "169.254.1.1", "100.100.1.1", "100.64.0.1", "mypc", "pc.local", "nas.lan", "::1", "fe80::1", "fd12::5", "[::1]", "printer.home.arpa"))
        c(h, true)
    for (h in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1", "100.63.0.1", "192.169.1.1", "11.0.0.1", "generativelanguage.googleapis.com", "api.openai.com", "example.com", "evil.local.example.com", "999.1.1.1", "", "2001:4860::1", "my-pc.tailnet.ts.net"))
        c(h, false)
    println(if (f == 0) "ALL GUARD OK" else "$f failures")
}
