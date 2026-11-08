package com.edgestream.tv.remote

import com.edgestream.tv.ui.Screen

/** What the phone can ask the app to do beyond key presses (those go straight to MainActivity). */
interface PhoneCommandTarget {
    fun playFromPhone(channelKey: String)
    fun openSearch(query: String)
    fun openScreen(screen: Screen)
    /** Tunes as if the number were keyed on the remote (country LCNs, e.g. 101 = BBC One HD). */
    fun tuneNumber(number: Int)
    /** Plays the best channel match for a spoken name, or searches for it when nothing matches. */
    fun playNamed(name: String)
}

class PhoneCommands(private val target: PhoneCommandTarget) {
    fun handle(c: PhoneLink.Command) {
        when (c) {
            is PhoneLink.Command.Play -> target.playFromPhone(c.channelKey)
            // The guide is never a link destination: it only makes sense as an overlay on live TV.
            is PhoneLink.Command.Open ->
                Screen.entries.firstOrNull { it.name == c.screen && it != Screen.GUIDE }?.let(target::openScreen)
            is PhoneLink.Command.Voice -> when (val v = c.command) {
                is VoiceCommand.Tune -> target.tuneNumber(v.number)
                is VoiceCommand.Watch -> target.playNamed(v.channel)
                is VoiceCommand.Open -> target.openScreen(v.screen)
                is VoiceCommand.Search -> target.openSearch(v.query)
                // Questions never arrive here: the phone server answers them through PhoneLink.answerer.
                is VoiceCommand.WhatsOn, VoiceCommand.Games, is VoiceCommand.Team -> Unit
            }
            is PhoneLink.Command.Key, is PhoneLink.Command.SetField -> Unit
        }
    }
}
