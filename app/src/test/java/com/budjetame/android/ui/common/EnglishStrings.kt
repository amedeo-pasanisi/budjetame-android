package com.budjetame.android.ui.common

import com.budjetame.android.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The English (`values/`) string resources for JVM tests (ADR-0010): no
 * Android `Resources` exist on the JVM, so this reads the module's
 * `src/main/res/values/strings*.xml` and resolves a [UiText] the way
 * `Resources.getString` / `getQuantityString` would under `en`. Tests keep
 * asserting the exact English copy: `assertEquals("Cash", label.english())`.
 */
object EnglishStrings {

    private val valuesDir: File by lazy {
        listOf("src/main/res/values", "app/src/main/res/values")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: error("values/ not found from ${File(".").absolutePath}")
    }

    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val files = valuesDir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (file in files) {
            val root = builder.parse(file).documentElement
            val stringNodes = root.getElementsByTagName("string")
            for (i in 0 until stringNodes.length) {
                val e = stringNodes.item(i) as Element
                strings[e.getAttribute("name")] = unescape(e.textContent)
            }
            val pluralNodes = root.getElementsByTagName("plurals")
            for (i in 0 until pluralNodes.length) {
                val e = pluralNodes.item(i) as Element
                val items = e.getElementsByTagName("item")
                plurals[e.getAttribute("name")] = (0 until items.length).associate { j ->
                    val item = items.item(j) as Element
                    item.getAttribute("quantity") to unescape(item.textContent)
                }
            }
        }
    }

    /** The English text of the `<string>` resource [id]. */
    fun string(id: Int): String {
        val name = resourceName(R.string::class.java, id)
        return strings[name] ?: error("no English <string name=\"$name\">")
    }

    /** The English text of the `<plurals>` resource [id] for [count]. */
    fun plural(id: Int, count: Int): String {
        // By name: R.plurals exists only once a <plurals> resource does.
        val name = resourceName(Class.forName("com.budjetame.android.R\$plurals"), id)
        val items = plurals[name] ?: error("no English <plurals name=\"$name\">")
        val quantity = if (count == 1) "one" else "other"
        return items[quantity] ?: items.getValue("other")
    }

    fun resolve(text: UiText): String = when (text) {
        is UiText.Res -> format(string(text.id), text.args)
        is UiText.Plural -> format(plural(text.id, text.count), text.args)
        is UiText.Raw -> text.text
    }

    private fun format(pattern: String, args: List<Any>): String =
        if (args.isEmpty()) pattern
        else String.format(Locale.US, pattern, *args.map { if (it is UiText) resolve(it) else it }.toTypedArray())

    private fun resourceName(type: Class<*>, id: Int): String =
        type.fields.firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }?.name
            ?: error("resource id $id is not in ${type.simpleName}")

    /** Android's string-resource escapes: surrounding quotes, `\'`, `\"`,
     * `\n`, `\t`, `\\`, `\@`, `\?`. */
    private fun unescape(raw: String): String {
        var s = raw
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** This [UiText] as the English resources render it. */
fun UiText.english(): String = EnglishStrings.resolve(this)

/** These Field Errors (or any keyed messages) as the English resources
 * render them: `assertEquals(mapOf(NAME to "Enter a name"), errors.english())`. */
fun Map<String, UiText>.english(): Map<String, String> = mapValues { it.value.english() }
