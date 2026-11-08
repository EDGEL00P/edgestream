package com.edgestream.tv.remote

import android.content.Context
import com.edgestream.tv.data.Secrets
import com.edgestream.tv.data.db.EdgeDatabase
import com.edgestream.tv.data.db.FavoriteEntity
import com.edgestream.tv.data.db.ProgressEntity
import com.edgestream.tv.data.db.SourceEntity
import com.edgestream.tv.data.db.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Copies one TV's whole setup to another on the same network: providers (logins included),
 * settings, profiles, My List, favourites, followed teams, channel picks, calendar and progress.
 *
 * The receiving TV shows a 6-digit code. Both TVs agree a key by ECDH (P-256) and stretch it
 * with that code (HKDF-SHA256), so the payload (AES-GCM, [LinkCipher]) is unreadable to anyone
 * listening, and a wrong code simply fails to decrypt. Each TV keeps its own database key,
 * phone pairing and hardware tuning.
 */
object SetupTransfer {

    /** Preferences that belong to the device, never copied. */
    private val DEVICE_ONLY = setOf(
        "dbKey", "companionToken", "companionKey", "effects", "tunneling", "matchFps", "overscan",
        "lastGuideMaintenance", "focusMemory", "mediaDeviceId",
    )
    private val FILES = listOf("library.json", "calendar.json", "reminders.json")
    private const val PREFS = "edgestream"
    private const val INFO = "edgestream-setup-v1"

    // ---- key agreement ------------------------------------------------------------------------

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicText(pair: KeyPair): String = Base64.getEncoder().encodeToString(pair.public.encoded)

