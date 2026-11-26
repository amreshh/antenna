package com.antenna.sponsorblock

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

/** Looking up a video's segments (SponsorBlockClient), from a stand-in for SponsorBlock's server. */
class SponsorBlockClientTest {

    /** The stand-in server's answer to a request: an HTTP status and body. */
    private class Answer(val code: Int, val body: String = "")

    private val requests = mutableListOf<Request>()

    /**
     * A client whose server gives [answers] in turn (null: the connection drops), without
     * waiting between tries: 4 tries in all.
     */
    private fun client(vararg answers: Answer?): SponsorBlockClient {
        val queue = ArrayDeque(answers.toList())
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                val answer = queue.removeFirst() ?: throw IOException("connection dropped")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(answer.code)
                    .message("")
                    .body(answer.body.toResponseBody())
                    .build()
            }
            .build()
        return SponsorBlockClient(http, retryDelaysMs = listOf(0, 0, 0))
    }

    private fun SponsorBlockClient.segments(categories: Set<String> = setOf("sponsor", "selfpromo")) =
        runBlocking { getSegments(VIDEO, categories) }

    @Test
    fun `the video's segments, from those of every video sharing its prefix`() {
        val found = client(Answer(200, FOUND)).segments()
        assertEquals(
            listOf(Segment("a", "sponsor", 12_250, 45_000), Segment("b", "selfpromo", 300_500, 330_000)),
            found,
        )
    }

    @Test
    fun `only the first 4 characters of the video id's hash are sent`() {
        client(Answer(200, FOUND)).segments()
        val url = requests.single().url
        val hash = MessageDigest.getInstance("SHA-256").digest(VIDEO.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("/api/skipSegments/${hash.take(4)}", url.encodedPath)
        assertFalse(url.toString().contains(VIDEO))
        assertEquals("""["sponsor","selfpromo"]""", url.queryParameter("categories"))
    }

    @Test
    fun `a 404 means no segments, and isn't tried again`() {
        assertEquals(emptyList<Segment>(), client(Answer(404)).segments())
        assertEquals(1, requests.size)
    }

    @Test
    fun `a failed lookup is tried again`() {
        val found = client(null, Answer(503), Answer(429), Answer(200, FOUND)).segments()
        assertEquals(listOf("a", "b"), found.map { it.uuid })
        assertEquals(4, requests.size)
    }

    @Test
    fun `an answer that isn't SponsorBlock's counts as a failure`() {
        val found = client(Answer(200, "<html>Log in to use this Wi-Fi</html>"), Answer(200, FOUND)).segments()
        assertEquals(listOf("a", "b"), found.map { it.uuid })
    }

    @Test
    fun `when every try fails it says so, rather than that there are no segments`() {
        val client = client(Answer(503), null, Answer(500), Answer(503))
        val failure = assertThrows(IOException::class.java) { client.segments() }
        assertTrue(failure.message.orEmpty().contains("503"))
        assertEquals(4, requests.size)
    }

    @Test
    fun `no categories to skip, nothing to look up`() {
        assertEquals(emptyList<Segment>(), client().segments(categories = emptySet()))
        assertTrue(requests.isEmpty())
    }

    private companion object {
        const val VIDEO = "dQw4w9WgXcQ"

        /**
         * SponsorBlock's answer for the video's hash prefix: its segments (out of order, and one
         * of no length), and another video's.
         */
        val FOUND = """
            [
              {"videoID": "$VIDEO", "segments": [
                {"category": "selfpromo", "actionType": "skip", "segment": [300.5, 330.0], "UUID": "b"},
                {"category": "sponsor", "actionType": "skip", "segment": [12.25, 45.0], "UUID": "a"},
                {"category": "sponsor", "actionType": "skip", "segment": [50.0, 50.0], "UUID": "empty"}
              ]},
              {"videoID": "jNQXAC9IVRw", "segments": [
                {"category": "sponsor", "actionType": "skip", "segment": [1.0, 2.0], "UUID": "c"}
              ]}
            ]
        """.trimIndent()
    }
}
