package com.budjetame.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function unit tests for [ErrorCatalogue].
 * Validates mapping logic without depending on Android R constant values.
 */
class ErrorCatalogueTest {

    // --- Known errors return non-null IDs ---

    @Test
    fun `unknown wallet returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Unknown wallet 'X'"))
    }

    @Test
    fun `wallet not found returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("wallet not found: 42"))
    }

    @Test
    fun `wallet is frozen returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Wallet is frozen"))
    }

    @Test
    fun `balance not zero returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Balance is not zero"))
    }

    @Test
    fun `unknown category returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("unknown category 'Groceries'"))
    }

    @Test
    fun `category not found returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Category not found: 7"))
    }

    @Test
    fun `name already taken returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Name already taken"))
    }

    @Test
    fun `already exists returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("already exists"))
    }

    @Test
    fun `undo not available returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Undo not available"))
    }

    @Test
    fun `already undone returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("already undone"))
    }

    @Test
    fun `occurrence already paid returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Occurrence already paid"))
    }

    @Test
    fun `cannot restore pin returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Cannot restore pin"))
    }

    @Test
    fun `income on contact returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Cannot record income on contact wallet"))
    }

    @Test
    fun `same source and destination returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Cannot have same source and destination"))
    }

    @Test
    fun `wallet must differ returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Wallet must be different"))
    }

    @Test
    fun `cash negative returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Negative cash wallet not allowed"))
    }

    @Test
    fun `validation failed returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Validation failed: amount is required"))
    }

    @Test
    fun `unknown recurring cost returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Unknown recurring cost"))
    }

    @Test
    fun `occurrence not found returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Occurrence not found"))
    }

    @Test
    fun `recurring definition not found returns a resource id`() {
        assertNotNull(ErrorCatalogue.resIdFor("Recurring definition not found"))
    }

    // --- Fallback behaviour ---

    @Test
    fun `unknown detail returns null`() {
        assertNull(ErrorCatalogue.resIdFor("Some completely unknown error string"))
    }

    @Test
    fun `null detail returns null`() {
        assertNull(ErrorCatalogue.resIdFor(null))
    }

    // --- Consistency: same input → same output ---

    @Test
    fun `same error detail maps to the same id`() {
        assertEquals(
            ErrorCatalogue.resIdFor("Unknown wallet 'X'"),
            ErrorCatalogue.resIdFor("Unknown wallet 'X'"),
        )
    }

    @Test
    fun `same error detail with different casing maps to the same id`() {
        assertEquals(
            ErrorCatalogue.resIdFor("Unknown wallet 'X'"),
            ErrorCatalogue.resIdFor("UNKNOWN WALLET 'X'"),
        )
    }

    @Test
    fun `same error detail name already taken maps consistently`() {
        assertEquals(
            ErrorCatalogue.resIdFor("Name already taken"),
            ErrorCatalogue.resIdFor("Name already taken"),
        )
    }

    // --- Helper methods ---

    @Test
    fun `knows returns true for known errors`() {
        assertTrue(ErrorCatalogue.knows("Unknown wallet 'abc'"))
        assertTrue(ErrorCatalogue.knows("Name already taken"))
    }

    @Test
    fun `knows returns false for unknown errors`() {
        assertTrue(!ErrorCatalogue.knows("Something unexpected happened"))
    }

    @Test
    fun `knows returns false for null`() {
        assertTrue(!ErrorCatalogue.knows(null))
    }

    @Test
    fun `map is alias for resIdFor`() {
        assertEquals(ErrorCatalogue.resIdFor("Unknown wallet 'X'"), ErrorCatalogue.map("Unknown wallet 'X'"))
        assertNull(ErrorCatalogue.map(null))
    }

    // --- Case insensitivity ---

    @Test
    fun `case insensitive matching for wallet errors`() {
        assertEquals(
            ErrorCatalogue.resIdFor("Unknown wallet 'X'"),
            ErrorCatalogue.resIdFor("UNKNOWN WALLET 'X'"),
        )
    }

    @Test
    fun `case insensitive matching for name taken`() {
        assertEquals(
            ErrorCatalogue.resIdFor("Name already taken"),
            ErrorCatalogue.resIdFor("NAME ALREADY TAKEN"),
        )
    }

    @Test
    fun `case insensitive matching for validation`() {
        assertEquals(
            ErrorCatalogue.resIdFor("Validation failed"),
            ErrorCatalogue.resIdFor("Validation Failed"),
        )
    }

    // --- Most-specific-first matching ---

    @Test
    fun `recurring cost maps before generic cost error`() {
        val specificId = ErrorCatalogue.resIdFor("Unknown recurring cost 42")
        val genericId = ErrorCatalogue.resIdFor("Unknown recurring cost")
        // Both should match the same prefix
        assertNotNull(specificId)
        assertEquals(specificId, genericId)
    }

    // --- Prefix matching ---

    @Test
    fun `error with extra detail after known prefix still matches`() {
        assertNotNull(ErrorCatalogue.resIdFor("Unknown wallet 'MyWallet' was not found in this account"))
    }
}