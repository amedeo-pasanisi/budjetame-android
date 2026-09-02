package com.budjetame.android.data.category

import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.CategoryApi
import com.budjetame.android.data.api.CategoryCreateRequest
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryMergeRequest
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.CategoryUpdateRequest
import com.budjetame.android.data.api.toApiException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import retrofit2.HttpException

/** The category operations screens call (UI-independent). */
interface CategoryGateway {
    suspend fun fetchCategories(): List<CategoryDto>
    suspend fun createCategory(name: String, type: CategoryType, icon: String, color: String): CategoryDto
    suspend fun updateCategory(id: Int, name: String, icon: String, color: String): CategoryDto
    suspend fun mergeCategory(id: Int, targetId: Int): CategoryDto
    suspend fun deleteCategory(id: Int)
}

/**
 * A colliding rename, surfaced as a typed error so the form can offer the
 * merge instead of failing (ADR-0007 in the web repo): the surviving
 * Category's id and how many Transactions would move.
 */
class CategoryMergeConflict(
    val targetId: Int,
    val transactionCount: Int,
) : ApiException(
    status = 409,
    detail = "A Category with this name already exists",
    message = "A Category with this name already exists",
)

/** The API-backed CategoryGateway (web issue #17). */
class ApiCategoryRepository(private val api: CategoryApi) : CategoryGateway {

    override suspend fun fetchCategories(): List<CategoryDto> =
        call { api.list() }

    override suspend fun createCategory(
        name: String,
        type: CategoryType,
        icon: String,
        color: String,
    ): CategoryDto =
        call {
            api.create(CategoryCreateRequest(name = name, type = type, icon = icon, color = color))
        }

    override suspend fun updateCategory(
        id: Int,
        name: String,
        icon: String,
        color: String,
    ): CategoryDto = try {
        api.update(id, CategoryUpdateRequest(name = name, icon = icon, color = color))
    } catch (error: HttpException) {
        throw error.toMergeAwareApiException()
    }

    override suspend fun mergeCategory(id: Int, targetId: Int): CategoryDto =
        call { api.merge(id, CategoryMergeRequest(target_id = targetId)) }

    override suspend fun deleteCategory(id: Int) {
        call { api.delete(id) }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}

/**
 * The structured 409 a colliding rename answers is the merge offer
 * (ADR-0007): it carries `target_id` and `transaction_count`. A plain-string
 * 409 (the unique-index race) and any other failure stay plain ApiExceptions.
 */
private fun HttpException.toMergeAwareApiException(): ApiException {
    val apiError = toApiException()
    val detail = apiError.detailObject ?: return apiError
    val targetId = (detail["target_id"] as? JsonPrimitive)?.intOrNull
    val transactionCount = (detail["transaction_count"] as? JsonPrimitive)?.intOrNull
    return if (apiError.status == 409 && targetId != null && transactionCount != null) {
        CategoryMergeConflict(targetId, transactionCount)
    } else {
        apiError
    }
}
