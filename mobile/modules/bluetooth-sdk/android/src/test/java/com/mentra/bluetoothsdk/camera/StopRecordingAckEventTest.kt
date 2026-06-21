package com.mentra.bluetoothsdk.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StopRecordingAckEventTest {
    @Test
    fun `parses requestId status and timestamp`() {
        val event =
            StopRecordingAckEvent(
                values =
                    mapOf(
                        "requestId" to "video-1",
                        "status" to "accepted",
                        "timestamp" to 1_708_963_201_234L,
                    ),
            )

        assertEquals("video-1", event.requestId)
        assertEquals("accepted", event.status)
        assertEquals(1_708_963_201_234L, event.timestamp)
    }

    @Test
    fun `missing fields fall back to empty strings and current time`() {
        val event = StopRecordingAckEvent(values = mapOf("requestId" to "video-2"))

        assertEquals("video-2", event.requestId)
        assertEquals("", event.status)
        // No timestamp on the wire → defaults to now.
        assertTrue(event.timestamp > 0L)
    }
}
