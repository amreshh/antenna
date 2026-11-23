package com.example.antenna

import java.io.File
import okhttp3.Response
import okhttp3.Interceptor
import okhttp3.Cache
import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.example.antenna.data.SubscriptionStore
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.data.WatchLaterStore
import com.example.antenna.net.OkHttpDownloader
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class App : Application(), SingletonImageLoader.Factory {

    companion object {
        private const val HTTP_CACHE_BYTES = 32L * 1024 * 1024

        /** One shared HTTP client for extraction, playback and SponsorBlock. */
        lateinit var http: OkHttpClient
            private set
    }

    override fun onCreate() {
        super.onCreate()
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // What YouTube says may be kept is kept between app starts: mostly its player
            // script (about 1 MB, which NewPipe needs after every start, and which may be kept
            // for a year: it's replaced, under a new address, when YouTube updates it).
            .cache(Cache(File(cacheDir, "http"), HTTP_CACHE_BYTES))
            .addNetworkInterceptor(FeedsNotCached)
            .build()
        NewPipe.init(OkHttpDownloader(http))
        // Read the watch history and subscriptions while the first screen is being set up,
        // instead of on the main thread when a screen first needs them.
        thread(name = "load-stores") {
            WatchHistoryStore.get(this)
            SubscriptionStore.get(this)
            WatchLaterStore.get(this)
        }
    }

    /**
     * Coil's image loader: thumbnails and avatars come through the shared [http] client's
     * connections, but not its cache: Coil keeps images in a cache of its own.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http.newBuilder().cache(null).build() })) }
            .build()

    /**
     * Channels' RSS feeds aren't kept in the HTTP cache (YouTube allows 15 minutes): pulling
     * the feed to refresh it should mean checking now. The feed has a cache of its own, which
     * shows it straight away when the tab opens.
     */
    private object FeedsNotCached : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            return if (chain.request().url.encodedPath == "/feeds/videos.xml") {
                response.newBuilder().header("Cache-Control", "no-store").build()
            } else {
                response
            }
        }
    }
}
