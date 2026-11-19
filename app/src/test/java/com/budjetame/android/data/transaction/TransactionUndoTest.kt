package com.budjetame.android.data.transaction

import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.TransactionApi
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The Transaction undo at the HTTP seam (web ADR-0031, ticket #58): the
 * held row travels to `POST /transactions/undo` field for field — exactly
 * the backend's TransactionUndoRequest keys, which forbids any other — and
 * a 4xx surfaces the server's `detail`. A linked Transaction's pin (its
 * Recurring Cost or Recurring Income and the Occurrence it paid) rides the
 * body so the server restores that same Occurrence (ticket #61); a
 * success bumps the data version — every screen, the Recurring ones
 * included, revalidates on it (ADR-0002) — and the pin-taken 422 bumps
 * nothing.
 */
class TransactionUndoTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ApiTransactionRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = ApiClient(server.url("/api/").toString()) { null }
        repository = ApiTransactionRepository(client.create(TransactionApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val linkedRow = TransactionDto(
        id = 42,
        type = TransactionType.EXPENSE,
        amount = "12.50",
        date = "2026-08-03",
        wallet_id = 1,
        category_id = 4,
        recurring_cost_id = 9,
        occurrence_date = "2026-08-01",
        description = "Gym",
        latitude = "45.4642",
        longitude = "9.19",
        place_name = "Palestra",
        place_id = "ChIJ123",
        warning = true,
        created_at = "2026-08-03T10:00:00Z",
    )

    private val rowJson = """
        {"id":42,"type":"expense","amount":"12.50","date":"2026-08-03","wallet_id":1,
         "category_id":4,"recurring_cost_id":9,"occurrence_date":"2026-08-01",
         "description":"Gym","latitude":"45.4642","longitude":"9.19",
         "place_name":"Palestra","place_id":"ChIJ123","warning":false,
         "created_at":"2026-08-19T08:00:00Z"}
    """.trimIndent()

    @Test
    fun `the undo body is the held row field for field and nothing else`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(rowJson))

        val restored = repository.undoTransaction(linkedRow)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/transactions/undo", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val expected = JsonObject(
            mapOf(
                "id" to JsonPrimitive(42),
                "type" to JsonPrimitive("expense"),
                "amount" to JsonPrimitive("12.50"),
                "date" to JsonPrimitive("2026-08-03"),
                "wallet_id" to JsonPrimitive(1),
                "category_id" to JsonPrimitive(4),
                "recurring_cost_id" to JsonPrimitive(9),
                "occurrence_date" to JsonPrimitive("2026-08-01"),
                "description" to JsonPrimitive("Gym"),
                "latitude" to JsonPrimitive("45.4642"),
                "longitude" to JsonPrimitive("9.19"),
                "place_name" to JsonPrimitive("Palestra"),
                "place_id" to JsonPrimitive("ChIJ123"),
            ),
        )
        assertEquals(expected, body)
        assertEquals(42, restored.id)
        assertEquals("2026-08-03", restored.date)
    }

    @Test
    fun `a transfer's undo carries both legs and its income pin`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(rowJson))
        val transfer = TransactionDto(
            id = 7,
            type = TransactionType.TRANSFER,
            amount = "40.00",
            date = "2026-08-02",
            source_wallet_id = 3,
            destination_wallet_id = 1,
            recurring_income_id = 5,
            occurrence_date = "2026-08-01",
            created_at = "2026-08-02T10:00:00Z",
        )

        repository.undoTransaction(transfer)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            setOf("id", "type", "amount", "date", "source_wallet_id", "destination_wallet_id", "recurring_income_id", "occurrence_date"),
            body.keys,
        )
        assertEquals(JsonPrimitive("transfer"), body["type"])
        assertEquals(JsonPrimitive(5), body["recurring_income_id"])
    }

    @Test
    fun `an income's undo carries its Recurring Income pin`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(rowJson))

        repository.undoTransaction(linkedIncome)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            setOf("id", "type", "amount", "date", "wallet_id", "recurring_income_id", "occurrence_date"),
            body.keys,
        )
        assertEquals(JsonPrimitive("income"), body["type"])
        assertEquals(JsonPrimitive(5), body["recurring_income_id"])
        assertEquals(JsonPrimitive("2026-08-01"), body["occurrence_date"])
    }

    @Test
    fun `a successful undo bumps the data version so every screen revalidates`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(rowJson))
        val before = DataVersion.current()

        repository.undoTransaction(linkedRow)

        assertEquals(before + 1, DataVersion.current())
    }

    @Test
    fun `a Recurring Cost pin-taken 422 surfaces its detail and bumps nothing`() = runBlocking {
        assertPinTaken(linkedRow)
    }

    @Test
    fun `a Recurring Income pin-taken 422 surfaces its detail and bumps nothing`() = runBlocking {
        assertPinTaken(linkedIncome)
    }

    private suspend fun assertPinTaken(row: TransactionDto) {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"$PIN_TAKEN"}"""))
        val before = DataVersion.current()

        try {
            repository.undoTransaction(row)
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals(422, error.status)
            assertEquals(PIN_TAKEN, error.detail)
        }
        assertEquals(before, DataVersion.current())
    }

    private val linkedIncome = TransactionDto(
        id = 8,
        type = TransactionType.INCOME,
        amount = "1500.00",
        date = "2026-08-02",
        wallet_id = 1,
        recurring_income_id = 5,
        occurrence_date = "2026-08-01",
        created_at = "2026-08-02T10:00:00Z",
    )

    private companion object {
        /** The backend's exact pin-taken detail (services/transactions.py). */
        const val PIN_TAKEN = "This Occurrence was already paid by another Transaction"
    }
}
