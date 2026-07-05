package com.cardlens.tcg.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Einfache, SharedPreferences-basierte Einstellungen. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cardlens_settings", Context.MODE_PRIVATE)

    private val _currency = MutableStateFlow(prefs.getString(KEY_CURRENCY, "EUR") ?: "EUR")
    /** Bevorzugte Waehrung fuer Preisanzeige und Sammlungswert ("EUR" oder "USD"). */
    val currency: StateFlow<String> = _currency

    fun setCurrency(value: String) {
        prefs.edit().putString(KEY_CURRENCY, value).apply()
        _currency.value = value
    }

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    /** Material-You-Farben statt der Markenfarben verwenden. */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _dynamicColor.value = enabled
    }

    private val _defaultCondition = MutableStateFlow(prefs.getString(KEY_CONDITION, "NM") ?: "NM")
    /** Standard-Zustand fuer neu erfasste Karten (CardCondition.code). */
    val defaultCondition: StateFlow<String> = _defaultCondition

    fun setDefaultCondition(code: String) {
        prefs.edit().putString(KEY_CONDITION, code).apply()
        _defaultCondition.value = code
    }

    private val _defaultLanguage = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "en") ?: "en")
    /** Standard-Sprache fuer neu erfasste Karten (CardLanguage.code). */
    val defaultLanguage: StateFlow<String> = _defaultLanguage

    fun setDefaultLanguage(code: String) {
        prefs.edit().putString(KEY_LANGUAGE, code).apply()
        _defaultLanguage.value = code
    }

    private companion object {
        const val KEY_CURRENCY = "preferred_currency"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_CONDITION = "default_condition"
        const val KEY_LANGUAGE = "default_language"
    }
}
