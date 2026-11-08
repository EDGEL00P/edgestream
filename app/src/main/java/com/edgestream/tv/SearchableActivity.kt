package com.edgestream.tv

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The TV's global voice/typed search lands here, then forwards the query into the app's own
 * Search screen through the edgestream://search deep link.
 */
class SearchableActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val deep = Uri.Builder().scheme(com.edgestream.tv.launcher.Links.SCHEME)
            .authority("search")
            .appendQueryParameter("q", query)
            .build()
        startActivity(
            Intent(Intent.ACTION_VIEW, deep).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
