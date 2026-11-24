package io.executionbridge.android
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

class EndpointTest {
    @Test fun httpsOnly() {
        assertEquals("example.org",Endpoint.base("https://example.org/").host)
        listOf("http://example.org","https://user@example.org","https://example.org/path","https://example.org?x=1","https://example.org#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { Endpoint.base(it) }
        }
    }
}
