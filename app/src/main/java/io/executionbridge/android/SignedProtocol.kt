package io.executionbridge.android

import org.json.JSONObject
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

internal object SignedProtocol {
    const val SCHEMA="EXECUTION_BRIDGE_SIGNED_RESPONSE_V2"
    const val TRANSPORT="OWNER_MEDIATED"
    val operations=setOf("GET_BATTERY_STATUS","GET_STORAGE_STATUS","GET_NETWORK_STATUS","GET_BRIDGE_VERSION")
    data class Request(val id:String,val fingerprint:String,val operation:String,val issued:Long,val expires:Long,val nonce:String)
    fun fingerprint(publicKey:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun parse(raw:String,now:Long):Request {
        require(raw.length<=320)
        val p=raw.trim().split(":")
        require(p.size==7 && p[0]=="EB2")
        val id=Endpoint.id(p[1])
        require(p[2].matches(Regex("[0-9a-f]{64}")) && p[3] in operations)
        require(p[4].matches(Regex("[0-9]{13}")) && p[5].matches(Regex("[0-9]{13}")))
        val issued=p[4].toLong();val expires=p[5].toLong()
        require(expires>issued && expires-issued<=1800000 && now in issued..expires)
        require(p[6].matches(Regex("[0-9a-f]{32}")))
        return Request(id,p[2],p[3],issued,expires,p[6])
    }
    fun canonical(q:Request,captured:Long,version:String,value:String):ByteArray {
        require(captured in q.issued..q.expires && version.matches(Regex("[A-Za-z0-9._-]{1,40}")))
        when(q.operation) {
            "GET_BATTERY_STATUS" -> require(value.matches(Regex("0|[1-9][0-9]{0,2}")) && value.toInt() in 0..100)
            "GET_STORAGE_STATUS" -> require(value.matches(Regex("0|[1-9][0-9]{0,15}")) && value.toLong()<=1125899906842624L)
            "GET_NETWORK_STATUS" -> require(value in setOf("VALIDATED","NOT_VALIDATED"))
            "GET_BRIDGE_VERSION" -> require(value==version)
            else -> throw IllegalArgumentException("unsupported operation")
        }
        return listOf(SCHEMA,q.id,q.fingerprint,q.operation,q.issued.toString(),q.expires.toString(),q.nonce,captured.toString(),version,TRANSPORT,value).joinToString("\n").toByteArray(Charsets.US_ASCII)
    }
    fun response(q:Request,captured:Long,version:String,value:String,key:PrivateKey):JSONObject {
        val sig=Signature.getInstance("SHA256withECDSA").apply {initSign(key);update(canonical(q,captured,version,value))}.sign()
        return JSONObject().put("schema",SCHEMA).put("request_id",q.id).put("key_id",q.fingerprint)
            .put("operation",q.operation).put("issued_at_ms",q.issued).put("expires_at_ms",q.expires)
            .put("nonce",q.nonce).put("captured_at_ms",captured).put("bridge_version",version)
            .put("transport",TRANSPORT).put("value",value).put("signature",Base64.getEncoder().encodeToString(sig))
    }
}
