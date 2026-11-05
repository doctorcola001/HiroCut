package com.hirocut.app.data.settings

import android.content.Context
import android.app.LocaleManager
import android.os.Build
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ColorTheme { Forest, Ocean, Amber }
enum class AppLanguage(val tag: String) { System(""), Chinese("zh-CN"), English("en") }
data class UiPreferences(val theme: ColorTheme = ColorTheme.Forest, val language: AppLanguage = AppLanguage.System)

class UiPreferencesStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UiPreferences(
        runCatching { ColorTheme.valueOf(prefs.getString("theme", null).orEmpty()) }.getOrDefault(ColorTheme.Forest),
        readLanguage(context)))
    val state = mutable.asStateFlow()
    fun setTheme(theme: ColorTheme) {
        prefs.edit().putString("theme", theme.name).apply()
        mutable.value = mutable.value.copy(theme = theme)
    }
    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString("language", language.name).apply()
        mutable.value = mutable.value.copy(language = language)
        applyStoredLanguage()
    }
    fun applyStoredLanguage() {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val desired = LocaleList.forLanguageTags(mutable.value.language.tag)
            if (manager.applicationLocales != desired) manager.applicationLocales = desired
        }
    }
    fun syncNativeLanguage() {
        if (Build.VERSION.SDK_INT < 33) return
        val tags = context.getSystemService(LocaleManager::class.java).applicationLocales
        val language = if (tags.isEmpty) AppLanguage.System else when (tags[0].language) {
            "zh" -> AppLanguage.Chinese
            "en" -> AppLanguage.English
            else -> AppLanguage.System
        }
        if (language != mutable.value.language) {
            prefs.edit().putString("language", language.name).apply()
            mutable.value = mutable.value.copy(language = language)
        }
    }
    companion object {
        private const val FILE = "ui-settings"
        fun readLanguage(context: Context): AppLanguage = runCatching {
            AppLanguage.valueOf(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("language", null).orEmpty())
        }.getOrDefault(AppLanguage.System)
        fun localizedContext(base: Context): Context {
            val language = readLanguage(base)
            val system = Resources.getSystem().configuration.locales[0]
            val locale = if (language == AppLanguage.System) {
                if (system.language in listOf("en", "zh")) system else Locale.ENGLISH
            } else Locale.forLanguageTag(language.tag)
            val config = Configuration(base.resources.configuration)
            config.setLocales(LocaleList(locale))
            config.setLayoutDirection(locale)
            return base.createConfigurationContext(config)
        }
    }
}