    /**
     * The shared 32-byte key: ECDH between [mine] and [theirs], stretched with the code and both
     * public keys (sorted, so both sides derive the same bytes).
     */
    fun deriveKey(mine: PrivateKey, minePublic: String, theirs: String, code: String): ByteArray {
        val other = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(theirs)))
        val shared = KeyAgreement.getInstance("ECDH").apply { init(mine); doPhase(other, true) }.generateSecret()
        val transcript = listOf(minePublic, theirs).sorted().joinToString("|")
        return hkdf(shared, salt = code.toByteArray(), info = "$INFO|$transcript".toByteArray())
    }

    /** RFC 5869 HKDF-SHA256, one 32-byte block. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        return hmac(prk, info + byteArrayOf(1))
    }

    /** A 6-digit code, never starting with 0 so it reads the same spoken or typed. */
    fun newCode(): String = (100_000 + java.security.SecureRandom().nextInt(900_000)).toString()

    // ---- export / import ----------------------------------------------------------------------

    /** Everything that makes up the setup, as plain JSON (sealed values opened for the trip). */
    suspend fun export(context: Context, db: EdgeDatabase): JSONObject {
        val prefs = JSONObject()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if (k in DEVICE_ONLY || v == null) return@forEach
            prefs.put(k, JSONObject().apply {
                when (v) {
                    is String -> if (Secrets.isSealed(v)) put("s", Secrets.openOrBlank(v)) else put("t", v)
                    is Boolean -> put("b", v)
                    is Int -> put("i", v)
                    is Long -> put("l", v)
                    is Float -> put("f", v.toDouble())
                    is Set<*> -> put("set", JSONArray(v.map { it.toString() }))
                }
            })
        }
        val files = JSONObject()
        FILES.forEach { name -> File(context.filesDir, name).takeIf { it.exists() }?.let { files.put(name, it.readText()) } }
        val sources = JSONArray(db.sources().all().map { s ->
            JSONObject().put("id", s.id).put("name", s.name).put("type", s.type.name).put("url", s.url)
                .put("username", Secrets.open(s.username)).put("password", Secrets.open(s.password)).put("epgUrl", s.epgUrl)
        })
        val favorites = JSONArray(db.user().allFavorites().map { JSONObject().put("key", it.key).put("addedAt", it.addedAt) })
        val progress = JSONArray(db.user().allProgress().map { p ->
            JSONObject().put("key", p.key).put("title", p.title).put("poster", p.poster).put("streamUrl", p.streamUrl)
                .put("positionMs", p.positionMs).put("durationMs", p.durationMs).put("updatedAt", p.updatedAt)
        })
        return JSONObject().put("v", 1).put("prefs", prefs).put("files", files).put("sources", sources)
            .put("favorites", favorites).put("progress", progress)
    }

    /**
     * Replaces this TV's setup with [setup]. Its own providers, channels and guide are cleared
     * (they reload from the copied providers after the restart that follows).
     */
    suspend fun import(context: Context, db: EdgeDatabase, setup: JSONObject) {
        require(setup.optInt("v") == 1) { "This setup comes from a different version of EdgeStream" }
        db.clearAllTables()
        setup.getJSONArray("sources").let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                db.sources().upsert(
                    SourceEntity(
                        id = o.getLong("id"), name = o.getString("name"), type = SourceType.valueOf(o.getString("type")),
                        url = o.getString("url"), username = Secrets.seal(o.optString("username")),
                        password = Secrets.seal(o.optString("password")), epgUrl = o.optString("epgUrl"),
                    ),
                )
            }
        }
        setup.getJSONArray("favorites").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { db.user().addFavorite(FavoriteEntity(it.getString("key"), it.getLong("addedAt"))) } }
        setup.getJSONArray("progress").let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { p ->
                db.user().saveProgress(
                    ProgressEntity(
                        p.getString("key"), p.getString("title"), p.optString("poster").ifBlank { null }, p.getString("streamUrl"),
                        p.getLong("positionMs"), p.getLong("durationMs"), p.getLong("updatedAt"),
                    ),
                )
            }
        }
        val files = setup.getJSONObject("files")
        FILES.forEach { name -> File(context.filesDir, name).let { f -> if (files.has(name)) f.writeText(files.getString(name)) else f.delete() } }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.keys.filter { it !in DEVICE_ONLY }.forEach(edit::remove)
        val incoming = setup.getJSONObject("prefs")
        incoming.keys().forEach { k ->
            if (k in DEVICE_ONLY) return@forEach
            val v = incoming.getJSONObject(k)
            when {
                v.has("s") -> edit.putString(k, Secrets.seal(v.getString("s")))
                v.has("t") -> edit.putString(k, v.getString("t"))
                v.has("b") -> edit.putBoolean(k, v.getBoolean("b"))
                v.has("i") -> edit.putInt(k, v.getInt("i"))
                v.has("l") -> edit.putLong(k, v.getLong("l"))
                v.has("f") -> edit.putFloat(k, v.getDouble("f").toFloat())
                v.has("set") -> edit.putStringSet(k, v.getJSONArray("set").let { a -> (0 until a.length()).map(a::getString).toSet() })
            }
        }
        // Written before the restart that reloads every store from it.
        edit.commit()
    }
}

/**
 * This TV waiting to receive a setup: the code it shows, its one-time key pair, and how many
 * wrong codes it has seen. Five wrong tries or five minutes end it.
 */
object SetupReceiver {
    sealed interface Status {
        data object Idle : Status
        data class Waiting(val code: String) : Status
        data object Importing : Status
        data object Done : Status
        data class Failed(val message: String) : Status
    }

    private class Session(val code: String, val pair: java.security.KeyPair, val expiresAt: Long, var wrong: Int = 0)

    private val _status = kotlinx.coroutines.flow.MutableStateFlow<Status>(Status.Idle)
    val status: kotlinx.coroutines.flow.StateFlow<Status> = _status
    @Volatile private var session: Session? = null

    fun start() {
        val s = Session(SetupTransfer.newCode(), SetupTransfer.newKeyPair(), System.currentTimeMillis() + 5 * 60_000L)
        session = s
        _status.value = Status.Waiting(s.code)
    }

    fun stop() { session = null; _status.value = Status.Idle }

    /** The public half for a sender, or null when this TV isn't waiting. */
    fun hello(): String? = live()?.let { SetupTransfer.publicText(it.pair) }

    /**
     * Opens a sender's sealed setup. Null means the code was wrong (counted) or nothing is
     * waiting; the plain JSON otherwise.
     */
    @Synchronized
    fun open(senderPublic: String, envelope: String): String? {
        val s = live() ?: return null
        val key = runCatching { SetupTransfer.deriveKey(s.pair.private, SetupTransfer.publicText(s.pair), senderPublic, s.code) }.getOrNull()
        val plain = key?.let { LinkCipher.open(it, envelope) }
        if (plain == null) {
            if (++s.wrong >= 5) { session = null; _status.value = Status.Failed("Too many wrong codes. Start again on this TV.") }
            return null
        }
        session = null
        _status.value = Status.Importing
        return plain
    }

