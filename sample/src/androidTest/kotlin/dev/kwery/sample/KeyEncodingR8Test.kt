package dev.kwery.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kwery.QueryKey
import dev.kwery.encodeKey
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Key encoding must survive R8.
 *
 * Kwery encodes enum key parts by `name`. If R8 rewrote those names, every
 * persisted key would change, and the cache would miss on every cold start of
 * the released app while working perfectly in debug. That failure cannot be
 * reproduced without a minified build, which is why this test exists at all and
 * why the sample runs its instrumentation against `release`.
 */
@RunWith(AndroidJUnit4::class)
class KeyEncodingR8Test {

    enum class Filter { All, Open, Done }

    data class FilteredKey(val filter: Filter, val page: Int) : QueryKey<String> {
        override val parts get() = listOf("todos", filter, page)
    }

    data class MapKey(val flag: Boolean) : QueryKey<String> {
        override val parts get() = listOf("todos", mapOf("done" to flag, "sort" to Filter.Open))
    }

    @Test
    fun enum_parts_encode_by_their_declared_names_after_minification() {
        // The literal expected string, not a comparison against another
        // encoding. Comparing two encodings would pass even if R8 renamed the
        // constant, because both sides would be renamed together.
        assertEquals("""["todos","Open",2]""", encodeKey(FilteredKey(Filter.Open, 2).parts))
        assertEquals("""["todos","All",0]""", encodeKey(FilteredKey(Filter.All, 0).parts))
        assertEquals("""["todos","Done",7]""", encodeKey(FilteredKey(Filter.Done, 7).parts))
    }

    @Test
    fun enum_names_inside_maps_also_survive() {
        assertEquals(
            """["todos",{"done":true,"sort":"Open"}]""",
            encodeKey(MapKey(flag = true).parts),
        )
    }

    @Test
    fun the_enum_constant_name_itself_is_intact() {
        // If R8 had rewritten the constant, `name` would report the new one and
        // every assertion above would still agree with itself.
        assertEquals("Open", Filter.Open.name)
        assertEquals(listOf("All", "Open", "Done"), Filter.entries.map { it.name })
    }

    @Test
    fun this_really_is_a_minified_build() {
        // A guard against the test silently becoming worthless. If the sample
        // stopped being minified, everything above would still pass and prove
        // nothing.
        val minified = javaClass.classLoader
            ?.loadClass("dev.kwery.QueryKeyCodecKt")
            ?.declaredMethods
            ?.isNotEmpty() == true
        assertTrue(minified, "the codec must be present in the minified APK")
    }
}
