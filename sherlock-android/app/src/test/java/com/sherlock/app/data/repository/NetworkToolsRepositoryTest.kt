package com.sherlock.app.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NetworkToolsRepositoryTest {

    private lateinit var server: MockWebServer
    private val repository = NetworkToolsRepository()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    @Test
    fun `getHttpHeaders detects nginx server and returns status code`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Server", "nginx/1.18.0")
                .setBody("<html></html>")
        )

        val result = repository.getHttpHeaders(server.url("/").toString())

        assertEquals(200, result.statusCode)
        assertTrue(result.detectedTech.contains("Nginx"))
    }

    @Test
    fun `getHttpHeaders detects wordpress from body content`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><head></head><body><div class=\"wp-content\"></div></body></html>")
        )

        val result = repository.getHttpHeaders(server.url("/").toString())

        assertTrue(result.detectedTech.contains("WordPress"))
    }

    @Test
    fun `getHttpHeaders wraps network failures in a Hebrew error message`() = runBlocking {
        server.shutdown()

        try {
            repository.getHttpHeaders(server.url("/").toString())
            fail("Expected an exception when the server is unreachable")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("שגיאה בבדיקת הכתובת"))
        }
    }

    @Test
    fun `getWebsiteSnapshot extracts title and description`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    <html>
                      <head>
                        <title>Example Site</title>
                        <meta name="description" content="An example description">
                      </head>
                      <body></body>
                    </html>
                    """.trimIndent()
                )
        )

        val snapshot = repository.getWebsiteSnapshot(server.url("/").toString())

        assertEquals("Example Site", snapshot.title)
        assertEquals("An example description", snapshot.description)
        assertEquals(false, snapshot.redirected)
    }

    @Test
    fun `getWebsiteSnapshot handles missing title gracefully`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>no title here</body></html>"))

        val snapshot = repository.getWebsiteSnapshot(server.url("/").toString())

        assertNull(snapshot.title)
    }

    @Test
    fun `expandRedirectChain follows a single redirect hop`() = runBlocking {
        val targetUrl = server.url("/final").toString()
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", targetUrl))
        server.enqueue(MockResponse().setResponseCode(200))

        val hops = repository.expandRedirectChain(server.url("/start").toString())

        assertEquals(2, hops.size)
        assertEquals(301, hops[0].statusCode)
        assertEquals(200, hops[1].statusCode)
    }

    @Test
    fun `expandRedirectChain stops immediately on a non-redirect response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val hops = repository.expandRedirectChain(server.url("/").toString())

        assertEquals(1, hops.size)
        assertEquals(200, hops[0].statusCode)
    }
}
