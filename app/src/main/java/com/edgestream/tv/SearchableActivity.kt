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
        val deep = com.edgestream.tv.launcher.Links.forCommand(com.edgestream.tv.remote.VoiceCommand.parse(query), query)
        startActivity(
            Intent(Intent.ACTION_VIEW, deep).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

}
