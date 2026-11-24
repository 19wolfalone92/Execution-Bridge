package io.executionbridge.android
import org.junit.Assert.*
import org.junit.Test
class BatteryChallengeTest {
    private val now=1795536000000L
    private val id="9e36fca7-854f-45e4-9b8d-b2a0b3fe7891"
    private val nonce="1234567890abcdef1234567890abcdef"
    private fun token(issued:Long=now-1000,expiry:Long=now+1000, n:String=nonce)="EB1:$id:$issued:$expiry:$n"
    @Test fun validAndCorrelated() {
        val c=BatteryChallenge.parse(token(),now)
        val r=c.response(54,now,"test")
        assertEquals(id,r.getString("request_id"))
        assertEquals(nonce,r.getString("nonce"))
        assertEquals(now,r.getLong("captured_at_ms"))
        assertEquals(54,r.getInt("battery_percent"))
        assertEquals("NOT_VERIFIED",r.getString("device_attestation"))
    }
    @Test fun expiredRejected() {assertThrows(IllegalArgumentException::class.java){BatteryChallenge.parse(token(now-2000,now-1),now)}}
    @Test fun futureRejected() {assertThrows(IllegalArgumentException::class.java){BatteryChallenge.parse(token(now+1,now+1000),now)}}
    @Test fun excessTtlRejected() {assertThrows(IllegalArgumentException::class.java){BatteryChallenge.parse(token(now-1,now+1800001),now)}}
    @Test fun invalidNonceRejected() {assertThrows(IllegalArgumentException::class.java){BatteryChallenge.parse(token(n="oops"),now)}}
    @Test fun extraCommandRejected() {assertThrows(IllegalArgumentException::class.java){BatteryChallenge.parse(token()+":shell",now)}}
    @Test fun unavailableBatteryRejected() {assertThrows(IllegalArgumentException::class.java){BatteryChallenge.parse(token(),now).response(-1,now,"test")}}
    @Test fun expiryAtCaptureRejected() {assertThrows(IllegalArgumentException::class.java){BatteryChallenge.parse(token(),now).response(54,now+1001,"test")}}
}
