package io.executionbridge.android

import java.net.URI
import java.util.UUID

object Endpoint {
    fun base(raw: String): URI {
        val u = URI(raw.trim().trimEnd('/'))
        require(u.scheme == "https" && u.host != null && u.userInfo == null &&
            u.rawQuery == null && u.rawFragment == null && u.rawPath.isNullOrEmpty())
        return u
    }
    fun id(raw: String): String {
        require(UUID.fromString(raw).toString() == raw)
        return raw
    }
}
