fun main() {
    val proxy = SmlDoH(
        dohURL = "https://cloudflare-dns.com/dns-query",
        offlineDns = mapOf("cloudflare-dns.com" to "203.32.120.226"),
        debugMode = true
    )
    proxy.start()
}