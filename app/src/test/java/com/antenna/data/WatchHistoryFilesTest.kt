package com.antenna.data

import com.antenna.data.TestData.id
import com.antenna.data.TestData.video
import com.antenna.data.TestData.watched
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch history's two files: the history, and the up next lists (WatchHistoryStore). */
class WatchHistoryFilesTest {

    private fun history() = mapOf(
        id(1) to watched(video(1), fraction = 0.5, related = listOf(video(10), video(11)))
            .copy(category = "Music", skipped = listOf(SkippedSegment("sponsor", 12_000, 45_000))),
        id(2) to watched(video(2), fraction = 1.0, daysAgo = 1.0),
    )

    @Test
    fun `the history reads back as it was written`() {
        val h = history()
        val read = decodeHistory(encodeHistory(h), encodeRelated(h))
        assertEquals(h, read.entries)
        assertFalse(read.relatedInline)
        assertEquals(0, read.skipped)
    }

    @Test
    fun `up next lists are kept out of the history file`() {
        val h = history()
        assertFalse(encodeHistory(h).contains("related"))
        assertFalse(encodeHistory(h).contains(video(10).url)) // an up next video
        val lists = JSONObject(encodeRelated(h))
        assertEquals(setOf(id(1)), lists.keys().asSequence().toSet()) // only the one that has a list
        assertEquals(2, lists.getJSONArray(id(1)).length())
    }

    @Test
    fun `an older history with up next lists inline still reads, and asks to be rewritten`() {
        val h = history()
        // The old shape: each entry with its "related" array.
        val old = JSONArray(encodeHistory(h)).also { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val entry = h.getValue(videoIdOf(o.getJSONObject("video").getString("url"))!!)
                o.put("related", JSONArray().apply { entry.related.forEach { put(it.toJson()) } })
            }
        }.toString()
        val read = decodeHistory(old, related = null)
        assertEquals(h, read.entries)
        assertTrue(read.relatedInline)
    }

    @Test
    fun `without the related file the history still loads, without up next lists`() {
        val read = decodeHistory(encodeHistory(history()), related = null)
        assertEquals(2, read.entries.size)
        assertTrue(read.entries.values.all { it.related.isEmpty() })
    }

    @Test
    fun `an unreadable entry is skipped, not the whole history`() {
        val arr = JSONArray(encodeHistory(history()))
        arr.put(JSONObject().put("pos", 5)) // no video
        arr.put(JSONObject().put("video", JSONObject().put("title", "no url")))
        val read = decodeHistory(arr.toString(), related = "not json")
        assertEquals(2, read.entries.size)
        assertEquals(2, read.skipped)
    }

    @Test
    fun `older entries have no skips, and a skip that can't be read is left out`() {
        val arr = JSONArray(encodeHistory(history()))
        val withSkips = (0 until arr.length()).map { arr.getJSONObject(it) }.single { it.has("skips") }
        assertEquals(1, (0 until arr.length()).count { arr.getJSONObject(it).has("skips") }) // none written for none
        withSkips.getJSONArray("skips").put("not a skip").put(JSONObject().put("start", 5))
        val read = decodeHistory(arr.toString(), related = null)
        assertEquals(listOf(SkippedSegment("sponsor", 12_000, 45_000)), read.entries.getValue(id(1)).skipped)
        assertEquals(emptyList<SkippedSegment>(), read.entries.getValue(id(2)).skipped)
    }

    @Test(expected = Exception::class)
    fun `a file that isn't a history at all is an error`() {
        decodeHistory("{\"truncated\": ", related = null)
    }
}
