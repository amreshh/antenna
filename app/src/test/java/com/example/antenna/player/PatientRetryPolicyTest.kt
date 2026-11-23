package com.example.antenna.player

import androidx.media3.common.C
import com.example.antenna.player.PatientRetryPolicy.Companion.retryDelayMs
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the player tries a failed request for a video's data again, and how long it waits. */
class PatientRetryPolicyTest {

    private val always = { true }

    @Test
    fun `busy servers are retried for about 20 seconds, waiting longer each time`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (1..5).map { retryDelayMs(503, it, always) })
        assertEquals(1_000L, retryDelayMs(500, 1, always))
        assertEquals(1_000L, retryDelayMs(502, 1, always))
        assertEquals(5, PatientRetryPolicy().getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `refused or expired links are not retried, fresh ones are fetched instead`() {
        listOf(403, 404, 410, 416).forEach { assertEquals("HTTP $it", C.TIME_UNSET, retryDelayMs(it, 1, always)) }
    }

    @Test
    fun `dropped connections are retried the same way`() {
        assertEquals(1_000L, retryDelayMs(null, 1, always))
        assertEquals(4_000L, retryDelayMs(null, 3, always))
    }

    @Test
    fun `errors Media3 considers final stay final`() {
        assertEquals(C.TIME_UNSET, retryDelayMs(null, 1) { false })
    }
}
