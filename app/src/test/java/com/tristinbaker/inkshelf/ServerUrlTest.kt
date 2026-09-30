package com.tristinbaker.inkshelf

import com.tristinbaker.inkshelf.core.net.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    private fun valid(raw: String): ServerUrl {
        val result = ServerUrl.parse(raw)
        assertTrue("expected $raw to parse, got $result", result is ServerUrl.Result.Valid)
        return (result as ServerUrl.Result.Valid).url
    }

    @Test
    fun `rejects plain http with an actionable message`() {
        val result = ServerUrl.parse("http://books.example.com")
        assertTrue(result is ServerUrl.Result.Invalid)
        val reason = (result as ServerUrl.Result.Invalid).reason
        assertTrue("should mention http", reason.contains("http://"))
        assertTrue("should point at the fix", reason.contains("https://"))
    }

    @Test
    fun `rejects a missing scheme`() {
        val result = ServerUrl.parse("books.example.com")
        assertTrue(result is ServerUrl.Result.Invalid)
    }

    @Test
    fun `rejects a blank address`() {
        assertTrue(ServerUrl.parse("   ") is ServerUrl.Result.Invalid)
    }

    @Test
    fun `rejects a bad port`() {
        val result = ServerUrl.parse("https://books.example.com:notaport")
        assertTrue(result is ServerUrl.Result.Invalid)
        assertTrue((result as ServerUrl.Result.Invalid).reason.contains("Port"))
    }

    @Test
    fun `keeps an explicit port and normalises the scheme`() {
        val url = valid("HTTPS://books.example.com:8443")
        assertEquals("https://books.example.com:8443", url.canonical)
        assertEquals("books.example.com:8443", url.hostPort)
    }

    @Test
    fun `joins paths without doubling slashes when the base has a trailing one`() {
        val url = valid("https://books.example.com/")
        assertEquals("https://books.example.com/api/libraries", url.resolve("/api/libraries"))
        assertEquals("https://books.example.com/api/libraries", url.resolve("api/libraries"))
    }

    @Test
    fun `preserves a subpath install`() {
        val url = valid("https://example.com/audiobookshelf")
        assertTrue(url.isSubPath)
        assertEquals(
            "https://example.com/audiobookshelf/api/libraries",
            url.resolve("/api/libraries"),
        )
        assertEquals("example.com", url.hostPort)
    }

    @Test
    fun `strips redundant slashes inside a subpath`() {
        val url = valid("https://example.com//audiobookshelf//")
        assertEquals("https://example.com/audiobookshelf", url.canonical)
        assertEquals(
            "https://example.com/audiobookshelf/api/libraries",
            url.resolve("api/libraries"),
        )
    }

    @Test
    fun `drops the default port from the pinning key`() {
        assertEquals("books.example.com", valid("https://books.example.com:443").hostPort)
    }

    @Test
    fun `percent encodes query values containing dots and base64 characters`() {
        val url = valid("https://books.example.com")
        val target = url.resolveWithQuery(
            "/api/libraries/abc/items",
            listOf("sort" to "media.metadata.title", "filter" to "series.U3ludHkgQ29uZA=="),
        )
        assertTrue(target.contains("sort=media.metadata.title"))
        assertFalse("base64 must not leak a raw '=' separator", target.contains("=U3ludH"))
        assertTrue(target.contains("filter=series.U3ludHkgQ29uZA%3D%3D"))
    }
}
