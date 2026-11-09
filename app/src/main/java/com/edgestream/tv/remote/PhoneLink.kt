package com.edgestream.tv.remote

import android.content.Context
import com.edgestream.tv.data.Secrets
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Shared state between the phone companion web server and the TV UI.
 * The server only posts [Command]s; the activity and view model decide what they mean.
 */
object PhoneLink {
    const val PORT = 8765

    /** Answers spoken questions from the TV's live data; set by the app while its screen exists. */
    @Volatile var answerer: (suspend (VoiceCommand) -> String)? = null

    /** The TV's own lineup (language-filtered, deduped, cleaned names); set by the app while its screen exists. */
    @Volatile var lineup: StateFlow<List<com.edgestream.tv.data.db.ChannelEntity>>? = null

    sealed interface Command {
        /** An Android key code to deliver to the focused UI, as if pressed on the remote. */
        data class Key(val code: Int) : Command
        /** Replaces the whole value of the TV text box a phone is filling (see [field]). */
        data class SetField(val text: String) : Command
        data class Play(val channelKey: String) : Command
        data class Open(val screen: String) : Command
        /** A spoken or typed command from the phone, already understood. */
        data class Voice(val command: VoiceCommand) : Command
    }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 64)
    val commands: SharedFlow<Command> = _commands
    fun post(c: Command) { _commands.tryEmit(c) }

    /** When a phone last talked to us; drives the "phone connected" indicator. */
    private val _lastSeen = MutableStateFlow(0L)
    val lastSeen: StateFlow<Long> = _lastSeen
    fun touch() { _lastSeen.value = System.currentTimeMillis() }

    /** The focused TV text box, offered to a paired phone. A secret box's value never leaves the TV. */
    data class Field(val label: String, val value: String, val secret: Boolean)

    private val _field = MutableStateFlow<Field?>(null)
    val field: StateFlow<Field?> = _field
    private var fieldOwner: Any? = null

    /** Focus moving A -> B can run B's offer before A's withdraw; the owner check keeps B's. */
    @Synchronized fun offerField(owner: Any, f: Field) { fieldOwner = owner; _field.value = f }
    @Synchronized fun withdrawField(owner: Any) { if (fieldOwner === owner) { fieldOwner = null; _field.value = null } }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    internal fun setRunning(on: Boolean) { _running.value = on }

    /**
     * Pairing code, kept across launches so a bookmarked phone page keeps working.
     * Six digits from a secure random source; anyone on the LAN without it gets 401.
     */
    @Volatile private var pairing: PairingCode? = null

    private fun pairing(context: Context): PairingCode = pairing ?: synchronized(this) {
        pairing ?: run {
            val prefs = context.applicationContext.getSharedPreferences("edgestream", Context.MODE_PRIVATE)
            PairingCode(
                load = {
                    prefs.getString("companionToken", null)?.let { stored ->
                        Secrets.openOrBlank(stored).takeIf { it.isNotBlank() }?.also { value ->
                            // Codes saved before encryption existed get sealed in place, once.
                            if (!Secrets.isSealed(stored)) prefs.edit().putString("companionToken", Secrets.seal(value)).apply()
                        }
                    }
                },
                store = { prefs.edit().putString("companionToken", Secrets.seal(it)).apply() },
                clear = { prefs.edit().remove("companionToken").apply() },
                generate = { (SecureRandom().nextInt(900_000) + 100_000).toString() },
            ).also { pairing = it }
        }
    }

    @Volatile private var link: PairingCode? = null

    /** The secure-link key, stored sealed like the code; it only ever leaves the TV inside the QR code. */
    private fun link(context: Context): PairingCode = link ?: synchronized(this) {
        link ?: run {
            val prefs = context.applicationContext.getSharedPreferences("edgestream", Context.MODE_PRIVATE)
            PairingCode(
                load = { prefs.getString("companionKey", null)?.let(Secrets::openOrBlank)?.takeIf { LinkCipher.keyFromText(it) != null } },
                store = { prefs.edit().putString("companionKey", Secrets.seal(it)).apply() },
                clear = { prefs.edit().remove("companionKey").apply() },
                generate = { LinkCipher.keyToText(LinkCipher.newKey()) },
            ).also { link = it }
        }
    }

    fun linkKey(context: Context): ByteArray = checkNotNull(LinkCipher.keyFromText(link(context).get()))

    /** The pairing code, from memory after the first read. */
    fun token(context: Context): String = pairing(context).get()

    /** Pairing-code guessing limits shared by every server worker. */
    val limiter = PairingLimiter()

    /** Replaces the pairing code; the old one stops working at once and every lockout clears. */
    fun resetToken(context: Context) {
        pairing(context).reset()
        link(context).reset()
        limiter.reset()
    }

    /** The TV's address on the home network (Ethernet or Wi-Fi), preferring private IPv4. */
    fun lanAddress(context: Context? = null): String? = runCatching {
        // The active network is the one the phone can actually reach; interface order is a guess.
        val active = context?.getSystemService(android.net.ConnectivityManager::class.java)?.let { cm ->
            cm.activeNetwork?.let { cm.getLinkProperties(it) }?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
                ?.address?.hostAddress
        }
        if (!active.isNullOrBlank()) return active
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** The QR target. Everything after # stays on the phone: browsers never send the fragment. */
    fun url(context: Context): String? = lanAddress(context)?.let {
        "http://$it:$PORT/#t=${token(context)}&k=${LinkCipher.keyToText(linkKey(context))}"
    }
}
