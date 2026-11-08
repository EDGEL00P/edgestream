package com.edgestream.tv.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.edgestream.tv.R
import com.edgestream.tv.ui.Edge

/**
 * The real Edge mark (slanted stripe + E) from the Edge launcher, tinted by the active
 * palette. No invented or generated artwork.
 */
@Composable
fun EdgeMark(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_edge_logo),
        contentDescription = "EdgeStream",
        modifier = modifier,
        tint = Edge.Accent,
    )
}
