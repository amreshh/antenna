package com.antenna.sponsorblock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
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
class SponsorBlockClient(
    private val http: OkHttpClient,
    /** How long to wait before each new try of a lookup that failed. */
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
) {

    /**
     * The segments of [videoId] in [categories]: empty when SponsorBlock has none.
     *
     * A lookup that fails (no connection, or SponsorBlock answers with an error or with
     * something unreadable) is tried again a few times over about two minutes, so a network
     * blip as a video opens doesn't mean nothing is skipped in it. When every try fails, this
     * throws the last one's IOException.
     */
    suspend fun getSegments(videoId: String, categories: Set<String>): List<Segment> {
        if (categories.isEmpty()) return emptyList()
        val prefix = sha256Hex(videoId).take(4)
        val url = "$BASE/api/skipSegments/$prefix".toHttpUrl().newBuilder()
            .addQueryParameter("categories", JSONArray(categories.toList()).toString())
            .addQueryParameter("actionTypes", """["skip"]""")
            .build()
        val request = Request.Builder().url(url).build()
        for (wait in retryDelaysMs) {
            try {
                return lookUp(request, videoId)
            } catch (_: IOException) {
                delay(wait)
            }
        }
        return lookUp(request, videoId)
    }

    /**
     * One try. Throws IOException when SponsorBlock can't be reached, answers with an error, or
     * sends something that isn't its list of segments (a Wi-Fi login page, say).
     */
    private suspend fun lookUp(request: Request, videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            when {
                resp.code == 404 -> emptyList() // no segments for any video with this prefix
                !resp.isSuccessful -> throw IOException("SponsorBlock answered HTTP ${resp.code}")
                else -> try {
                    parse(resp.body.string(), videoId)
                } catch (e: JSONException) {
                    throw IOException("SponsorBlock's answer couldn't be read", e)
                }
            }
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

        /** 5 tries over about two minutes: a blip is over in seconds, a busy server takes longer. */
        val RETRY_DELAYS_MS = listOf(2_000L, 10_000L, 30_000L, 60_000L)
    }
}
