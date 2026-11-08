package com.edgestream.tv.ui

import com.edgestream.tv.data.ChannelNumbers
import com.edgestream.tv.data.db.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the channel-number OSD shows: the digits typed so far and the channel they would tune. */
data class PadState(val digits: String = "", val match: ChannelEntity? = null)

/** Channel number → the best channel carrying it (country LCN first, then the provider's number). */
fun buildLcnIndex(channels: List<ChannelEntity>): Map<Int, ChannelEntity> {
    val index = HashMap<Int, ChannelEntity>()
    channels.groupBy { ChannelNumbers.lcn(it) }.forEach { (n, list) ->
        list.minByOrNull { ChannelNumbers.rank(it) }?.let { index[n] = it }
    }
    channels.forEach { index.putIfAbsent(it.number, it) }
    return index
}

/**
 * Universal-remote number entry: digits collect for [commitDelayMs] after the last press, then
 * [onTune] gets the number. Lives in the view model so it survives recreation and can be tested
 * without Compose; only the OSD composable collects [state].
 */
class ChannelPad(
    private val scope: CoroutineScope,
    private val commitDelayMs: Long = 1_800,
    private val onTune: (Int) -> Unit,
) {
    private val _state = MutableStateFlow(PadState())
    val state: StateFlow<PadState> = _state.asStateFlow()

    private var index: Map<Int, ChannelEntity> = emptyMap()
    private var autoCommit: Job? = null

    val active: Boolean get() = _state.value.digits.isNotEmpty()

    fun setIndex(index: Map<Int, ChannelEntity>) {
        this.index = index
        _state.update { it.copy(match = lookup(it.digits)) }
    }

    fun append(digit: Char) {
        val digits = (_state.value.digits + digit).take(4)
        _state.value = PadState(digits, lookup(digits))
        autoCommit?.cancel()
        autoCommit = scope.launch {
            delay(commitDelayMs)
            commit()
        }
    }

    fun backspace() {
        val digits = _state.value.digits.dropLast(1)
        _state.value = PadState(digits, lookup(digits))
        if (digits.isEmpty()) autoCommit?.cancel()
    }

    fun commit() {
        autoCommit?.cancel()
        val number = _state.value.digits.toIntOrNull()
        _state.value = PadState()
        number?.let(onTune)
    }

    private fun lookup(digits: String): ChannelEntity? = digits.toIntOrNull()?.let { index[it] }
}
