package io.executionbridge.android

import org.json.JSONObject

/** User-mediated single read-only challenge, not an authenticated remote channel. */
internal data class BatteryChallenge(val id:String,val issued:Long,val expires:Long,val nonce:String) {
    fun fresh(now:Long):Boolean=now in issued..expires
    fun response(battery:Int, now:Long, version:String):JSONObject {
        require(fresh(now) && battery in 0..100)
        return JSONObject().put("schema","EXECUTION_BRIDGE_BATTERY_RESPONSE_V1")
            .put("request_id",id).put("nonce",nonce)
            .put("issued_at_ms",issued).put("expires_at_ms",expires)
            .put("captured_at_ms",now).put("battery_percent",battery)
            .put("bridge",version).put("source","OWNER_APPROVED_ANDROID")
            .put("transport","MANUAL_CHAT_FILE_OR_TEXT")
            .put("device_attestation","NOT_VERIFIED")
    }
    companion object {
        const val MAX_TTL=1800000L
        fun parse(raw:String,now:Long):BatteryChallenge {
            require(raw.length<=180)
            val parts=raw.trim().split(":")
            require(parts.size==5 && parts[0]=="EB1")
            val id=Endpoint.id(parts[1])
            require(parts[2].matches(Regex("[0-9]{13}")) && parts[3].matches(Regex("[0-9]{13}")))
            val issued=parts[2].toLong();val expires=parts[3].toLong()
            require(expires>issued && expires-issued<=MAX_TTL)
            require(parts[4].matches(Regex("[0-9a-f]{32}")))
            return BatteryChallenge(id,issued,expires,parts[4]).also {require(it.fresh(now))}
        }
    }
}
