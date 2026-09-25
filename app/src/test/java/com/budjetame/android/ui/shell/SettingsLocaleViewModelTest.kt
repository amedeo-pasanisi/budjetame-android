package com.budjetame.android.ui.shell

import com.budjetame.android.data.auth.LocaleGateway
import com.budjetame.android.util.AppLocale
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings locale picker ViewModel (i18n, ticket #59): the seam is a fake
 * [LocaleGateway] that tracks calls.
 */
class SettingsLocaleViewModelTest {

    private class FakeLocaleGateway : LocaleGateway {
        var stored: String? = null
        val updates = mutableListOf<String>()

        override suspend fun fetchLocale(): String? = stored
        override suspend fun updateLocale(tag: String) {
            stored = tag
            updates.add(tag)
        }
    }

    private lateinit var gateway: FakeLocaleGateway

    @Before
    fun setUp() {
        AppLocale.current = Locale.US
        gateway = FakeLocaleGateway()
    }

    @After
    fun tearDown() {
        AppLocale.current = Locale.US
    }

    private fun createViewModel(): SettingsLocaleViewModel =
        SettingsLocaleViewModel(gateway)

    @Test
    fun `init fetches the stored locale and sets the state`() = runBlocking {
        gateway.stored = "it"
        val vm = createViewModel()

        withTimeout(5_000) {
            vm.state.first { it.currentLocale == "it" }
        }
        assertEquals("it", vm.state.value.currentLocale)
    }

    @Test
    fun `init defaults to en when no locale is stored`() = runBlocking {
        val vm = createViewModel()

        withTimeout(5_000) {
            vm.state.first { it.currentLocale == "en" }
        }
        assertEquals("en", vm.state.value.currentLocale)
    }

    @Test
    fun `selectLocale calls the gateway and updates the state`() = runBlocking {
        val vm = createViewModel()
        // Wait for init to settle on "en"
        withTimeout(5_000) { vm.state.first { it.currentLocale == "en" } }

        vm.selectLocale("it")
        withTimeout(5_000) { vm.state.first { it.currentLocale == "it" } }

        assertEquals("it", vm.state.value.currentLocale)
        assertEquals(listOf("it"), gateway.updates)
        assertEquals(Locale.ITALY, AppLocale.current)
    }

    @Test
    fun `selectLocale does nothing when the tag is unchanged`() = runBlocking {
        val vm = createViewModel()
        withTimeout(5_000) { vm.state.first { it.currentLocale == "en" } }

        vm.selectLocale("en")
        // No API call and no state change
        assertTrue(gateway.updates.isEmpty())
        assertEquals("en", vm.state.value.currentLocale)
    }

    @Test
    fun `a gateway error sets the error state`() = runBlocking {
        val failingGateway = object : LocaleGateway {
            override suspend fun fetchLocale(): String? = null
            override suspend fun updateLocale(tag: String) {
                throw RuntimeException("Network error")
            }
        }
        val vm = SettingsLocaleViewModel(failingGateway)

        withTimeout(5_000) { vm.state.first { it.currentLocale == "en" } }

        vm.selectLocale("it")
        withTimeout(5_000) { vm.state.first { it.error != null } }

        assertEquals("en", vm.state.value.currentLocale) // unchanged
        assertEquals("Could not update language setting.", vm.state.value.error)
    }

    @Test
    fun `a gateway error on init degrades gracefully to en`() = runBlocking {
        val failingGateway = object : LocaleGateway {
            override suspend fun fetchLocale(): String? = throw RuntimeException("Offline")
            override suspend fun updateLocale(tag: String) = Unit
        }
        val vm = SettingsLocaleViewModel(failingGateway)

        withTimeout(5_000) { vm.state.first { it.currentLocale == "en" } }

        assertEquals("en", vm.state.value.currentLocale)
        assertNull(vm.state.value.error)
    }
}