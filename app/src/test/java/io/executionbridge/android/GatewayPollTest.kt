package io.executionbridge.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayPollTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    @Test fun emptyQueueRequiresExplicitNull() {
        assertNull(GatewayPoll.pendingId(JSONObject().put("operation",JSONObject.NULL)))
    }
    @Test fun validPending() {
        val op=JSONObject().put("type","bridge.get_device_health").put("status","PENDING").put("operation_id",id)
        assertEquals(id,GatewayPoll.pendingId(JSONObject().put("operation",op)))
    }
    @Test fun malformedOrUnexpectedRepliesRejected() {
        val bad=listOf("{}", "{\"error\":\"unauthorized\"}", "{\"operation\":null,\"error\":\"bad\"}",
            "{\"operation\":false}", "{\"operation\":{}}",
            "{\"operation\":{\"type\":\"other\",\"status\":\"PENDING\",\"operation_id\":\"$id\"}}",
            "{\"operation\":{\"type\":\"bridge.get_device_health\",\"status\":\"SUCCEEDED\",\"operation_id\":\"$id\"}}",
            "{\"operation\":{\"type\":\"bridge.get_device_health\",\"status\":\"PENDING\",\"operation_id\":\"bad\"}}")
        for(raw in bad) {
            assertTrue("Accepted: $raw",runCatching { GatewayPoll.pendingId(JSONObject(raw)) }.isFailure)
        }
    }
}
