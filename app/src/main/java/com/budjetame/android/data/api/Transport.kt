package com.budjetame.android.data.api

import com.budjetame.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Converter
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

/**
 * The global cache clock (ADR-0002, mirroring the web app's ADR-0022): every
 * successful write through the transport bumps the version, and screens
 * re-fetch in the background when it changes. Failed writes never bump.
 */
object DataVersion {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun bump() {
        _version.update { it + 1 }
    }

    fun current(): Long = _version.value
}

/**
 * A non-OK API response, carrying the backend's `detail` when it had one —
 * a string, or the `message` field of a structured detail (e.g. the category
 * merge conflict, ADR-0007 in the web repo). `detailObject` keeps the whole
 * structured detail readable for typed conflicts like the merge offer.
 */
open class ApiException(
    val status: Int,
    val detail: String?,
    val detailObject: JsonObject? = null,
    message: String,
) : Exception(message)

/**
 * Human message for an API failure, shared by every screen (ported from the
 * web transport's `apiErrorMessage`): the status codes are part of the API
 * contract (409 duplicate name, 422 validation).
 */
fun apiErrorMessage(status: Int?, conflictMessage: String, fallback: String): String = when (status) {
    409 -> conflictMessage
    422 -> "Check the fields and try again."
    else -> fallback
}

/** Map a Retrofit HttpException to an ApiException with the parsed detail. */
fun HttpException.toApiException(): ApiException {
    val response = response()
    val detail = parseDetail(response?.errorBody())
    return ApiException(
        status = code(),
        detail = detail.message,
        detailObject = detail.detailObject,
        message = detail.message ?: "Request failed (${code()})",
    )
}

/** Map a non-2xx raw-body response to an ApiException with the parsed
 * detail. Retrofit throws HttpException only for typed JSON endpoints;
 * an endpoint whose body is not JSON (the export's .xlsx) returns a
 * `Response` instead, so the repository maps it here — the same
 * `detail` parsing and the same message as the HttpException mapping. */
fun Response<*>.toApiException(): ApiException {
    val detail = parseDetail(errorBody())
    return ApiException(
        status = code(),
        detail = detail.message,
        detailObject = detail.detailObject,
        message = detail.message ?: "Request failed (${code()})",
    )
}

/** One parsed `detail`: its message and the raw object, from one body read. */
private data class ParsedDetail(val message: String?, val detailObject: JsonObject?)

private fun parseDetail(body: ResponseBody?): ParsedDetail {
    val content = body?.string() ?: return ParsedDetail(null, null)
    return try {
        val detail = Json.parseToJsonElement(content).jsonObject["detail"] ?: return ParsedDetail(null, null)
        when (detail) {
            is JsonPrimitive -> if (detail.isString) ParsedDetail(detail.content, null) else ParsedDetail(null, null)
            is JsonObject -> ParsedDetail(detailMessage(detail), detail)
            else -> ParsedDetail(null, null)
        }
    } catch (_: Exception) {
        ParsedDetail(null, null)
    }
}

private fun detailMessage(detail: JsonObject): String? {
    val message = detail["message"]
    return if (message is JsonPrimitive && message.isString) message.content else null
}

/**
 * The shared API transport: one place that knows the base URL, the bearer
 * token, the write-bump rule (ADR-0002), and JSON configuration. Resource
 * modules go through the Retrofit services this client creates.
 */
class ApiClient(baseUrl: String, tokenProvider: () -> String?) {

    val json = Json {
        ignoreUnknownKeys = true
        // Nulls are encodable so PATCH bodies can send an explicit null
        // ("clear this field"); default-valued fields are still omitted.
        explicitNulls = true
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val token = tokenProvider()
            val request = if (token != null) {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // ADR-0002: bump after successful writes only. The import
            // pipeline's computation endpoints speak POST but write nothing,
            // so they are exempt — without the exemption every row edit
            // during Verification would look like a write.
            val isExempt = request.url.encodedPath in WRITE_EXEMPT_PATHS
            if (response.isSuccessful && request.method != "GET" && !isExempt) {
                DataVersion.bump()
            }
            response
        }
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(httpClient)
        .addConverterFactory(
            // Hand-rolled kotlinx.serialization converter: the published
            // converter artifact's classes don't resolve under Kotlin 2.4.
            JsonConverterFactory(json),
        )
        .build()

    fun <T> create(service: Class<T>): T = retrofit.create(service)

    companion object {
        private val WRITE_EXEMPT_PATHS = setOf(
            "/api/import/preview",
            "/api/import/validate-row",
            "/api/import/revalidate-rows",
        )
    }
}

/** A minimal Retrofit converter on kotlinx.serialization's Json. */
private class JsonConverterFactory(private val json: Json) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
        if (type == Unit::class.java || type == Void.TYPE) return null
        val serializer = serializer(type)
        return Converter<ResponseBody, Any> { body ->
            json.decodeFromString(serializer, body.string())
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? {
        if (type == Unit::class.java || type == Void.TYPE) return null
        val serializer = serializer(type)
        return Converter<Any, RequestBody> { value ->
            json.encodeToString(serializer, value).toRequestBody(JSON_MEDIA_TYPE)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
