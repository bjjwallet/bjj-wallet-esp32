package pub.airlock

import kotlin.test.*
import kotlinx.serialization.json.Json
import pub.airlock.domain.AppLanguage
import pub.airlock.domain.VaultSnapshot

class AppLanguageTest {
    @Test
    fun firstRunFollowsEachSupportedDeviceLanguage() {
        for ((tag, expected) in
            listOf(
                "pt-BR" to AppLanguage.PORTUGUESE,
                "pt_PT" to AppLanguage.PORTUGUESE,
                "en-US" to AppLanguage.ENGLISH,
                "zh-Hans-CN" to AppLanguage.CHINESE,
                "es-MX" to AppLanguage.SPANISH,
                "fr-CA" to AppLanguage.FRENCH,
                "it-IT" to AppLanguage.ITALIAN,
                "de-DE" to AppLanguage.GERMAN,
                "ja-JP" to AppLanguage.JAPANESE,
            )) assertEquals(expected, AppLanguage.resolve(null, listOf(tag)), tag)
    }

    @Test
    fun usesFirstSupportedPreferenceThenEnglishFallback() {
        assertEquals(
            AppLanguage.FRENCH,
            AppLanguage.resolve(null, listOf("ko-KR", "fr-CA", "pt-BR")),
        )
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(null, listOf("ko-KR", "ar-SA")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(null, emptyList()))
    }

    @Test
    fun explicitChoiceOverridesDeviceAndNullRestoresAutomaticSelection() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.resolve("de", listOf("pt-BR")))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.resolve(null, listOf("pt-BR")))
        assertEquals(AppLanguage.FRENCH, AppLanguage.resolve("unknown", listOf("fr-FR")))
    }

    @Test
    fun freshAndLegacyVaultsDoNotInventAnExplicitPreference() {
        assertNull(VaultSnapshot().language)
        assertNull(Json.decodeFromString<VaultSnapshot>("{}").language)
        assertEquals("pt", Json.decodeFromString<VaultSnapshot>("{\"language\":\"pt\"}").language)
        assertEquals("en", AppLanguage.explicitSelection("en-US"))
        assertNull(AppLanguage.explicitSelection("unsupported"))
    }
}
