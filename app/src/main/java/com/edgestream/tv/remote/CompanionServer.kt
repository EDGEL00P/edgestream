package com.edgestream.tv.remote

import android.content.Context
import android.view.KeyEvent
import com.edgestream.tv.data.Repository
import com.edgestream.tv.data.SourceInputParser
import androidx.compose.ui.graphics.toArgb
import com.edgestream.tv.data.db.SourceEntity
import com.edgestream.tv.data.db.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A deliberately tiny HTTP/1.1 server for the phone companion. It serves one page and a handful
 * of JSON endpoints on the home network, only while the app is in the foreground.
 *
 * Hardening: bound to the TV's LAN address (never 0.0.0.0), every API call needs the pairing code
 * in the X-Edge-Token header (no more ?t= in URLs, which leaks into logs, history and Referer),
 * token comparison is constant-time, repeated wrong codes from one address are locked out, and the
 * worker pool has a fixed queue so a burst of connections cannot exhaust the process.
 */
class CompanionServer(private val context: Context, private val repo: Repository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = ThreadPoolExecutor(
        0, MAX_WORKERS, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUED),
        ThreadPoolExecutor.AbortPolicy(),
    )
    @Volatile private var socket: ServerSocket? = null
    private var advert: android.net.nsd.NsdManager.RegistrationListener? = null
    private val replay = ReplayGuard()

    fun start() {
        if (socket != null) return
        // Bind to the home-network address only: nothing else on a shared/VPN interface answers.
        val host = runCatching { InetAddress.getByName(PhoneLink.lanAddress(context) ?: return) }.getOrNull() ?: return
        val server = runCatching {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(host, PhoneLink.PORT), 16) }
        }.getOrNull() ?: return
        socket = server
        PhoneLink.setRunning(true)
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: SocketException) { break }
                try {
                    pool.execute { runCatching { handle(client) }; runCatching { client.close() } }
                } catch (_: RejectedExecutionException) {
                    runCatching { respond(client.getOutputStream(), 503, "text/plain", "Busy".toByteArray()) }
                    runCatching { client.close() }
                }
            }
        }, "companion-accept").apply { isDaemon = true }.start()
        advertise()
    }

    /** Lets another EdgeStream TV on the network find this one (Settings › Transfer Setup). */
    private fun advertise() {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager ?: return
        val listener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: android.net.nsd.NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: android.net.nsd.NsdServiceInfo, code: Int) { android.util.Log.w("EdgeStream", "network advert failed ($code)") }
            override fun onServiceUnregistered(info: android.net.nsd.NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: android.net.nsd.NsdServiceInfo, code: Int) = Unit
        }
        val info = android.net.nsd.NsdServiceInfo().apply {
            serviceName = android.os.Build.MODEL; serviceType = SetupSender.SERVICE_TYPE; port = PhoneLink.PORT
        }
        runCatching { nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { advert = listener }
    }

    fun stop() {
        advert?.let { l -> runCatching { (context.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager).unregisterService(l) } }
        advert = null
        runCatching { socket?.close() }
        socket = null
        PhoneLink.setRunning(false)
    }


    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        // The total budget, not just the gap between bytes, bounds how long one client holds a worker.
        // A whole setup from another TV (providers, calendar, My List) is far larger than a phone request.
        val req = readRequest(
            BufferedInputStream(client.getInputStream()), setTimeout = { client.soTimeout = it },
            maxBody = { if (it == "/api/setup") 4_000_000 else 64_000 },
        ) ?: return
        val out = client.getOutputStream()
        if (req.method != "GET" && req.method != "POST") return respond(out, 405, "text/plain", "Method not allowed".toByteArray())
        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            val page = context.assets.open("companion.html").use { it.readBytes() }
            return respond(out, 200, "text/html; charset=utf-8", page, html = true)
        }
        if (!req.path.startsWith("/api/")) return respond(out, 404, "text/plain", "Not found".toByteArray())

        val ip = client.inetAddress?.hostAddress.orEmpty()
        // Counted before the check, so parallel guesses can't all slip under the limit.
        if (!PhoneLink.limiter.tryAttempt(ip)) {
            return respond(out, 429, "text/plain", "Too many attempts".toByteArray(), extra = "Retry-After: 60\r\n")
        }
        // Another TV sending its setup: authenticated by the code shown on this TV, not by pairing.
        if (req.path == "/api/setup/hello" && req.method == "GET") {
            val pub = SetupReceiver.hello() ?: return json(out, 404, JSONObject().put("error", "This TV isn't waiting for a setup"))
            return json(out, 200, JSONObject().put("pub", pub).put("name", android.os.Build.MODEL))
        }
        if (req.path == "/api/setup" && req.method == "POST") {
            val o = runCatching { JSONObject(req.body) }.getOrNull() ?: return json(out, 400, JSONObject().put("error", "Unreadable setup"))
            val plain = SetupReceiver.open(o.optString("pub"), o.optString("e"))
                ?: return json(out, 403, JSONObject().put("error", "Wrong code, or this TV stopped waiting"))
            PhoneLink.limiter.onSuccess(ip)
            val error = runCatching {
                kotlinx.coroutines.runBlocking { repo.importSetup(context, JSONObject(plain)) }
            }.exceptionOrNull()?.let { it.message ?: "Couldn't apply the setup" }
            SetupReceiver.finished(error)
            return json(out, if (error == null) 200 else 500, if (error == null) OK else JSONObject().put("error", error))
        }
        // Header only: a token in the query string ends up in proxy logs and browser history.
        val token = req.headers["x-edge-token"]
        if (!tokenMatches(token, PhoneLink.token(context))) {
            return json(out, 401, JSONObject().put("error", "Wrong pairing code"))
        }
        PhoneLink.limiter.onSuccess(ip)
        PhoneLink.touch()

        // Sealed link: the phone that scanned the QR code holds the key; it seals every request
        // body and asks for sealed replies. A phone paired by typing the code has no key, so it
        // keeps the remote buttons but can't send text.
        val key = PhoneLink.linkKey(context)
        val sealedReplies = req.headers["x-edge-enc"] == "1"
        fun reply(code: Int, b: Any) =
            json(out, code, if (sealedReplies) JSONObject().put("e", LinkCipher.seal(key, b.toString())) else b)
        fun error(code: Int, message: String) = reply(code, JSONObject().put("error", message))

        val raw = runCatching { JSONObject(req.body.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val envelope = raw.optString("e").takeIf { raw.has("e") }
        val body = if (envelope == null) raw else {
            LinkCipher.openRequest(key, envelope, req.path, replay)
                ?: return error(400, "That request couldn't be verified. Check your phone's clock, or scan the QR code on the TV again.")
        }
        val sealed = envelope != null
        when ("${req.method} ${req.path}") {
            "GET /api/state" -> reply(200, state(secure = sealedReplies))
            "POST /api/channels" -> if (!sealed) error(403, RESCAN) else reply(200, channels(body.optString("q").take(100)))
            "POST /api/key" -> {
                val code = KEYS[body.optString("key")] ?: return error(400, "Unknown key")
                PhoneLink.post(PhoneLink.Command.Key(code)); reply(200, OK)
            }
            "POST /api/command" -> {
                if (!sealed) return error(403, RESCAN)
                val cmd = VoiceCommand.parse(body.optString("text").take(200)) ?: return error(400, "Say or type something first")
                // A question is answered in words, here and on the TV; everything else is carried out.
                if (cmd.isQuestion) {
                    val answer = PhoneLink.answerer?.let { ask ->
                        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(6_000) { runCatching { ask(cmd) }.getOrNull() } }
                    } ?: "Open EdgeStream on the TV to ask that"
                    return reply(200, JSONObject().put("ok", true).put("message", answer))
                }
                PhoneLink.post(PhoneLink.Command.Voice(cmd))
                reply(200, JSONObject().put("ok", true).put("message", when (cmd) {
                    is VoiceCommand.Tune -> "Channel ${cmd.number}"
                    is VoiceCommand.Watch -> "Finding “${cmd.channel}”…"
                    is VoiceCommand.Open -> "Opening ${cmd.screen.label}"
                    is VoiceCommand.Search -> "Searching for “${cmd.query}”"
                    is VoiceCommand.WhatsOn, VoiceCommand.Games, is VoiceCommand.Team -> ""
                }))
            }
            "POST /api/field" -> {
                if (!sealed) return error(403, RESCAN)
                if (PhoneLink.field.value == null) return error(409, "Select a text box on the TV first")
                PhoneLink.post(PhoneLink.Command.SetField(body.optString("text").take(2_000))); reply(200, OK)
            }
            "POST /api/play" -> { PhoneLink.post(PhoneLink.Command.Play(body.optString("key"))); reply(200, OK) }
            "POST /api/open" -> { PhoneLink.post(PhoneLink.Command.Open(body.optString("screen"))); reply(200, OK) }
            "POST /api/source" -> if (!sealed) error(403, RESCAN) else addSource(body).let { (code, b) -> reply(code, b) }
            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    /** The TV's current broadcaster theme, so the phone page wears the same colours. */
    private fun theme(): JSONObject {
        val p = com.edgestream.tv.ui.Edge.palette
        fun hex(c: androidx.compose.ui.graphics.Color) = "#%06x".format(c.toArgb() and 0xFFFFFF)
        return JSONObject().put("bg", hex(p.bg)).put("panel", hex(p.panel)).put("panelHi", hex(p.panelHi)).put("line", hex(p.line))
            .put("text", hex(p.text)).put("dim", hex(p.dim)).put("faint", hex(p.faint)).put("accent", hex(p.accent)).put("onAccent", hex(p.onAccent))
    }

    private fun state(secure: Boolean): JSONObject = runBlocking {
        val sources = repo.sources.first()
        JSONObject()
            .put("sources", JSONArray(sources.map {
                JSONObject().put("id", it.id).put("name", it.name).put("type", it.type.name)
                    .put("channels", it.channelCount).put("guide", it.guideMatched).put("error", it.lastError ?: JSONObject.NULL)
            }))
            .put("status", repo.status.value.message)
            .put("secure", secure)
            .put("theme", theme())
            .put("field", PhoneLink.field.value?.let { JSONObject().put("label", it.label).put("value", it.value).put("secret", it.secret) } ?: JSONObject.NULL)
    }

    private fun channels(q: String): JSONArray = runBlocking {
        val list = if (q.isBlank()) repo.channels.first().filter { !it.offline }.take(400) else repo.searchChannels(q)
        val epg = repo.nowAndNext()
        JSONArray(list.map { c ->
            JSONObject().put("key", c.key).put("name", c.name).put("number", com.edgestream.tv.data.ChannelNumbers.lcn(c)).put("group", c.groupName)
                .put("logo", c.logo ?: JSONObject.NULL)
                .put("now", c.epgId?.let { epg[it]?.first?.title } ?: JSONObject.NULL)
        })
    }

    /** Same rules as the TV's own form, and the provider is tried before anything is saved. */
    private fun addSource(b: JSONObject): Pair<Int, JSONObject> {
        fun problem(message: String) = 400 to JSONObject().put("error", message)
        val type = if (b.optString("type").equals("m3u", true)) SourceType.M3U else SourceType.XTREAM
        val url = b.optString("url").trim()
        if (url.isEmpty()) return problem("Enter the server or playlist address")
        val epg = b.optString("epg").trim()
        val source = SourceEntity(
            name = b.optString("name").trim().ifEmpty { url.substringAfter("://").substringBefore(':').substringBefore('/') },
            type = type,
            url = SourceInputParser.playableUrl(url) ?: return problem("Only http:// and https:// addresses are supported"),
            username = b.optString("username").trim(), password = b.optString("password").trim(),
            epgUrl = if (epg.isEmpty()) "" else SourceInputParser.playableUrl(epg) ?: return problem("The guide address must start with http:// or https://"),
        )
        if (type == SourceType.XTREAM && (source.username.isBlank() || source.password.isBlank())) {
            return problem("Enter your username and password")
        }
        runBlocking { repo.verifySource(source) }?.let { return problem(it) }
        scope.launch { com.edgestream.tv.work.RefreshWorker.runNow(context, repo.insertSource(source)) }
        return 200 to JSONObject().put("ok", true).put("message", "Login checked. Loading “${source.name}” on the TV…")
    }

    // ---- HTTP plumbing ------------------------------------------------------------------------


    private fun json(out: OutputStream, code: Int, body: Any) =
        respond(out, code, "application/json; charset=utf-8", body.toString().toByteArray(Charsets.UTF_8))

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, html: Boolean = false, extra: String = "") {
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            403 -> "Forbidden"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 429 -> "Too Many Requests"; 503 -> "Service Unavailable"
            else -> "Error"
        }
        // The page needs inline styles/scripts and remote channel logos; JSON/plain never do.
        val csp = if (html) "default-src 'none'; img-src http: https: data:; style-src 'unsafe-inline'; " +
            "script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        else "default-src 'none'; frame-ancestors 'none'"
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
                "Referrer-Policy: no-referrer\r\nContent-Security-Policy: $csp\r\n" +
                "Connection: close\r\n$extra\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        out.write(body)
        out.flush()
    }

    // ---- pairing attempts ---------------------------------------------------------------------

    private fun tokenMatches(given: String?, want: String): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), want.toByteArray(Charsets.UTF_8))

    companion object {
        private const val MAX_WORKERS = 4
        private val OK get() = JSONObject().put("ok", true)
        private const val RESCAN = "Scan the QR code on the TV to send text securely."
        private const val MAX_QUEUED = 24

        private val KEYS = buildMap {
            put("up", KeyEvent.KEYCODE_DPAD_UP); put("down", KeyEvent.KEYCODE_DPAD_DOWN)
            put("left", KeyEvent.KEYCODE_DPAD_LEFT); put("right", KeyEvent.KEYCODE_DPAD_RIGHT)
            put("ok", KeyEvent.KEYCODE_DPAD_CENTER); put("back", KeyEvent.KEYCODE_BACK)
            put("menu", KeyEvent.KEYCODE_MENU); put("info", KeyEvent.KEYCODE_INFO)
            put("playpause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            put("chup", KeyEvent.KEYCODE_CHANNEL_UP); put("chdown", KeyEvent.KEYCODE_CHANNEL_DOWN)
            put("rew", KeyEvent.KEYCODE_MEDIA_REWIND); put("ff", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            put("del", KeyEvent.KEYCODE_DEL)
            for (d in 0..9) put("$d", KeyEvent.KEYCODE_0 + d)
        }
    }
}
