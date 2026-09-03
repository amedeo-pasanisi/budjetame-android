package com.budjetame.android.ui.imports

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.CategoryApi
import com.budjetame.android.data.api.CategoryCreateRequest
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.ImportApi
import com.budjetame.android.data.api.ImportBatchRevalidationRequest
import com.budjetame.android.data.api.ImportConfirmRequest
import com.budjetame.android.data.api.ImportPreviewDto
import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowRevalidationDto
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.ImportRowValidationDto
import com.budjetame.android.data.api.ImportRowValidationRequest
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.api.WalletCreateRequest
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.ApiCategoryRepository
import com.budjetame.android.data.imports.ApiImportRepository
import com.budjetame.android.data.wallet.ApiWalletRepository
import com.budjetame.android.ui.transactions.WalletFieldTarget
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The fixed template's canonical columns (in the backend's own order). */
private val TEMPLATE_COLUMNS = listOf(
    "date", "type", "amount", "wallet", "source_wallet",
    "destination_wallet", "category", "description", "location",
)

/** Normalized header text → canonical field (the backend's _COLUMNS). */
private val HEADER_FIELDS = mapOf(
    "date" to "date",
    "type" to "type",
    "amount" to "amount",
    "wallet" to "wallet",
    "sourcewallet" to "source_wallet",
    "destinationwallet" to "destination_wallet",
    "category" to "category",
    "description" to "description",
    "location" to "location",
)

/** The wire value of a TransactionType (the fake echoes the backend's
 * enum names back). */
private fun wireType(type: String): TransactionType = when (type) {
    "expense" -> TransactionType.EXPENSE
    "income" -> TransactionType.INCOME
    else -> TransactionType.TRANSFER
}

/** The type string a TransactionType travels as on the wire. */
private fun wireName(type: TransactionType): String = when (type) {
    TransactionType.EXPENSE -> "expense"
    TransactionType.INCOME -> "income"
    TransactionType.OPENING_BALANCE -> "opening_balance"
    TransactionType.TRANSFER -> "transfer"
}

/**
 * The Import flow tested at the single seam (the HTTP API): the ViewModel
 * is driven through the real repository, Retrofit, OkHttp, and a
 * MockWebServer whose dispatcher is a small stateful fake of the /import
 * resource — the multipart preview over an uploaded CSV (the rows parsed
 * and classified ok/duplicate/error against the fake's Wallets, Categories,
 * and inserted Transactions), the single-row re-validation of Verification
 * (web issue #44) with the edited row and the in-file Duplicate context,
 * and the transactional confirm. The fake keys duplicates exactly like the
 * backend (ADR-0006: a blank description matches a missing one, amounts
 * quantized to cents), so a second preview after a confirm flags every
 * imported row. Request bodies are captured for assertions.
 */
class ImportViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(val method: String, val path: String, val body: String)

    /** The fake's Account state: resolvable Wallets and Categories by name
     * and the inserted Transactions (the fake's "database"). */
    private class FakeWallet(val name: String, val frozen: Boolean = false, val contact: Boolean = false)
    private class FakeCategory(val name: String, val type: String)
    private class StoredTransaction(
        val type: String,
        val date: String,
        val amount: String,
        val wallet: String? = null,
        val category: String? = null,
        val sourceWallet: String? = null,
        val destinationWallet: String? = null,
        val description: String? = null,
    )

    private lateinit var server: MockWebServer
    private lateinit var viewModel: ImportViewModel

    private val wallets = mutableListOf<FakeWallet>()
    private val categories = mutableListOf<FakeCategory>()
    private val stored = mutableListOf<StoredTransaction>()
    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private var nextTransactionId = 1
    private var nextWalletId = 1
    private var nextCategoryId = 1

    /** Failure-injection knobs: when non-200, the endpoint answers the
     * recorded detail without touching the fake's state. */
    private var previewStatus = 200
    private var previewDetail = ""
    private var validateStatus = 200
    private var validateDetail = ""
    private var revalidateStatus = 200
    private var revalidateDetail = ""
    private var walletCreateStatus = 201
    private var walletCreateDetail = ""
    private var categoryCreateStatus = 201
    private var categoryCreateDetail = ""
    private var confirmStatus = 201
    private var confirmDetail = ""

    /** When true, the created Transactions carry the Cash negative-balance
     * warning flag. */
    private var confirmWarning = false

    /** Test hook: when set, the preview dispatch blocks on it — a read the
     * test can cancel mid-flight. */
    private var previewGate: CountDownLatch? = null

    /** Test hook: when set, a batch re-validation dispatch blocks on it —
     * after the verdicts are computed, before the response goes out — so a
     * test can interleave a change between the computation and the apply. */
    private var revalidateGate: CountDownLatch? = null

    /** Test hook: counted down once a batch re-validation dispatch has
     * computed its verdicts (and, with `revalidateGate`, is about to block
     * on it) — lets a test interleave a change at a deterministic point. */
    private var revalidateComputed: CountDownLatch? = null

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        wallets.clear()
        categories.clear()
        stored.clear()
        calls.clear()
        nextTransactionId = 1
        nextWalletId = 1
        nextCategoryId = 1
        previewStatus = 200
        previewDetail = ""
        validateStatus = 200
        validateDetail = ""
        revalidateStatus = 200
        revalidateDetail = ""
        walletCreateStatus = 201
        walletCreateDetail = ""
        categoryCreateStatus = 201
        categoryCreateDetail = ""
        confirmStatus = 201
        confirmDetail = ""
        confirmWarning = false
        previewGate = null
        revalidateGate = null
        revalidateComputed = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun createViewModel() {
        val client = ApiClient(server.url("/api/").toString()) { null }
        viewModel = ImportViewModel(
            imports = ApiImportRepository(client.create(ImportApi::class.java)),
            wallets = ApiWalletRepository(client.create(WalletApi::class.java)),
            categories = ApiCategoryRepository(client.create(CategoryApi::class.java)),
        )
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, body))
        return when {
            method == "POST" && path == "/api/import/preview" -> preview(body)
            method == "POST" && path == "/api/import/validate-row" -> validateRow(body)
            method == "POST" && path == "/api/import/revalidate-rows" -> revalidateRows(body)
            method == "POST" && path == "/api/import/confirm" -> confirm(body)
            method == "POST" && path == "/api/wallets" -> createWallet(body)
            method == "POST" && path == "/api/categories" -> createCategory(body)
            else -> jsonResponse(404, """{"detail":"not found"}""")
        }
    }

    // --- The fake's emulation of the backend --------------------------------

    /** The file part of a multipart upload: (file name, content). */
    private fun uploadedFile(body: String): Pair<String, String> {
        val text = body.replace("\r\n", "\n")
        val marker = text.indexOf("name=\"file\"")
        val headerEnd = text.indexOf("\n\n", marker)
        val name = Regex("filename=\"([^\"]*)\"").find(text.substring(marker, headerEnd))
            ?.groupValues?.get(1).orEmpty()
        val contentStart = headerEnd + 2
        val closing = text.indexOf("\n--", contentStart)
        val content = if (closing >= 0) text.substring(contentStart, closing) else text.substring(contentStart)
        return name to content
    }

    private fun preview(body: String): MockResponse {
        previewGate?.await()
        if (previewStatus != 200) return jsonResponse(previewStatus, """{"detail":"$previewDetail"}""")
        val (name, content) = uploadedFile(body)
        if (!name.lowercase().endsWith(".csv")) {
            return jsonResponse(422, """{"detail":"Only .csv and .xlsx files are supported"}""")
        }
        if (content.isBlank()) return jsonResponse(422, """{"detail":"The file is empty"}""")
        val cells = parseCsv(content)
        if (cells.isEmpty()) return jsonResponse(422, """{"detail":"The file is empty"}""")
        // The header's cells normalize onto the template's canonical
        // fields: "source wallet" and "source_wallet" are the same column.
        val headerFields = cells.first().map { header -> HEADER_FIELDS[normalizeHeader(header)] }
        val missing = TEMPLATE_COLUMNS.filter { it !in headerFields }
        if (missing.isNotEmpty()) {
            val sorted = missing.sorted()
            return jsonResponse(422, """{"detail":"Missing required column(s): ${sorted.joinToString(", ")}"}""")
        }
        val columnByField = headerFields.mapIndexedNotNull { index, field ->
            field?.let { it to index }
        }.toMap()
        val rows = cells.drop(1).mapIndexed { index, row ->
            classifyRow(
                rowNumber = index + 2,
                row = row,
                columnByField = columnByField,
            )
        }
        var okCount = 0
        var duplicateCount = 0
        var errorCount = 0
        val seen = HashSet<List<Any?>>()
        val verdicts = rows.map { row ->
            if (row.error == null) {
                resolve(row)?.let { params ->
                    val key = dupKey(params)
                    if (stored.any { dupKey(it) == key } || key in seen) {
                        duplicateCount++
                        row.withStatus(ImportRowStatus.DUPLICATE)
                    } else {
                        okCount++
                        seen.add(key)
                        row.withStatus(ImportRowStatus.OK)
                    }
                } ?: row.copy(error = lastResolveError).also { errorCount++ }
            } else {
                errorCount++
                row
            }
        }
        return jsonResponse(
            200,
            json.encodeToString(
                ImportPreviewDto(
                    rows = verdicts.map { it.toDto() },
                    ok_count = okCount,
                    error_count = errorCount,
                    duplicate_count = duplicateCount,
                ),
            ),
        )
    }

    private fun validateRow(body: String): MockResponse {
        if (validateStatus != 200) return jsonResponse(validateStatus, """{"detail":"$validateDetail"}""")
        val request = json.decodeFromString<ImportRowValidationRequest>(body)
        val seen = HashSet<List<Any?>>()
        for (earlier in request.earlier_rows) {
            resolve(earlier)?.let { seen.add(dupKey(it)) }
        }
        val verdict: ImportRowValidationDto = resolve(request.row)?.let { params ->
            if (stored.any { dupKey(it) == dupKey(params) } || dupKey(params) in seen) {
                ImportRowValidationDto(ImportRowStatus.DUPLICATE)
            } else {
                ImportRowValidationDto(ImportRowStatus.OK)
            }
        } ?: ImportRowValidationDto(ImportRowStatus.ERROR, error = lastResolveError)
        return jsonResponse(200, json.encodeToString(verdict))
    }

    /** The fake's batch Revalidation (web issue #76): every target row
     * re-validated exactly like the single-row one, with the rows that
     * precede it in the Draft as its in-file Duplicate context. The verdicts
     * are computed against the fake's state at dispatch time; the optional
     * gate blocks the response after the computation. */
    private fun revalidateRows(body: String): MockResponse {
        if (revalidateStatus != 200) {
            return jsonResponse(revalidateStatus, """{"detail":"$revalidateDetail"}""")
        }
        val request = json.decodeFromString<ImportBatchRevalidationRequest>(body)
        val byRowNumber = request.rows.associateBy { it.row }
        val verdicts = mutableListOf<ImportRowRevalidationDto>()
        for (target in request.targets) {
            // The in-file half of the Duplicate check: the rows preceding
            // the target in the file, their keys added in order.
            val seen = HashSet<List<Any?>>()
            for (row in request.rows) {
                if (row.row >= target) break
                resolve(row)?.let { seen.add(dupKey(it)) }
            }
            val targetRow = byRowNumber[target] ?: continue
            val verdict: ImportRowRevalidationDto = resolve(targetRow)?.let { params ->
                if (stored.any { dupKey(it) == dupKey(params) } || dupKey(params) in seen) {
                    ImportRowRevalidationDto(target, ImportRowStatus.DUPLICATE)
                } else {
                    ImportRowRevalidationDto(target, ImportRowStatus.OK)
                }
            } ?: ImportRowRevalidationDto(target, ImportRowStatus.ERROR, error = lastResolveError)
            verdicts.add(verdict)
        }
        revalidateComputed?.countDown()
        revalidateGate?.await()
        return jsonResponse(200, json.encodeToString(verdicts))
    }

    /** The fake's Wallet create: the Wallet joins the Account's resolvable
     * Wallets immediately — inline creation is real at once (ADR-0014). */
    private fun createWallet(body: String): MockResponse {
        if (walletCreateStatus != 201) {
            return jsonResponse(walletCreateStatus, """{"detail":"$walletCreateDetail"}""")
        }
        val request = json.decodeFromString<WalletCreateRequest>(body)
        wallets.add(FakeWallet(request.name, frozen = false, contact = request.type == WalletType.CONTACT))
        return jsonResponse(
            201,
            json.encodeToString(
                WalletDto(
                    id = nextWalletId++,
                    name = request.name,
                    type = request.type,
                    balance = request.opening_balance,
                    frozen = false,
                    created_at = "2026-08-01T10:00:00Z",
                ),
            ),
        )
    }

    /** The fake's Category create: the Category joins the Account's
     * resolvable Categories immediately (ADR-0014). */
    private fun createCategory(body: String): MockResponse {
        if (categoryCreateStatus != 201) {
            return jsonResponse(categoryCreateStatus, """{"detail":"$categoryCreateDetail"}""")
        }
        val request = json.decodeFromString<CategoryCreateRequest>(body)
        val typeName = when (request.type) {
            CategoryType.EXPENSE -> "expense"
            CategoryType.INCOME -> "income"
        }
        categories.add(FakeCategory(request.name, typeName))
        return jsonResponse(
            201,
            json.encodeToString(
                CategoryDto(
                    id = nextCategoryId++,
                    name = request.name,
                    type = request.type,
                    icon = request.icon.ifBlank { null },
                    color = request.color,
                    created_at = "2026-08-01T10:00:00Z",
                ),
            ),
        )
    }

    private fun confirm(body: String): MockResponse {
        if (confirmStatus != 201) return jsonResponse(confirmStatus, """{"detail":"$confirmDetail"}""")
        val request = json.decodeFromString<ImportConfirmRequest>(body)
        val created = mutableListOf<TransactionDto>()
        val checkAgainst = stored.toMutableList()
        for (input in request.rows) {
            val params = resolve(input)
                ?: return jsonResponse(422, """{"detail":"$lastResolveError"}""")
            if (checkAgainst.any { dupKey(it) == dupKey(params) }) {
                return jsonResponse(
                    422,
                    """{"detail":"Row ${input.row} duplicates an existing transaction"}""",
                )
            }
            stored.add(params)
            checkAgainst.add(params)
            created.add(
                TransactionDto(
                    id = nextTransactionId++,
                    type = input.type,
                    amount = input.amount,
                    date = input.date,
                    warning = confirmWarning,
                    created_at = "2026-08-01T10:00:00Z",
                ),
            )
        }
        return jsonResponse(201, json.encodeToString(created))
    }

    /** One row's classification state: the parsed template fields plus the
     * error message (null = parsed cleanly, pending resolution). */
    private data class ClassifiedRow(
        val rowNumber: Int,
        val type: String?,
        val date: String?,
        val amount: String?,
        val wallet: String?,
        val sourceWallet: String?,
        val destinationWallet: String?,
        val category: String?,
        val description: String?,
        val latitude: String?,
        val longitude: String?,
        val error: String? = null,
        val status: ImportRowStatus = ImportRowStatus.ERROR,
    ) {
        fun withStatus(status: ImportRowStatus) = copy(status = status)

        fun toDto() = ImportRowDto(
            row = rowNumber,
            status = status,
            type = type?.let(::wireType),
            date = date,
            amount = amount,
            wallet = wallet,
            source_wallet = sourceWallet,
            destination_wallet = destinationWallet,
            category = category,
            description = description,
            latitude = latitude,
            longitude = longitude,
            error = error,
        )
    }

    private fun wireType(type: String): TransactionType = when (type) {
        "expense" -> TransactionType.EXPENSE
        "income" -> TransactionType.INCOME
        else -> TransactionType.TRANSFER
    }

    private fun classifyRow(
        rowNumber: Int,
        row: List<String>,
        columnByField: Map<String, Int>,
    ): ClassifiedRow {
        fun field(name: String): String =
            columnByField[name]?.let { row.getOrNull(it)?.trim().orEmpty() } ?: ""

        var type = field("type").ifEmpty { null }
        var date = field("date").ifEmpty { null }
        var amount: String? = null
        var latitude: String? = null
        var longitude: String? = null
        val wallet = field("wallet").ifEmpty { null }
        val sourceWallet = field("source_wallet").ifEmpty { null }
        val destinationWallet = field("destination_wallet").ifEmpty { null }
        val category = field("category").ifEmpty { null }
        // A blank description is missing (ADR-0006), exactly like the
        // backend's _blank.
        val description = field("description").ifEmpty { null }
        val errors = mutableListOf<String>()

        if (type == null || type.lowercase() !in setOf("expense", "income", "transfer")) {
            errors.add("Type must be expense, income, or transfer" +
                (if (type != null) " (got '$type')" else ""))
        } else {
            type = type.lowercase()
        }
        if (date == null) {
            errors.add("Date is required")
        } else if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) {
            errors.add("Invalid date '$date' (use YYYY-MM-DD)")
        }
        val amountText = field("amount")
        if (amountText.isEmpty()) {
            errors.add("Amount is required")
        } else {
            val parsed = parseEuro(amountText)
            if (parsed == null) {
                errors.add("Invalid amount '$amountText'")
            } else if (parsed <= BigDecimal.ZERO) {
                errors.add("Amount must be positive (got '$amountText')")
            } else {
                amount = cents(parsed)
            }
        }
        if (description != null && description.length > 500) {
            errors.add("Description is too long (max 500 characters)")
        }
        val location = field("location")
        if (location.isNotEmpty()) {
            val parts = location.split(",", ";").map { it.trim() }
            val decimals = parts.mapNotNull { runCatching { BigDecimal(it) }.getOrNull() }
            val inRange = decimals.size == 2 &&
                decimals[0] >= BigDecimal("-90") && decimals[0] <= BigDecimal("90") &&
                decimals[1] >= BigDecimal("-180") && decimals[1] <= BigDecimal("180")
            if (parts.size != 2 || decimals.size != 2 || !inRange) {
                errors.add("Invalid location '$location' (use 'lat,lon')")
            } else {
                latitude = decimals[0].stripTrailingZeros().toPlainString()
                longitude = decimals[1].stripTrailingZeros().toPlainString()
            }
        }
        return ClassifiedRow(
            rowNumber = rowNumber,
            type = type,
            date = date,
            amount = amount,
            wallet = wallet,
            sourceWallet = sourceWallet,
            destinationWallet = destinationWallet,
            category = category,
            description = description,
            latitude = latitude,
            longitude = longitude,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    private var lastResolveError: String? = null

    /** Resolve the row's names against the fake's Wallets/Categories and
     * return the insertion params — the duplicate key's carrier — or null
     * with `lastResolveError` set. Mirrors the backend's resolution and the
     * template-shape guards. */
    private fun resolve(row: ClassifiedRow): StoredTransaction? = resolve(
        type = row.type,
        date = row.date,
        amount = row.amount,
        wallet = row.wallet,
        sourceWallet = row.sourceWallet,
        destinationWallet = row.destinationWallet,
        category = row.category,
        description = row.description,
    )

    private fun resolve(input: ImportRowInput): StoredTransaction? = resolve(
        type = wireName(input.type),
        date = input.date,
        amount = cents(BigDecimal(input.amount)),
        wallet = input.wallet,
        sourceWallet = input.source_wallet,
        destinationWallet = input.destination_wallet,
        category = input.category,
        description = input.description?.ifBlank { null },
    )

    private fun resolve(
        type: String?,
        date: String?,
        amount: String?,
        wallet: String?,
        sourceWallet: String?,
        destinationWallet: String?,
        category: String?,
        description: String?,
    ): StoredTransaction? {
        if (type == null || date == null || amount == null) return null
        if (type == "transfer") {
            if (wallet != null) {
                lastResolveError = "Transfer rows use source wallet and destination wallet, not wallet"
                return null
            }
            if (category != null) {
                lastResolveError = "Transfers never carry a category"
                return null
            }
            if (sourceWallet == null || destinationWallet == null) {
                lastResolveError = "source wallet and destination wallet are required for a Transfer"
                return null
            }
            val source = wallets.find { it.name.equals(sourceWallet, ignoreCase = true) }
                ?: return unknownWallet(sourceWallet)
            if (source.frozen) return frozenWallet(sourceWallet)
            val destination = wallets.find { it.name.equals(destinationWallet, ignoreCase = true) }
                ?: return unknownWallet(destinationWallet)
            if (destination.frozen) return frozenWallet(destinationWallet)
            return StoredTransaction(
                type = type,
                date = date,
                amount = amount,
                sourceWallet = sourceWallet,
                destinationWallet = destinationWallet,
                description = description,
            )
        }
        if (sourceWallet != null || destinationWallet != null) {
            lastResolveError = "source and destination wallets are only for Transfers"
            return null
        }
        if (wallet == null) {
            lastResolveError = "wallet_id is required for Expense and Income"
            return null
        }
        val found = wallets.find { it.name.equals(wallet, ignoreCase = true) }
            ?: return unknownWallet(wallet)
        if (found.frozen) return frozenWallet(wallet)
        if (found.contact && type != "expense") {
            lastResolveError = "Incomes can't be recorded on Contact Wallets"
            return null
        }
        var resolvedCategory: String? = null
        if (category != null) {
            val match = categories.find {
                it.name.equals(category, ignoreCase = true) && it.type == type
            }
            if (match != null) {
                resolvedCategory = category
            } else {
                val otherType = categories.find { it.name.equals(category, ignoreCase = true) }?.type
                if (otherType != null) {
                    lastResolveError = "Category '$category' is a $otherType category, not $type"
                    return null
                }
                lastResolveError = "Unknown $type category '$category'"
                return null
            }
        }
        return StoredTransaction(
            type = type,
            date = date,
            amount = amount,
            wallet = wallet,
            category = resolvedCategory,
            description = description,
        )
    }

    private fun unknownWallet(name: String): StoredTransaction? {
        lastResolveError = "Unknown wallet '$name'"
        return null
    }

    private fun frozenWallet(name: String): StoredTransaction? {
        lastResolveError = "Wallet '$name' is frozen"
        return null
    }

    /** The ADR-0006 duplicate key: date, cents-quantized amount, the wallet
     * names (resolved ones are unique per Account), the Category, and the
     * description — blank and missing key identically. */
    private fun dupKey(tx: StoredTransaction): List<Any?> = if (tx.type == "transfer") {
        listOf("transfer", tx.date, tx.amount, tx.sourceWallet, tx.destinationWallet, tx.description.orEmpty())
    } else {
        listOf(tx.type, tx.date, tx.amount, tx.wallet, tx.category, tx.description.orEmpty())
    }

    private fun cents(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun parseEuro(value: String): BigDecimal? {
        val cleaned = value.replace("€", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        return try {
            var v = cleaned
            if ("," in v && "." in v) {
                v = if (v.lastIndexOf(",") > v.lastIndexOf(".")) {
                    v.replace(".", "").replace(",", ".")
                } else {
                    v.replace(",", "")
                }
            } else if ("," in v) {
                v = v.replace(",", ".")
            }
            BigDecimal(v)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun parseCsv(content: String): List<List<String>> {
        val lines = content.replace("\r\n", "\n").split("\n")
        val first = lines.firstOrNull().orEmpty()
        val delimiter = if (";" in first && "," !in first) ";" else ","
        return lines
            .filter { line -> line.split(delimiter).any { it.isNotBlank() } }
            .map { line -> line.split(delimiter).map { it.trim() } }
    }

    private fun normalizeHeader(header: String): String =
        Regex("""[\s_]+""").replace(header.lowercase(), "")

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    // --- Fixtures and helpers ----------------------------------------------

    private fun seedWallet(name: String, frozen: Boolean = false, contact: Boolean = false) {
        wallets.add(FakeWallet(name, frozen, contact))
    }

    private fun seedCategory(name: String, type: String) {
        categories.add(FakeCategory(name, type))
    }

    private fun seedStored(tx: StoredTransaction) {
        stored.add(tx)
    }

    /** The CSV fixture: a header plus one data row per line; the first data
     * row is file line 2, and the preview numbers rows from there. */
    private fun csv(headers: String, vararg rows: String): ByteArray =
        (listOf(headers) + rows).joinToString("\n").toByteArray()

    private fun pickAndRead(fileName: String = "rows.csv", content: ByteArray) = runBlocking {
        viewModel.open()
        viewModel.onFilePicked(fileName, content)
        viewModel.readFile()
        withTimeout(5_000) {
            viewModel.uiState.first { it.draft?.phase == ImportPhase.PREVIEW }
        }
    }

    private suspend fun awaitDraft(predicate: (ImportDraft) -> Boolean): ImportDraft {
        withTimeout(5_000) { viewModel.uiState.first { it.draft?.let(predicate) == true } }
        return viewModel.uiState.value.draft!!
    }

    private fun callsFor(path: String): List<RecordedCall> =
        calls.filter { it.path == path }

    private fun callBody(path: String): String = callsFor(path).last().body

    private fun readyRow(row: Int, date: String, description: String? = null): ImportRowDto =
        ImportRowDto(row = row, status = ImportRowStatus.OK, type = TransactionType.EXPENSE, date = date, amount = "12.50")

    /** The fake's pre-existing database: one transaction matching the
     * fixture file's row 3, so that row previews as a Duplicate. */
    private fun defaultStore(): List<StoredTransaction> = listOf(
        StoredTransaction("expense", "2026-08-02", "5.00", "Checking", description = null),
    )

    // --- The flows ----------------------------------------------------------

    private fun fixtureViewModel() {
        seedWallet("Checking")
        seedWallet("Cash", contact = false)
        seedCategory("Food", "expense")
        seedCategory("Salary", "income")
        defaultStore().forEach(::seedStored)
        createViewModel()
    }

    /** A three-row fixture file: row 2 Ready, row 3 a Duplicate (already in
     * the fake's database), row 4 a Problem (an unknown Wallet). The header
     * carries the full fixed template; data rows may leave the trailing
     * columns blank. */
    private fun mixedFile(): ByteArray = csv(
        "date,type,amount,wallet,source wallet,destination wallet,category,description,location",
        "2026-08-01,expense,12.50,Checking,,,Food,coffee,",
        "2026-08-02,expense,5.00,Checking,,,,",
        "2026-08-03,expense,7.00,Mystery,,,,",
    )

    @Test
    fun `reading a file uploads it and previews every row with its verdict and counts`() = runBlocking {
        fixtureViewModel()
        viewModel.open()
        viewModel.onFilePicked("rows.csv", mixedFile())
        viewModel.readFile()

        awaitDraft { it.phase == ImportPhase.PREVIEW && it.preview != null }
        val preview = viewModel.uiState.value.draft!!.preview!!

        // The upload traveled as a multipart part named "file" carrying the
        // file's name and bytes.
        val upload = callsFor("/api/import/preview").single()
        val (uploadedName, uploadedContent) = uploadedFile(upload.body)
        assertEquals("rows.csv", uploadedName)
        assertTrue(uploadedContent.contains("2026-08-03,expense,7.00,Mystery,,"))

        assertEquals(listOf(ImportRowStatus.OK, ImportRowStatus.DUPLICATE, ImportRowStatus.ERROR), preview.rows.map { it.status })
        assertEquals(listOf(2, 3, 4), preview.rows.map { it.row })
        assertEquals(1, preview.ok_count)
        assertEquals(1, preview.duplicate_count)
        assertEquals(1, preview.error_count)

        val ready = preview.rows[0]
        assertEquals(TransactionType.EXPENSE, ready.type)
        assertEquals("2026-08-01", ready.date)
        assertEquals("12.50", ready.amount)
        assertEquals("Checking", ready.wallet)
        assertEquals("Food", ready.category)
        assertEquals("coffee", ready.description)
        assertNull(ready.error)
        // A blank description arrived as null (ADR-0006), and the duplicate
        // echoes its file fields without a message.
        assertNull(preview.rows[1].description)
        assertNull(preview.rows[1].category)
        assertNull(preview.rows[1].error)
        // The problem row's message names the missing Wallet.
        assertEquals("Unknown wallet 'Mystery'", preview.rows[2].error)

        // Every Ready row started selected; the rest never are.
        assertEquals(setOf(2), viewModel.uiState.value.draft!!.selected)
        val draft = viewModel.uiState.value.draft!!
        val counts = previewCounts(draft.preview!!)
        assertEquals("1 ready · 1 duplicate · 1 problem", importCountsText(counts.ready, counts.duplicates, counts.problems))
    }

    @Test
    fun `only ready rows can be toggled`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        viewModel.toggle(2)
        viewModel.toggle(3)
        viewModel.toggle(4)
        assertEquals(setOf<Int>(), viewModel.uiState.value.draft!!.selected)
    }

    @Test
    fun `verifying a problem row re-validates it and flips it to ready, auto-selected`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())

        viewModel.openEditor(4)
        assertEquals(4, viewModel.uiState.value.draft!!.editingRow)
        val input = editedRowInput(
            rowNumber = 4,
            type = TransactionType.EXPENSE,
            amount = "7.00",
            date = "2026-08-03",
            wallet = "Checking",
            sourceWallet = "",
            destinationWallet = "",
            category = "Food",
            description = "dinner",
            latitude = "",
            longitude = "",
        )
        viewModel.saveRowEdit(input)
        val draft = awaitDraft { it.editingRow == null }

        // The re-validation call carried the edited row and the draft's
        // preceding rows — with their edits — as the in-file context.
        val request = json.decodeFromString<ImportRowValidationRequest>(callBody("/api/import/validate-row"))
        assertEquals(4, request.row.row)
        assertEquals("Checking", request.row.wallet)
        assertEquals("Food", request.row.category)
        assertEquals("dinner", request.row.description)
        assertEquals(listOf(2, 3), request.earlier_rows.map { it.row })
        // Row 3's blank description traveled as null (ADR-0006); row 2's
        // real one stayed.
        assertNull(request.earlier_rows[1].description)
        assertEquals("coffee", request.earlier_rows[0].description)

        // The row flipped in place to the edited values and joined the
        // selection; the sticky bar's counts moved with it.
        val row = draft.preview!!.rows.first { it.row == 4 }
        assertEquals(ImportRowStatus.OK, row.status)
        assertEquals("Checking", row.wallet)
        assertEquals("dinner", row.description)
        assertNull(row.error)
        assertEquals(setOf(2, 4), draft.selected)
        val counts = previewCounts(draft.preview!!)
        assertEquals("2 ready · 1 duplicate · 0 problems", importCountsText(counts.ready, counts.duplicates, counts.problems))
    }

    @Test
    fun `a verified row that stays broken keeps its problem with the narrowed message`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())

        viewModel.openEditor(4)
        viewModel.saveRowEdit(
            editedRowInput(4, TransactionType.EXPENSE, "7.00", "2026-08-03", wallet = "Nope", sourceWallet = "", destinationWallet = "", category = "", description = "dinner", latitude = "", longitude = ""),
        )
        val draft = awaitDraft { it.editingRow == null }
        val row = draft.preview!!.rows.first { it.row == 4 }
        assertEquals(ImportRowStatus.ERROR, row.status)
        assertEquals("Unknown wallet 'Nope'", row.error)
        assertEquals("Nope", row.wallet)
        assertEquals(setOf(2), draft.selected)
    }

    @Test
    fun `verifying a duplicate into a different key flips it to ready`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())

        // Row 3 duplicates an existing transaction; changing its description
        // alone makes its key unique (ADR-0006 — the description is part of
        // the key).
        viewModel.openEditor(3)
        viewModel.saveRowEdit(
            editedRowInput(3, TransactionType.EXPENSE, "5.00", "2026-08-02", wallet = "Checking", sourceWallet = "", destinationWallet = "", category = "", description = "morning", latitude = "", longitude = ""),
        )
        val draft = awaitDraft { it.editingRow == null }
        val row = draft.preview!!.rows.first { it.row == 3 }
        assertEquals(ImportRowStatus.OK, row.status)
        assertEquals("morning", row.description)
        assertEquals(setOf(2, 3), draft.selected)
    }

    @Test
    fun `a later verification sends the earlier rows with their edits applied`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())

        // Edit row 2 (Ready) first: its description changes.
        viewModel.openEditor(2)
        viewModel.saveRowEdit(
            editedRowInput(2, TransactionType.EXPENSE, "12.50", "2026-08-01", wallet = "Checking", sourceWallet = "", destinationWallet = "", category = "Food", description = "breakfast", latitude = "", longitude = ""),
        )
        awaitDraft { it.editingRow == null }

        // Then edit row 4: its earlier_rows must carry row 2's edited value.
        viewModel.openEditor(4)
        viewModel.saveRowEdit(
            editedRowInput(4, TransactionType.EXPENSE, "7.00", "2026-08-03", wallet = "Checking", sourceWallet = "", destinationWallet = "", category = "", description = "dinner", latitude = "", longitude = ""),
        )
        awaitDraft { it.editingRow == null }

        val request = json.decodeFromString<ImportRowValidationRequest>(
            callsFor("/api/import/validate-row").last().body,
        )
        assertEquals("breakfast", request.earlier_rows.first { it.row == 2 }.description)
    }

    @Test
    fun `a failed re-validation call keeps the editor open with its error inline`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        validateStatus = 500
        validateDetail = "boom"

        viewModel.openEditor(4)
        viewModel.saveRowEdit(
            editedRowInput(4, TransactionType.EXPENSE, "7.00", "2026-08-03", wallet = "Checking", sourceWallet = "", destinationWallet = "", category = "", description = "dinner", latitude = "", longitude = ""),
        )
        val draft = awaitDraft { it.editorError != null }
        assertEquals("boom", draft.editorError)
        assertEquals(4, draft.editingRow)
        assertEquals(ImportRowStatus.ERROR, draft.preview!!.rows.first { it.row == 4 }.status)
    }

    @Test
    fun `confirm sends exactly the selected rows in file order and reports the import`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())

        // Fix row 4 and drop row 2 from the selection.
        viewModel.openEditor(4)
        viewModel.saveRowEdit(
            editedRowInput(4, TransactionType.EXPENSE, "7.00", "2026-08-03", wallet = "Checking", sourceWallet = "", destinationWallet = "", category = "", description = "dinner", latitude = "", longitude = ""),
        )
        awaitDraft { it.editingRow == null }
        viewModel.toggle(2)

        viewModel.confirm()
        val draft = awaitDraft { it.phase == ImportPhase.DONE }

        val request = json.decodeFromString<ImportConfirmRequest>(callBody("/api/import/confirm"))
        assertEquals(listOf(4), request.rows.map { it.row })
        assertEquals("Checking", request.rows.single().wallet)
        assertEquals("dinner", request.rows.single().description)
        assertEquals(1, draft.imported)
        assertFalse(draft.createdWithWarning)
        // Nothing selected or sendable was left behind: the fake's database
        // holds exactly the preseeded row plus the one inserted.
        assertEquals(2, stored.size)
    }

    @Test
    fun `a rejected confirm keeps the preview with the batch's detail and nothing imported`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        confirmStatus = 422
        confirmDetail = "Row 2 duplicates an existing transaction"

        viewModel.confirm()
        val draft = awaitDraft { it.error != null }
        assertEquals(ImportPhase.PREVIEW, draft.phase)
        assertEquals("Row 2 duplicates an existing transaction", draft.error)
        assertFalse(draft.busy)
        assertEquals(setOf(2), draft.selected)
        assertEquals(1, stored.size) // nothing was inserted
    }

    @Test
    fun `a second preview after a successful import flags every row as a duplicate`() = runBlocking {
        seedWallet("Checking")
        seedCategory("Food", "expense")
        createViewModel()
        val file = csv(
            "date,type,amount,wallet,source wallet,destination wallet,category,description,location",
            // Row 2's description is blank; row 3's is "coffee". After the
            // import the blank description must match the stored missing
            // one (ADR-0006) and flag the row again.
            "2026-08-10,expense,12.50,Checking,,,Food,,",
            "2026-08-11,expense,5.00,Checking,,,,coffee,",
        )

        pickAndRead(content = file)
        var draft = viewModel.uiState.value.draft!!
        assertEquals(setOf(2, 3), draft.selected)
        assertEquals(listOf(ImportRowStatus.OK, ImportRowStatus.OK), draft.preview!!.rows.map { it.status })

        viewModel.confirm()
        awaitDraft { it.phase == ImportPhase.DONE }
        assertEquals(2, viewModel.uiState.value.draft!!.imported)

        // A successful import is a discard path (the Back button); picking
        // the same file again previews nothing ready.
        viewModel.done()
        assertNull(viewModel.uiState.value.draft)
        viewModel.open()
        viewModel.onFilePicked("rows.csv", file)
        viewModel.readFile()
        draft = awaitDraft { it.phase == ImportPhase.PREVIEW }
        assertEquals(
            listOf(ImportRowStatus.DUPLICATE, ImportRowStatus.DUPLICATE),
            draft.preview!!.rows.map { it.status },
        )
        assertEquals(emptySet<Int>(), draft.selected)
        assertEquals(0, draft.preview!!.ok_count)
        assertEquals(2, draft.preview!!.duplicate_count)
    }

    @Test
    fun `a confirm that made a cash wallet negative reports the warning on the done phase`() = runBlocking {
        seedWallet("Cash")
        createViewModel()
        val file = csv(
            "date,type,amount,wallet,source wallet,destination wallet,category,description,location",
            "2026-08-01,expense,200.00,Cash,,,,",
        )
        pickAndRead(content = file)
        confirmWarning = true

        viewModel.confirm()
        val draft = awaitDraft { it.phase == ImportPhase.DONE }
        assertEquals(1, draft.imported)
        assertTrue(draft.createdWithWarning)
    }

    @Test
    fun `the draft survives a cancelled read and is discarded only by its own paths`() = runBlocking {
        fixtureViewModel()
        // A fresh open starts in the pick phase with nothing picked.
        viewModel.open()
        var draft = viewModel.uiState.value.draft!!
        assertEquals(ImportPhase.PICK, draft.phase)
        assertNull(draft.fileName)

        // Cancel discards the draft entirely.
        viewModel.cancel()
        assertNull(viewModel.uiState.value.draft)

        // Picking a file only fills the pick phase; the read that follows
        // is cancelled mid-flight and its response never lands.
        viewModel.open()
        viewModel.onFilePicked("rows.csv", mixedFile())
        draft = viewModel.uiState.value.draft!!
        assertEquals("rows.csv", draft.fileName)
        assertEquals(mixedFile().size.toLong(), draft.fileSizeBytes)
        val gate = CountDownLatch(1)
        previewGate = gate
        viewModel.readFile()
        viewModel.cancel()
        assertNull(viewModel.uiState.value.draft)
        gate.countDown()
        delay(300)
        assertNull(viewModel.uiState.value.draft)

        // The next open is fresh again.
        viewModel.open()
        draft = viewModel.uiState.value.draft!!
        assertEquals(ImportPhase.PICK, draft.phase)
        assertNull(draft.fileName)
        assertNull(draft.preview)
    }

    @Test
    fun `pick another file abandons the preview for a fresh pick`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        viewModel.openEditor(4)
        viewModel.pickAgain()
        val draft = viewModel.uiState.value.draft!!
        assertEquals(ImportPhase.PICK, draft.phase)
        assertNull(draft.fileName)
        assertNull(draft.preview)
        assertEquals(emptySet<Int>(), draft.selected)
        assertNull(draft.editingRow)
    }

    @Test
    fun `a failed read keeps the pick phase with the backend's detail and the file picked`() = runBlocking {
        fixtureViewModel()
        previewStatus = 422
        previewDetail = "Missing required column(s): date"
        viewModel.open()
        viewModel.onFilePicked("rows.csv", mixedFile())
        viewModel.readFile()
        val draft = awaitDraft { it.error != null }
        assertEquals(ImportPhase.PICK, draft.phase)
        assertEquals("Missing required column(s): date", draft.error)
        assertFalse(draft.busy)
        assertEquals("rows.csv", draft.fileName)
    }

    @Test
    fun `a cancelled editor leaves the row untouched`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        val before = viewModel.uiState.value.draft!!.preview!!.rows.first { it.row == 4 }
        viewModel.openEditor(4)
        viewModel.closeEditor()
        val after = viewModel.uiState.value.draft!!.preview!!.rows.first { it.row == 4 }
        assertEquals(before, after)
        assertNull(viewModel.uiState.value.draft!!.editingRow)
    }

    // --- Batch Revalidation (web issues #76/#78, ticket #27) ----------------

    @Test
    fun `resuming the preview rechecks every problem row in one batch and flips the rows that now pass`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        // While the user was away a Wallet was created that resolves the
        // problem row; the Preview's resume re-checks the problem rows.
        seedWallet("Mystery")
        viewModel.recheckProblems()
        val draft = awaitDraft { it.preview!!.rows.first { row -> row.row == 4 }.status == ImportRowStatus.OK }

        val request = json.decodeFromString<ImportBatchRevalidationRequest>(callBody("/api/import/revalidate-rows"))
        // The whole sendable Draft traveled as the in-file Duplicate
        // context, in file order; only the problem row was a target.
        assertEquals(listOf(2, 3, 4), request.rows.map { it.row })
        assertEquals(listOf(4), request.targets)
        // Row 3's blank description traveled as null (ADR-0006).
        assertNull(request.rows.first { it.row == 3 }.description)
        // The row flipped in place — fields untouched — and joined the
        // selection; the counts moved with it.
        val row = draft.preview!!.rows.first { it.row == 4 }
        assertEquals(ImportRowStatus.OK, row.status)
        assertEquals("Mystery", row.wallet)
        assertNull(row.error)
        assertEquals(setOf(2, 4), draft.selected)
        val counts = previewCounts(draft.preview!!)
        assertEquals("2 ready · 1 duplicate · 0 problems", importCountsText(counts.ready, counts.duplicates, counts.problems))
        // The computation endpoint never bumped the data version (ADR-0002):
        // only the one batch call hit the wire.
        assertEquals(1, callsFor("/api/import/revalidate-rows").size)
    }

    @Test
    fun `a problem row that now duplicates flips to duplicate and stays unselected`() = runBlocking {
        fixtureViewModel()
        // Row 4's wallet is missing at preview time, so the row previews as
        // a Problem — even though its key matches the seeded transaction.
        seedStored(StoredTransaction("expense", "2026-08-03", "7.00", "Mystery"))
        pickAndRead(content = mixedFile())
        assertEquals(ImportRowStatus.ERROR, viewModel.uiState.value.draft!!.preview!!.rows.first { it.row == 4 }.status)

        seedWallet("Mystery")
        viewModel.recheckProblems()
        val draft = awaitDraft { it.preview!!.rows.first { row -> row.row == 4 }.status == ImportRowStatus.DUPLICATE }
        val row = draft.preview!!.rows.first { it.row == 4 }
        assertEquals(ImportRowStatus.DUPLICATE, row.status)
        assertNull(row.error)
        // A Duplicate is never selectable: it joined neither the selection
        // nor the ready count.
        assertEquals(setOf(2), draft.selected)
    }

    @Test
    fun `the batch recheck narrows a still-broken row's message`() = runBlocking {
        fixtureViewModel()
        val file = csv(
            "date,type,amount,wallet,source wallet,destination wallet,category,description,location",
            "2026-08-03,expense,7.00,Mystery,,,Neverland,,",
        )
        pickAndRead(content = file)
        var draft = viewModel.uiState.value.draft!!
        assertEquals("Unknown wallet 'Mystery'", draft.preview!!.rows.first().error)

        // Creating the missing Wallet resolves the wallet half; the row is
        // still broken, narrowed to its missing Category.
        seedWallet("Mystery")
        viewModel.recheckProblems()
        draft = awaitDraft {
            it.preview!!.rows.first { row -> row.row == 2 }.error == "Unknown expense category 'Neverland'"
        }
        val row = draft.preview!!.rows.first { it.row == 2 }
        assertEquals(ImportRowStatus.ERROR, row.status)
        assertEquals(emptySet<Int>(), draft.selected)
    }

    @Test
    fun `a batch recheck with no problem rows never calls the endpoint`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        // Row 4 was already fixed through the editor; nothing is broken.
        viewModel.openEditor(4)
        viewModel.saveRowEdit(
            editedRowInput(4, TransactionType.EXPENSE, "7.00", "2026-08-03", wallet = "Checking", sourceWallet = "", destinationWallet = "", category = "", description = "dinner", latitude = "", longitude = ""),
        )
        awaitDraft { it.editingRow == null }

        viewModel.recheckProblems()
        val draft = viewModel.uiState.value.draft!!
        assertEquals(setOf(2, 4), draft.selected)
        assertEquals(ImportRowStatus.OK, draft.preview!!.rows.first { it.row == 4 }.status)
        assertEquals(0, callsFor("/api/import/revalidate-rows").size)
    }

    @Test
    fun `a failed batch recheck surfaces the flow error and leaves the rows`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        seedWallet("Mystery")
        revalidateStatus = 500
        revalidateDetail = "boom"

        viewModel.recheckProblems()
        val draft = awaitDraft { it.error != null }
        assertEquals("boom", draft.error)
        val row = draft.preview!!.rows.first { it.row == 4 }
        assertEquals(ImportRowStatus.ERROR, row.status)
        assertEquals("Unknown wallet 'Mystery'", row.error)
        assertEquals(setOf(2), draft.selected)
    }

    @Test
    fun `a stale batch verdict never clobbers a row the editor has since flipped`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        // The resume re-check is in flight — its verdicts computed against
        // the unresolved row — while the user fixes the row in the editor.
        val gate = CountDownLatch(1)
        revalidateGate = gate
        viewModel.recheckProblems()
        viewModel.openEditor(4)
        viewModel.saveRowEdit(
            editedRowInput(4, TransactionType.EXPENSE, "7.00", "2026-08-03", wallet = "Checking", sourceWallet = "", destinationWallet = "", category = "", description = "dinner", latitude = "", longitude = ""),
        )
        awaitDraft { it.editingRow == null && it.preview!!.rows.first { row -> row.row == 4 }.status == ImportRowStatus.OK }
        gate.countDown()

        // The batch's stale verdict arrives after the editor's own; the row
        // keeps the editor's flip and edited fields.
        awaitDraft { callsFor("/api/import/revalidate-rows").size == 1 }
        val row = viewModel.uiState.value.draft!!.preview!!.rows.first { it.row == 4 }
        assertEquals(ImportRowStatus.OK, row.status)
        assertEquals("Checking", row.wallet)
        assertEquals("dinner", row.description)
        assertNull(row.error)
        assertEquals(setOf(2, 4), viewModel.uiState.value.draft!!.selected)
    }

    @Test
    fun `a trigger arriving while a batch is in flight queues one more recheck`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        // The resume re-check is in flight; its verdicts — computed against
        // the still-missing Wallet — leave the row broken.
        val computed = CountDownLatch(1)
        val release = CountDownLatch(1)
        revalidateComputed = computed
        revalidateGate = release
        viewModel.recheckProblems()
        assertTrue(computed.await(5, TimeUnit.SECONDS))

        // A second trigger arrives while the first is out: it is not lost —
        // it queues one more full re-check for when the first finishes.
        viewModel.recheckProblems()
        // The Wallet resolves before the first response is released; the
        // queued follow-up — not the stale first verdict — flips the row.
        seedWallet("Mystery")
        release.countDown()

        val draft = awaitDraft { it.preview!!.rows.first { row -> row.row == 4 }.status == ImportRowStatus.OK }
        assertEquals(2, callsFor("/api/import/revalidate-rows").size)
        assertEquals(setOf(2, 4), draft.selected)
        assertNull(draft.error)
    }

    // --- Inline creation from the row editor (ADR-0013/0014, ticket #27) ---

    @Test
    fun `a wallet created from the row editor is real at once, auto-selected, and every waiting row revalidates in a batch`() = runBlocking {
        fixtureViewModel()
        // Two problem rows wait on the same missing Wallet: row 4's Wallet
        // field names it, row 5's Transfer From leg names it.
        val file = csv(
            "date,type,amount,wallet,source wallet,destination wallet,category,description,location",
            "2026-08-01,expense,12.50,Checking,,,Food,,",
            "2026-08-02,expense,5.00,Checking,,,,",
            "2026-08-03,expense,7.00,Mystery,,,,",
            "2026-08-04,transfer,3.00,,Mystery,Cash,,,",
        )
        pickAndRead(content = file)
        assertEquals(
            listOf(ImportRowStatus.OK, ImportRowStatus.DUPLICATE, ImportRowStatus.ERROR, ImportRowStatus.ERROR),
            viewModel.uiState.value.draft!!.preview!!.rows.map { it.status },
        )

        viewModel.openEditor(4)
        viewModel.onRowWalletAdd(WalletFieldTarget.WALLET, "Mystery")
        var draft = viewModel.uiState.value.draft!!
        val opened = draft.rowWalletCreate
        assertNotNull(opened)
        // The create form is prefilled with the file's missing name, and an
        // Expense/Income row's Wallet field locks Contact out (ADR-0017).
        assertEquals(WalletFieldTarget.WALLET, opened?.target)
        assertEquals(setOf(WalletType.CHECKING, WalletType.CREDIT_CARD, WalletType.CASH), opened?.allowedTypes)
        assertEquals("Mystery", opened?.modal?.name)

        viewModel.submitRowWalletCreate()
        draft = awaitDraft {
            it.rowWalletCreate == null &&
                it.preview!!.rows.filter { row -> row.row in setOf(4, 5) }
                    .all { row -> row.status == ImportRowStatus.OK }
        }
        // Created through the shared endpoint — real at once (ADR-0014).
        val create = json.decodeFromString<WalletCreateRequest>(callBody("/api/wallets"))
        assertEquals("Mystery", create.name)
        assertEquals(WalletType.CHECKING, create.type)
        assertEquals("0.00", create.opening_balance)
        // The name is reported back to the open editor's originating field;
        // the editor itself stays open — and its row flips behind it, even
        // though the editor has not saved (web issue #78).
        assertEquals(RowWalletToSelect("Mystery", WalletFieldTarget.WALLET), draft.rowWalletToSelect)
        assertEquals(4, draft.editingRow)
        // The batch re-validation carried the whole Draft and targeted both
        // problem rows that waited on the created Wallet.
        val request = json.decodeFromString<ImportBatchRevalidationRequest>(callBody("/api/import/revalidate-rows"))
        assertEquals(listOf(2, 3, 4, 5), request.rows.map { it.row })
        assertEquals(listOf(4, 5), request.targets)
        assertEquals(setOf(2, 4, 5), draft.selected)
        // Closing the editor clears the pending auto-select (a stale name is
        // never applied to an editor opened later).
        viewModel.closeEditor()
        assertNull(viewModel.uiState.value.draft!!.rowWalletToSelect)
        assertNull(viewModel.uiState.value.draft!!.editingRow)
    }

    @Test
    fun `a transfer's From sentinel creates with all four wallet types and selects into From`() = runBlocking {
        fixtureViewModel()
        val file = csv(
            "date,type,amount,wallet,source wallet,destination wallet,category,description,location",
            "2026-08-03,transfer,10.00,,Mystery,Cash,,,",
        )
        pickAndRead(content = file)
        viewModel.openEditor(2)
        viewModel.onRowWalletAdd(WalletFieldTarget.SOURCE, "Mystery")
        val opened = viewModel.uiState.value.draft!!.rowWalletCreate
        // A Transfer's From/To may create a Contact Wallet: nothing locked.
        assertNull(opened?.allowedTypes)
        assertEquals("Mystery", opened?.modal?.name)

        viewModel.onRowWalletCreateTypeChange(WalletType.CONTACT)
        viewModel.submitRowWalletCreate()
        val draft = awaitDraft {
            it.rowWalletCreate == null &&
                it.preview!!.rows.first { row -> row.row == 2 }.status == ImportRowStatus.OK
        }
        val create = json.decodeFromString<WalletCreateRequest>(callBody("/api/wallets"))
        assertEquals(WalletType.CONTACT, create.type)
        assertEquals("0.00", create.opening_balance)
        assertEquals(RowWalletToSelect("Mystery", WalletFieldTarget.SOURCE), draft.rowWalletToSelect)
        assertEquals(setOf(2), draft.selected)
    }

    @Test
    fun `a category created from an income row locks to income and its rows revalidate`() = runBlocking {
        fixtureViewModel()
        val file = csv(
            "date,type,amount,wallet,source wallet,destination wallet,category,description,location",
            "2026-08-04,income,50.00,Checking,,,Bonus,,",
        )
        pickAndRead(content = file)
        assertEquals("Unknown income category 'Bonus'", viewModel.uiState.value.draft!!.preview!!.rows.first().error)

        viewModel.openEditor(2)
        viewModel.onRowCategoryAdd(CategoryType.INCOME, "Bonus")
        val opened = viewModel.uiState.value.draft!!.rowCategoryCreate
        assertNotNull(opened)
        assertEquals(CategoryType.INCOME, opened?.lockedType)
        assertEquals(CategoryType.INCOME, opened?.modal?.type)
        assertEquals("Bonus", opened?.modal?.name)

        viewModel.submitRowCategoryCreate()
        val draft = awaitDraft {
            it.rowCategoryCreate == null &&
                it.preview!!.rows.first { row -> row.row == 2 }.status == ImportRowStatus.OK
        }
        val create = json.decodeFromString<CategoryCreateRequest>(callBody("/api/categories"))
        assertEquals("Bonus", create.name)
        assertEquals(CategoryType.INCOME, create.type)
        assertEquals("Bonus", draft.rowCategoryToSelect)
        val request = json.decodeFromString<ImportBatchRevalidationRequest>(callBody("/api/import/revalidate-rows"))
        assertEquals(listOf(2), request.targets)
        assertEquals(setOf(2), draft.selected)
    }

    @Test
    fun `a failed wallet create keeps the create form open with the conflict copy`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())
        walletCreateStatus = 409
        walletCreateDetail = "A wallet with this name already exists"

        viewModel.openEditor(4)
        viewModel.onRowWalletAdd(WalletFieldTarget.WALLET, "Mystery")
        viewModel.submitRowWalletCreate()
        val draft = awaitDraft { it.rowWalletCreate?.modal?.error != null }
        // The shared 409 mapping surfaces the web's conflict copy inside the
        // stacked form; the row editor's draft is untouched beneath it.
        assertEquals("A wallet with this name already exists.", draft.rowWalletCreate?.modal?.error)
        assertFalse(draft.rowWalletCreate!!.modal.submitting)
        assertEquals(4, draft.editingRow)
        assertEquals(0, callsFor("/api/import/revalidate-rows").size)
    }

    @Test
    fun `cancelling the stacked create form leaves the editor and the draft untouched`() = runBlocking {
        fixtureViewModel()
        pickAndRead(content = mixedFile())

        viewModel.openEditor(4)
        viewModel.onRowWalletAdd(WalletFieldTarget.WALLET, "Mystery")
        viewModel.onRowCategoryAdd(CategoryType.EXPENSE, "Nope") // blocked: the wallet form is on top
        assertNotNull(viewModel.uiState.value.draft!!.rowWalletCreate)
        assertNull(viewModel.uiState.value.draft!!.rowCategoryCreate)

        viewModel.cancelRowWalletCreate()
        var draft = viewModel.uiState.value.draft!!
        // Only the stacked form closed: the editor stays open, its row
        // untouched, nothing on the wire.
        assertNull(draft.rowWalletCreate)
        assertEquals(4, draft.editingRow)
        assertEquals(ImportRowStatus.ERROR, draft.preview!!.rows.first { it.row == 4 }.status)

        // The Category form mirrors the contract.
        viewModel.onRowCategoryAdd(CategoryType.EXPENSE, "Nope")
        assertNotNull(viewModel.uiState.value.draft!!.rowCategoryCreate)
        viewModel.cancelRowCategoryCreate()
        draft = viewModel.uiState.value.draft!!
        assertNull(draft.rowCategoryCreate)
        assertEquals(4, draft.editingRow)
        assertEquals(0, callsFor("/api/import/revalidate-rows").size)
        assertEquals(0, callsFor("/api/wallets").size)
        assertEquals(0, callsFor("/api/categories").size)
    }
}