    fun finished(error: String?) { _status.value = if (error == null) Status.Done else Status.Failed(error) }

    private fun live(): Session? = session?.takeIf { System.currentTimeMillis() < it.expiresAt }
        ?: run { if (session != null) { session = null; _status.value = Status.Failed("The code expired. Start again on this TV.") }; null }
}

/** Finding other EdgeStream TVs on the network and sending this TV's setup to one. */
object SetupSender {
    const val SERVICE_TYPE = "_edgestream._tcp."

    data class Peer(val name: String, val host: String, val port: Int)

    /**
     * Every other EdgeStream TV advertising itself (NSD), as they're found. Resolving is done one
     * service at a time: older Android allows only one resolve in flight.
     */
    fun discover(context: Context): kotlinx.coroutines.flow.Flow<List<Peer>> = kotlinx.coroutines.flow.callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        val own = PhoneLink.lanAddress(context)
        val found = java.util.concurrent.ConcurrentHashMap<String, Peer>()
        val queue = kotlinx.coroutines.channels.Channel<android.net.nsd.NsdServiceInfo>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) { queue.trySend(info) }
            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo) { found.remove(info.serviceName); trySend(found.values.sortedBy { it.name }) }
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { close(IllegalStateException("Network discovery isn't available ($code)")) }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        val resolver = launch {
            for (info in queue) {
                val resolved = kotlinx.coroutines.suspendCancellableCoroutine<android.net.nsd.NsdServiceInfo?> { cont ->
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : android.net.nsd.NsdManager.ResolveListener {
                        override fun onServiceResolved(s: android.net.nsd.NsdServiceInfo) { if (cont.isActive) cont.resume(s) {} }
                        override fun onResolveFailed(s: android.net.nsd.NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) {} }
                    })
                } ?: continue
                @Suppress("DEPRECATION")
                val host = resolved.host?.hostAddress ?: continue
                if (host == own) continue
                found[resolved.serviceName] = Peer(resolved.serviceName, host, resolved.port)
                trySend(found.values.sortedBy { it.name })
            }
        }
        nsd.discoverServices(SERVICE_TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { resolver.cancel(); runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** Sends the setup to [peer] sealed with [code]; null on success, else what went wrong. */
    suspend fun send(context: Context, repo: com.edgestream.tv.data.Repository, peer: Peer, code: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val base = "http://${peer.host}:${peer.port}/api/setup"
            val client = com.edgestream.tv.data.Http.client
            fun call(req: okhttp3.Request) = client.newCall(req).execute().use { r -> r.code to r.body.string() }
            val (helloCode, hello) = runCatching { call(okhttp3.Request.Builder().url("$base/hello").build()) }
                .getOrElse { return@withContext "Couldn't reach ${peer.name}" }
            if (helloCode == 404) return@withContext "${peer.name} isn't waiting. Choose Receive Setup on that TV first."
            val theirs = runCatching { JSONObject(hello).getString("pub") }.getOrNull() ?: return@withContext "${peer.name} answered oddly"
            val pair = SetupTransfer.newKeyPair()
            val mine = SetupTransfer.publicText(pair)
            val key = SetupTransfer.deriveKey(pair.private, mine, theirs, code.trim())
            val payload = repo.exportSetup(context).toString()
            val body = JSONObject().put("pub", mine).put("e", LinkCipher.seal(key, payload)).toString()
                .toRequestBody("application/json".toMediaType())
            val (status, reply) = runCatching { call(okhttp3.Request.Builder().url(base).post(body).build()) }
                .getOrElse { return@withContext "The connection to ${peer.name} dropped" }
            when (status) {
                200 -> null
                403 -> "Wrong code. Check the code on ${peer.name}."
                else -> runCatching { JSONObject(reply).optString("error") }.getOrNull()?.ifBlank { null } ?: "${peer.name} couldn't apply the setup"
            }
        }
}
