package com.budjetame.android.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.apiErrorMessage
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.category.CategoryMergeConflict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The preset colors the form offers, ported from the web app's CategoryForm. */
val CATEGORY_PRESET_COLORS = listOf(
    "#ef4444",
    "#f97316",
    "#f59e0b",
    "#84cc16",
    "#10b981",
    "#06b6d4",
    "#3b82f6",
    "#6366f1",
    "#a855f7",
    "#ec4899",
)

/**
 * The Categories screen's state machine (ticket #16), ported from the web
 * app's CategoriesScreen + CategoryForm: load with sections and search,
 * create/edit/delete, and the rename-collision merge flow (ADR-0007) — a
 * structured 409 becomes a confirmation offer carrying the transaction
 * count, and the confirmed merge calls POST /categories/{id}/merge. Data is
 * refetched in the background when the global data version bumps (ADR-0002).
 */
class CategoriesViewModel(private val categories: CategoryGateway) : ViewModel() {

    /** The merge offer (ADR-0007): set when a rename save collided. */
    data class MergeOffer(val targetId: Int, val transactionCount: Int)

    /** The create/edit modal's draft (null = modal closed). */
    data class ModalState(
        val category: CategoryDto? = null,
        val name: String = "",
        val type: CategoryType = CategoryType.EXPENSE,
        val icon: String = "",
        val color: String = CATEGORY_PRESET_COLORS.first(),
        val error: String? = null,
        val submitting: Boolean = false,
        val confirmingDelete: Boolean = false,
        val deleting: Boolean = false,
        val mergeOffer: MergeOffer? = null,
        val confirmingMerge: Boolean = false,
        val merging: Boolean = false,
    ) {
        val editing: Boolean get() = category != null

        val busy: Boolean get() = submitting || deleting || merging

        val canSubmit: Boolean get() = !busy && name.isNotBlank()
    }

    data class UiState(
        val loading: Boolean = true,
        val loadError: String? = null,
        val categories: List<CategoryDto> = emptyList(),
        val query: String = "",
        val modal: ModalState? = null,
    ) {
        val sections: List<CategorySection> get() = categorySections(categories, query)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // ADR-0002: the transport bumps the data version after every write, and
        // this screen refetches in the background. The first emission (the
        // current version) is the initial load.
        viewModelScope.launch {
            DataVersion.version.collect { reload() }
        }
    }

    fun openCreate() {
        _uiState.update { it.copy(modal = ModalState()) }
    }

    fun openEdit(category: CategoryDto) {
        _uiState.update {
            it.copy(
                modal = ModalState(
                    category = category,
                    name = category.name,
                    icon = category.icon ?: "",
                    color = category.color,
                ),
            )
        }
    }

    fun closeModal() {
        _uiState.update { it.copy(modal = null) }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun onNameChange(value: String) = updateModal {
        // A new name invalidates the offer: it was about the collision the
        // user just typed (web CategoryForm).
        it.copy(name = value, error = null, mergeOffer = null, confirmingMerge = false)
    }

    fun onTypeChange(value: CategoryType) = updateModal { it.copy(type = value, error = null) }

    fun onIconChange(value: String) = updateModal { it.copy(icon = value, error = null) }

    fun onColorChange(value: String) = updateModal { it.copy(color = value, error = null) }

    fun submit() {
        val modal = _uiState.value.modal ?: return
        if (!modal.canSubmit) return
        if (modal.editing) update(modal) else create(modal)
    }

    fun onDeleteTap() {
        val modal = _uiState.value.modal ?: return
        if (!modal.editing || modal.busy) return
        if (!modal.confirmingDelete) {
            updateModal { it.copy(confirmingDelete = true, error = null) }
            return
        }
        val category = modal.category ?: return
        viewModelScope.launch {
            updateModal { it.copy(deleting = true, error = null) }
            try {
                categories.deleteCategory(category.id)
                _uiState.update { state ->
                    state.copy(
                        categories = state.categories.filterNot { it.id == category.id },
                        modal = null,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        confirmingDelete = false,
                        deleting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A category with this name already exists.",
                            "Could not delete the category.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal {
                    it.copy(confirmingDelete = false, deleting = false, error = "Could not delete the category.")
                }
            }
        }
    }

    fun onMergeTap() {
        val modal = _uiState.value.modal ?: return
        val offer = modal.mergeOffer ?: return
        val category = modal.category ?: return
        if (modal.busy) return
        if (!modal.confirmingMerge) {
            updateModal { it.copy(confirmingMerge = true, error = null) }
            return
        }
        viewModelScope.launch {
            updateModal { it.copy(merging = true, error = null) }
            try {
                val surviving = categories.mergeCategory(category.id, offer.targetId)
                _uiState.update { state ->
                    state.copy(
                        // The renamed Category is gone; the survivor replaces
                        // its own row in place (web CategoriesScreen).
                        categories = state.categories
                            .filterNot { it.id == category.id }
                            .map { if (it.id == surviving.id) surviving else it },
                        modal = null,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        confirmingMerge = false,
                        merging = false,
                        error = apiErrorMessage(
                            error.status,
                            "A category with this name already exists.",
                            "Could not merge the categories.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal {
                    it.copy(confirmingMerge = false, merging = false, error = "Could not merge the categories.")
                }
            }
        }
    }

    fun cancelMerge() = updateModal {
        it.copy(mergeOffer = null, confirmingMerge = false, error = null)
    }

    fun retry() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadError = null) }
            reload()
        }
    }

    private fun create(modal: ModalState) {
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            try {
                val created = categories.createCategory(modal.name.trim(), modal.type, modal.icon, modal.color)
                _uiState.update { state ->
                    state.copy(categories = state.categories + created, modal = null)
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A category with this name already exists.",
                            "Could not create the category.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = "Could not create the category.") }
            }
        }
    }

    private fun update(modal: ModalState) {
        val category = modal.category ?: return
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            try {
                val saved = categories.updateCategory(category.id, modal.name.trim(), modal.icon, modal.color)
                _uiState.update { state ->
                    state.copy(
                        categories = state.categories.map { if (it.id == saved.id) saved else it },
                        modal = null,
                    )
                }
            } catch (error: CategoryMergeConflict) {
                // The collision is a merge offer, not an error (web issue #45).
                updateModal {
                    it.copy(
                        submitting = false,
                        mergeOffer = MergeOffer(error.targetId, error.transactionCount),
                        confirmingMerge = false,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A category with this name already exists.",
                            "Could not save the category.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = "Could not save the category.") }
            }
        }
    }

    /**
     * Fetch the list. A failed background refetch keeps the held data on
     * screen (ADR-0002); a failure with nothing to show surfaces the error.
     */
    private suspend fun reload() {
        try {
            val loaded = categories.fetchCategories()
            _uiState.update { it.copy(categories = loaded, loadError = null, loading = false) }
        } catch (_: Exception) {
            _uiState.update { state ->
                if (state.categories.isEmpty()) {
                    state.copy(loadError = "Could not load your categories.", loading = false)
                } else {
                    state.copy(loading = false)
                }
            }
        }
    }

    private fun updateModal(transform: (ModalState) -> ModalState) {
        _uiState.update { state ->
            state.modal?.let { state.copy(modal = transform(it)) } ?: state
        }
    }
}
