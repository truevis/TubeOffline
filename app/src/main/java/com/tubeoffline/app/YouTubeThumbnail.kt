package com.tubeoffline.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compact thumbnail: prefers the on-device cache so library rows work with no network. */
@Composable
fun YouTubeThumbnail(videoId: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<Bitmap?>(initialValue = null, videoId) {
        value = videoId?.let { id ->
            withContext(Dispatchers.IO) {
                ThumbnailCache.loadBitmap(context, id)
            }
        }
    }
    Box(
        modifier = modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
