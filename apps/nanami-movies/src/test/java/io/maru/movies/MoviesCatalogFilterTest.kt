package io.maru.movies

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviesCatalogFilterTest {
    @Test
    fun keepsNonAnimeCartoons() {
        assertTrue(includeAnimation(listOf("Australia", "United Kingdom", "United States"), false))
        assertTrue(includeAnimation(listOf("United States", "South Korea"), false))
        assertTrue(includeAnimation(listOf("United States", "Japan"), false))
    }

    @Test
    fun hidesAnimeWithJapaneseOriginOrAnAnimeDatabaseMatch() {
        assertFalse(includeAnimation(listOf("Japan", "United States"), false))
        assertFalse(includeAnimation(listOf("United Kingdom", "United States", "Japan"), true))
        assertFalse(includeAnimation(listOf("United States"), true))
    }

    @Test
    fun fallsBackToUnambiguousOriginsWhenLookupIsUnavailable() {
        assertTrue(includeAnimation(listOf("United States"), null))
        assertFalse(includeAnimation(listOf("United States", "Japan"), null))
        assertFalse(includeAnimation(emptyList(), null))
    }
}
