package com.tubeoffline.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** A compact, cached-by-Compose thumbnail for downloads that include a YouTube video ID. */
@Composable
fun YouTubeThumbnail(videoId: String?, modifier: Modifier = Modifier) {
    val image by produceState<Bitmap?>(initialValue = null, videoId) {
        value = videoId?.let { id ->
            withContext(Dispatchers.IO) {
                runCatching {
                    URL("https://i.ytimg.com/vi/$id/hqdefault.jpg").openStream().use(BitmapFactory::decodeStream)
                }.getOrNull()
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
