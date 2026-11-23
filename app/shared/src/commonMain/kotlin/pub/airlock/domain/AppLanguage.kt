package pub.airlock.domain

enum class AppLanguage(val code: String, val nativeName: String) {
    PORTUGUESE("pt", "Português"),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    CHINESE("zh", "简体中文"),
    JAPANESE("ja", "日本語"),
    ITALIAN("it", "Italiano"),
    GERMAN("de", "Deutsch");

    companion object {
        fun fromCode(code: String): AppLanguage = supported(code) ?: ENGLISH

        private fun supported(tag: String): AppLanguage? {
            val language = tag.substringBefore('-').substringBefore('_').lowercase()
            return entries.firstOrNull { it.code == language }
        }

        /** Unknown legacy values follow the device; existing valid selections are retained. */
        fun explicitSelection(saved: String?): String? = saved?.let { supported(it)?.code }

        fun resolve(selection: String?, deviceLanguages: List<String>): AppLanguage =
            selection?.let(::supported)
                ?: deviceLanguages.firstNotNullOfOrNull(::supported)
                ?: ENGLISH
    }
}
