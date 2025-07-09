package com.thumb.test

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var exoPlayer: ExoPlayer

    private lateinit var fpsText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var resolutionText: TextView
    private lateinit var droppedFramesText: TextView
    private lateinit var recyclerView: RecyclerView

    private var lastRenderedFrames = 0
    private var lastDroppedFrames = 0
    private var lastTimeMs = 0L

    private val channels = listOf(
        Channel(
            url = "https://3abn.bozztv.com/3abn2/3abn_live/smil:3abn_live.smil/playlist.m3u8",
            name = "3ABN English",
            imageResId = R.drawable.channel
        ),
        Channel(
            url = "http://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv/stitch/hls/channel/65a67dd13af63d0008257f17/master.m3u8?appName=web&appVersion=unknown&clientTime=0&deviceDNT=0&deviceId=1b1c5240-4b81-11ef-a8ac-e146e4e7be02&deviceMake=Chrome&deviceModel=web&deviceType=web&deviceVersion=unknown&includeExtendedEvents=false&serverSideAds=false&sid=6e62cae5-9404-4e52-8b20-c5fc2b453e9d",
            name = "90210",
            imageResId = R.drawable.channel
        ),
        Channel(
            url = "https://content.uplynk.com/channel/3324f2467c414329b3b0cc5cd987b6be.m3u8",
            name = "ABC News",
            imageResId = R.drawable.channel
        ),

        ////////////////////
        Channel(
            url = "https://mediaserver.abnvideos.com/streams/abnafrica.m3u8",
            name = "ABN Africa",
            imageResId = R.drawable.channel
        ),
        Channel(
            url = "https://mn-nl.mncdn.com/kanal24/smil:kanal24.smil/playlist.m3u8",
            name = "24 TV",
            imageResId = R.drawable.channel
        ),
        Channel(
            url = "https://turkmedya-live.ercdn.net/tv360/tv360.m3u8",
            name = "360",
            imageResId = R.drawable.channel
        ),
        /////////////////////////////////
        Channel(
            url = "https://cdn3.wowza.com/5/OE5HREpIcEkySlNT/alhayat-live/ngrp:livestream_all/playlist.m3u8",
            name = "Al Hayat TV",
            imageResId = R.drawable.channel
        ),
        Channel(
            url = "https://streams2.sofast.tv/sofastplayout/33c31ac4-51fa-46ae-afd0-0d1fe5e60a80_0_HLS/master.m3u8",
            name = "Fashion TV 4K",
            imageResId = R.drawable.channel
        ),
        Channel(
            url = "https://dd782ed59e2a4e86aabf6fc508674b59.msvdn.net/live/S97044836/tbbP8T1ZRPBL/playlist_video.m3u8",
            name = "RAI 4K",
            imageResId = R.drawable.channel
        ),
        Channel(
            url = "https://streams2.sofast.tv/sofastplayout/33c31ac4-51fa-46ae-afd0-0d1fe5e60a80_0_HLS/master.m3u8",
            name = "4K Travel TV",
            imageResId = R.drawable.channel
        ),
        Channel(
            url = "https://bloomberg-bloombergtv-1-it.samsung.wurl.tv/manifest/playlist.m3u8",
            name = "Bloomberg TV+ UHD",
            imageResId = R.drawable.channel
        ),
        Channel(
            url = "https://ncdn.telewebion.com/faratar/live/playlist.m3u8",
            name = "IRIB UHD",
            imageResId = R.drawable.channel
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        playerView = findViewById(R.id.playerView)
        fpsText = findViewById(R.id.fpsText)
        bitrateText = findViewById(R.id.bitrateText)
        resolutionText = findViewById(R.id.resolutionText)
        droppedFramesText = findViewById(R.id.droppedFramesText)
        recyclerView = findViewById(R.id.channelList)

        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = ChannelAdapter(channels) { playChannel(it.url) }

        playChannel(channels[0].url)
    }

    private fun playChannel(url: String) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val mediaItem = MediaItem.fromUri(url)
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.11 LibVLC/3.0.11")
            .setAllowCrossProtocolRedirects(true)

        val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.play()

        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoSizeChanged(
                eventTime: AnalyticsListener.EventTime,
                width: Int,
                height: Int,
                unappliedRotationDegrees: Int,
                pixelWidthHeightRatio: Float
            ) {
                resolutionText.text = "Resolution: ${width}x$height"
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                startStatsTracker()
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                mediaLoadData: MediaLoadData
            ) {
                val bitrate = mediaLoadData.trackFormat?.bitrate ?: Format.NO_VALUE
                if (bitrate != Format.NO_VALUE) {
                    bitrateText.text = "Bitrate: ${bitrate / 1000} kbps"
                } else {
                    bitrateText.text = "Bitrate: N/A"
                }
            }
        })
    }

    private fun startStatsTracker() {
        val handler = Handler(Looper.getMainLooper())

        lastTimeMs = System.currentTimeMillis()
        lastRenderedFrames = exoPlayer.videoDecoderCounters?.renderedOutputBufferCount ?: 0

        handler.post(object : Runnable {
            override fun run() {
                val counters = exoPlayer.videoDecoderCounters
                counters?.ensureUpdated()

                val now = System.currentTimeMillis()
                val elapsed = (now - lastTimeMs) / 1000f

                val rendered = counters?.renderedOutputBufferCount ?: 0
                val dropped = counters?.droppedBufferCount ?: 0

                if (elapsed > 0f) {
                    val fps = (rendered - lastRenderedFrames) / elapsed
                    if (fps in 0.0..240.0) {
                        fpsText.text = "FPS: ${String.format("%.1f", fps)}"
                    }
                    droppedFramesText.text = "Dropped Frames: $dropped"
                }

                lastTimeMs = now
                lastRenderedFrames = rendered
                lastDroppedFrames = dropped

                handler.postDelayed(this, 1000)
            }
        })
    }

    override fun onStop() {
        super.onStop()
        exoPlayer.release()
    }
}