package com.edgestream.tv

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The TV's global/remote voice search. Spoken queries are parsed as commands (tune, open, watch,
 * scores/teams) so the Shield remote mic works as a voice remote; anything else opens Search.
 */
class SearchableActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val deep = when (val cmd = com.edgestream.tv.remote.VoiceCommand.parse(query)) {
            is com.edgestream.tv.remote.VoiceCommand.Tune ->
                commandUri().appendQueryParameter("action", "tune").appendQueryParameter("n", cmd.number.toString()).build()
            is com.edgestream.tv.remote.VoiceCommand.Watch ->
                commandUri().appendQueryParameter("action", "watch").appendQueryParameter("name", cmd.channel).build()
            is com.edgestream.tv.remote.VoiceCommand.Open ->
                commandUri().appendQueryParameter("action", "open").appendQueryParameter("screen", cmd.screen.name).build()
            is com.edgestream.tv.remote.VoiceCommand.Games ->
                commandUri().appendQueryParameter("action", "games").build()
            is com.edgestream.tv.remote.VoiceCommand.Team ->
                commandUri().appendQueryParameter("action", "team").appendQueryParameter("name", cmd.team).build()
            is com.edgestream.tv.remote.VoiceCommand.WhatsOn ->
                commandUri().appendQueryParameter("action", "whatson").appendQueryParameter("channel", cmd.channel.orEmpty()).build()
            is com.edgestream.tv.remote.VoiceCommand.Search, null ->
                Uri.Builder().scheme(com.edgestream.tv.launcher.Links.SCHEME).authority("search").appendQueryParameter("q", query).build()
        }
        startActivity(
            Intent(Intent.ACTION_VIEW, deep).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    private fun commandUri(): Uri.Builder =
        Uri.Builder().scheme(com.edgestream.tv.launcher.Links.SCHEME).authority("command")
}
