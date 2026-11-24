package io.executionbridge.android

import org.json.JSONObject

/** Only an explicitly null operation means the authenticated queue is empty. */
object GatewayPoll {
    fun pendingId(response: JSONObject): String? {
        require(response.has("operation") && response.length()==1)
        if(response.isNull("operation")) return null
        val op=response.getJSONObject("operation")
        require(op.getString("type")=="bridge.get_device_health")
        require(op.getString("status")=="PENDING")
        return Endpoint.id(op.getString("operation_id"))
    }
}
