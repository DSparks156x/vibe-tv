package com.vibe.tvlauncher.settings

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A tiny LAN web page for typing keys and URLs on a phone or computer instead of the remote.
 * Runs only while the settings screen is open, and every request must carry the one-time code shown on the TV
 * (it's in the QR code's URL), so other devices on the network can't change anything.
 */
class SetupServer(private val context: Context, private val onChanged: () -> Unit) {
    val code: String = (100000 + SecureRandom().nextInt(900000)).toString()
    @Volatile private var socket: ServerSocket? = null

    /** http://<tv-ip>:<port>/?c=<code>, or null if there's no LAN address. */
    var url: String? = null
        private set

    fun start() {
        val ip = lanAddress() ?: return
        val s = ServerSocket()
        s.reuseAddress = true
        // A fixed port keeps a bookmarked page working; fall back to any free port.
        runCatching { s.bind(InetSocketAddress(PORT)) }.onFailure { s.bind(InetSocketAddress(0)) }
        socket = s
        url = "http://$ip:${s.localPort}/?c=$code"
        thread(name = "vibe-setup", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use { runCatching { handle(it) }.onFailure { e -> Log.w(TAG, "request failed", e) } } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 10_000
        val input = BufferedInputStream(sock.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val (method, target) = parts
        val query = parse(target.substringAfter('?', ""))
        val path = target.substringBefore('?')
        val out = sock.getOutputStream()

        if (path != "/") return respond(out, 404, "text/plain", "Not found")
        when (method) {
            "GET" -> {
                if (query["c"] != code) return respond(out, 403, "text/html", SetupPage.denied())
                respond(out, 200, "text/html", SetupPage.render(SettingsRegistry.fields(context), code, null))
            }
            "POST" -> {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len > MAX_BODY) return respond(out, 413, "text/plain", "Too large")
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val form = parse(String(body, 0, read, Charsets.UTF_8))
                if (form["c"] != code) return respond(out, 403, "text/html", SetupPage.denied())
                val changes = SetupPage.changes(form.filterKeys { it != "c" }, SettingsRegistry.fields(context))
                val results = SettingsRegistry.apply(context, changes)
                onChanged()
                respond(out, 200, "text/html", SetupPage.render(SettingsRegistry.fields(context), code, results))
            }
            else -> respond(out, 405, "text/plain", "Method not allowed")
        }
    }

    private fun respond(out: java.io.OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = mapOf(200 to "OK", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 413 to "Payload Too Large")[status] ?: ""
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            if (buf.size() > 8192) return null
            buf.write(b)
        }
    }

    private fun parse(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate {
        val k = it.substringBefore('=')
        val v = it.substringAfter('=', "")
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    companion object {
        private const val TAG = "VibeSetup"
        const val PORT = 8765
        private const val MAX_BODY = 64 * 1024

        /** The TV's address on the home network (Wi-Fi or Ethernet). */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
