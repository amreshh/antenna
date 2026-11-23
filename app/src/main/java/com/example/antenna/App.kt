package com.example.antenna

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.example.antenna.data.SubscriptionStore
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.net.OkHttpDownloader
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class App : Application(), SingletonImageLoader.Factory {

    companion object {
        /** One shared HTTP client for extraction, playback and SponsorBlock. */
        lateinit var http: OkHttpClient
            private set
    }

    override fun onCreate() {
        super.onCreate()
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        NewPipe.init(OkHttpDownloader(http))
        // Read the watch history and subscriptions while the first screen is being set up,
        // instead of on the main thread when a screen first needs them.
        thread(name = "load-stores") {
            WatchHistoryStore.get(this)
            SubscriptionStore.get(this)
        }
    }

    /** Coil's image loader: thumbnails and avatars come through the shared [http] client too. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .build()
}
