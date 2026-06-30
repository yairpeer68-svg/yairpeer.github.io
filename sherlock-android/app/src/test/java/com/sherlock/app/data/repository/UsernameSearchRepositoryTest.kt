package com.sherlock.app.data.repository

import com.sherlock.app.data.model.ErrorType
import com.sherlock.app.data.model.SiteCategory
import com.sherlock.app.data.model.SiteConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsernameSearchRepositoryTest {

    private lateinit var server: MockWebServer
    private val repository = UsernameSearchRepository()

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

    private fun siteFor(
        errorType: ErrorType,
        errorIndicator: String = ""
    ) = SiteConfig(
        name = "TestSite",
        urlTemplate = server.url("/{}").toString().replace("%7B%7D", "{}"),
        category = SiteCategory.OTHER,
        errorType = errorType,
        errorIndicator = errorIndicator
    )

    @Test
    fun `status code site marks profile as existing on 2xx`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = repository.checkSite(siteFor(ErrorType.STATUS_CODE), "alice")

        assertTrue(result.exists)
        assertEquals(200, result.httpStatus)
    }

    @Test
    fun `status code site marks profile as missing on 404`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.checkSite(siteFor(ErrorType.STATUS_CODE), "alice")

        assertFalse(result.exists)
        assertEquals(404, result.httpStatus)
    }

    @Test
    fun `message in page site marks profile as missing when error indicator is found`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody("Sorry, this user was not found"))

        val result = repository.checkSite(siteFor(ErrorType.MESSAGE_IN_PAGE, "not found"), "alice")

        assertFalse(result.exists)
    }

    @Test
    fun `message in page site marks profile as existing when error indicator is absent`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody("Welcome to alice's profile"))

        val result = repository.checkSite(siteFor(ErrorType.MESSAGE_IN_PAGE, "not found"), "alice")

        assertTrue(result.exists)
    }

    @Test
    fun `redirect type site marks profile as missing on 3xx response`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/login"))

        val result = repository.checkSite(siteFor(ErrorType.REDIRECT), "alice")

        assertFalse(result.exists)
    }

    @Test
    fun `network failure is caught and reported as a non-existing result`() {
        server.shutdown()

        val result = repository.checkSite(siteFor(ErrorType.STATUS_CODE), "alice")

        assertFalse(result.exists)
        assertEquals(0, result.httpStatus)
    }
}
