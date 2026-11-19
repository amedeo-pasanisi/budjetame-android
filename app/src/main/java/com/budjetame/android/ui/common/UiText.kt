package com.budjetame.android.ui.common

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources

/**
 * User-visible text produced outside a composable (ADR-0010): a ViewModel's
 * UI state or a presentation helper names a string resource and its
 * arguments, and the composable resolves it in the Account's Locale at
 * render time. A Locale switch therefore re-renders text already held in
 * state, and JVM tests assert on the value (ids and args), with no Android
 * resources at hand.
 *
 * Arguments are positional (`%1$s`, `%2$d`): an amount or a date is passed
 * already formatted (Money / Dates with the display Locale), never as a
 * float. An argument that is itself a [UiText] is resolved first, so a label
 * can be nested in a sentence.
 */
sealed interface UiText {

    /** A `<string>` resource with its format arguments. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A `<plurals>` resource: [count] picks the quantity, [args] fill it
     * (usually the count itself). */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Text that is already in the user's language or is not language at
     * all: a server message, a user-entered name, a formatted amount. */
    data class Raw(val text: String) : UiText

    companion object {
        /** A `<string>` resource with positional [args]: `UiText.of(R.string.x, name)`. */
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

/** [UiText] against [resources] — the Account-Locale resources a composable
 * sees, or any `Resources` in non-composable code. */
fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) {
        resources.getString(id)
    } else {
        resources.getString(id, *resolveArgs(args, resources))
    }
    is UiText.Plural -> resources.getQuantityString(id, count, *resolveArgs(args, resources))
    is UiText.Raw -> text
}

/** [UiText] in the Account's Locale (the [AccountLocaleResources] in scope). */
@Composable
@ReadOnlyComposable
fun UiText.resolve(): String = resolve(LocalResources.current)

private fun resolveArgs(args: List<Any>, resources: Resources): Array<Any> =
    args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()
