package io.executionbridge.android
import org.junit.Test
import org.junit.Assert.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
class SignedProtocolTest {
    private val now=1795536000000L
    private val id="9e36fca7-854f-45e4-9b8d-b2a0b3fe7891"
    private val nonce="1234567890abcdef1234567890abcdef"
    private val pair=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
    private val fingerprint=SignedProtocol.fingerprint(pair.public.encoded)
    private fun token(op:String="GET_BATTERY_STATUS",end:Long=now+1000)="EB2:$id:$fingerprint:$op:${now-1000}:$end:$nonce"
    @Test fun signatureMatchesExactBytes() {
        val q=SignedProtocol.parse(token(),now)
        val r=SignedProtocol.response(q,now,"0.2.4-dev","44",pair.private)
        val sig=Base64.getDecoder().decode(r.getString("signature"))
        fun verify(value:String)=Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public);update(SignedProtocol.canonical(q,now,"0.2.4-dev",value));verify(sig)
        }
        assertTrue(verify("44"));assertFalse(verify("45"))
    }
    @Test fun unknownOperationRejected(){assertThrows(IllegalArgumentException::class.java){SignedProtocol.parse(token("SHELL"),now)}}
    @Test fun expiredRejected(){assertThrows(IllegalArgumentException::class.java){SignedProtocol.parse(token(end=now-1),now)}}
    @Test fun invalidBatteryRejected(){assertThrows(IllegalArgumentException::class.java){SignedProtocol.canonical(SignedProtocol.parse(token(),now),now,"0.2.4-dev","101")}}
    @Test fun otherOperations() {
        for((op,value) in mapOf("GET_STORAGE_STATUS" to "100","GET_NETWORK_STATUS" to "VALIDATED","GET_BRIDGE_VERSION" to "0.2.4-dev")) {
            assertTrue(SignedProtocol.canonical(SignedProtocol.parse(token(op),now),now,"0.2.4-dev",value).isNotEmpty())
        }
    }
}
