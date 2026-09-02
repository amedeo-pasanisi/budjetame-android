package com.budjetame.android.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorMappingTest {

    private fun httpException(status: Int, body: String): HttpException =
        HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `a string detail becomes the exception detail`() {
        val error = httpException(422, """{"detail":"Unknown wallet 'X'"}""").toApiException()
        assertEquals(422, error.status)
        assertEquals("Unknown wallet 'X'", error.detail)
        assertEquals("Unknown wallet 'X'", error.message)
    }

    @Test
    fun `a structured detail contributes its message field`() {
        val error = httpException(
            409,
            """{"detail":{"message":"Name already taken","target_id":1,"transaction_count":3}}""",
        ).toApiException()
        assertEquals(409, error.status)
        assertEquals("Name already taken", error.detail)
    }

    @Test
    fun `a structured detail stays readable as an object for typed conflicts`() {
        // The category merge conflict (ADR-0007 in the web repo): the
        // repository reads the raw fields off the same parsed body the
        // message came from, without a second (exhausted) read.
        val error = httpException(
            409,
            """{"detail":{"message":"Name already taken","target_id":7,"transaction_count":12}}""",
        ).toApiException()
        assertEquals(7, error.detailObject?.get("target_id")?.toString()?.toInt())
        assertEquals(12, error.detailObject?.get("transaction_count")?.toString()?.toInt())
    }

    @Test
    fun `a string detail carries no structured object`() {
        val error = httpException(409, """{"detail":"Name already taken"}""").toApiException()
        assertNull(error.detailObject)
    }

    @Test
    fun `a non-JSON body falls back to a generic message`() {
        val error = httpException(500, "<html>gateway error</html>").toApiException()
        assertEquals(500, error.status)
        assertNull(error.detail)
        assertTrue(error.message!!.contains("500"))
    }

    @Test
    fun `apiErrorMessage maps the contract statuses`() {
        assertEquals("Name taken", apiErrorMessage(409, "Name taken", "Failed"))
        assertEquals("Check the fields and try again.", apiErrorMessage(422, "Name taken", "Failed"))
        assertEquals("Failed", apiErrorMessage(500, "Name taken", "Failed"))
        assertEquals("Failed", apiErrorMessage(null, "Name taken", "Failed"))
    }
}
