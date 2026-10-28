package com.tubeoffline.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Receives "Share → TubeOffline" from YouTube / Chrome, queues the download and closes at once. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val candidates = buildList {
            add(intent.getStringExtra(Intent.EXTRA_TEXT))
            add(intent.getStringExtra(Intent.EXTRA_SUBJECT))
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) add(clip.getItemAt(i).text?.toString())
            }
        }
        val url = candidates.firstNotNullOfOrNull { YouTubeUrl.extract(it) }
        if (url == null) {
            Toast.makeText(this, "TubeOffline: no YouTube link found in what you shared", Toast.LENGTH_LONG).show()
            return
        }
        DownloadService.enqueue(this, url)
        val extra = if (Notifications.canPost(this)) "" else "\n(Open TubeOffline once to allow notifications)"
        Toast.makeText(this, "Added to the download queue.$extra", Toast.LENGTH_SHORT).show()
    }
}
