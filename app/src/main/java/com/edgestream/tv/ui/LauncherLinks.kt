package com.edgestream.tv.ui

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/** The edgestream:// link the app was opened with, held until the view model handles it. */
object LauncherLinks {
    val pending = MutableStateFlow<Uri?>(null)
}
