package com.example.antenna

import android.app.Application
import com.example.antenna.net.OkHttpDownloader
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.TimeUnit

class App : Application() {

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
    }
}
