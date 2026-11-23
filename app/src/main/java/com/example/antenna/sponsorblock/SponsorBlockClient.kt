package com.example.antenna.sponsorblock

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.IOException
import java.security.MessageDigest

data class Segment(
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
)

enum class SponsorCategory(
    val key: String,
    val label: String,
    /** What's skipped, in a word or two: for lists without room for [label] (the Analytics tab). */
    val shortLabel: String,
) {
    SPONSOR("sponsor", "Sponsor", "Sponsors"),
    SELF_PROMO("selfpromo", "Unpaid / self promotion", "Self promotion"),
    INTERACTION("interaction", "Interaction reminder (like & subscribe)", "Interaction reminders"),
    INTRO("intro", "Intermission / intro animation", "Intros"),
    OUTRO("outro", "Endcards / credits", "Endcards"),
    PREVIEW("preview", "Preview / recap", "Previews"),
    FILLER("filler", "Filler tangent", "Filler"),
    MUSIC_OFFTOPIC("music_offtopic", "Non-music section (music videos)", "Non-music");

    companion object {
        val DEFAULT_ON = setOf(SPONSOR.key, SELF_PROMO.key, INTERACTION.key)
        fun labelFor(key: String) = of(key)?.label ?: key
        fun shortLabelFor(key: String) = of(key)?.shortLabel ?: key
        private fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/**
 * Client for the public SponsorBlock API (https://wiki.sponsor.ajay.app/w/API_Docs).
 *
 * Uses the privacy-preserving hash-prefix endpoint: we only send the first 4 hex
 * chars of sha256(videoId), get back segments for every video sharing that prefix,
 * and pick ours out locally. The server never learns exactly what you watch.
 */
class SponsorBlockClient(private val http: OkHttpClient) {

    suspend fun getSegments(videoId: String, categories: Set<String>): List<Segment> =
        withContext(Dispatchers.IO) {
            if (categories.isEmpty()) return@withContext emptyList()
            val prefix = sha256Hex(videoId).take(4)
            val url = "$BASE/api/skipSegments/$prefix".toHttpUrl().newBuilder()
                .addQueryParameter("categories", JSONArray(categories.toList()).toString())
                .addQueryParameter("actionTypes", """["skip"]""")
                .build()

            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    // 404 just means "no segments for this prefix"
                    if (!resp.isSuccessful) return@use emptyList<Segment>()
                    val body = resp.body.string()
                    parse(body, videoId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "SponsorBlock lookup failed", e)
                emptyList()
            }
        }

    /** Tells SponsorBlock a segment was skipped (feeds the public "time saved" stats). */
    fun reportSkip(uuid: String) {
        val url = "$BASE/api/viewedVideoSponsorTime".toHttpUrl().newBuilder()
            .addQueryParameter("UUID", uuid).build()
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: okhttp3.Response) = response.close()
        })
    }

    private fun parse(json: String, videoId: String): List<Segment> {
        val result = mutableListOf<Segment>()
        val videos = JSONArray(json)
        for (i in 0 until videos.length()) {
            val video = videos.getJSONObject(i)
            if (video.optString("videoID") != videoId) continue
            val segments = video.getJSONArray("segments")
            for (j in 0 until segments.length()) {
                val s = segments.getJSONObject(j)
                val range = s.getJSONArray("segment")
                result += Segment(
                    uuid = s.getString("UUID"),
                    category = s.getString("category"),
                    startMs = (range.getDouble(0) * 1000).toLong(),
                    endMs = (range.getDouble(1) * 1000).toLong(),
                )
            }
        }
        return result.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BASE = "https://sponsor.ajay.app"
        const val TAG = "SponsorBlock"
    }
}
