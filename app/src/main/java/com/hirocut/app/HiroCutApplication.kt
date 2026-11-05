package com.hirocut.app

import android.app.Application
import android.content.res.Configuration
import androidx.media3.common.util.UnstableApi
import com.hirocut.app.media.export.ExportCoordinator
import com.hirocut.app.data.settings.UiPreferencesStore

@UnstableApi
class HiroCutApplication : Application() {
    lateinit var exports: ExportCoordinator
        private set
    lateinit var preferences: UiPreferencesStore
        private set
    override fun onCreate() {
        super.onCreate()
        preferences = UiPreferencesStore(this)
        preferences.applyStoredLanguage()
        exports = ExportCoordinator(this)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::preferences.isInitialized) preferences.syncNativeLanguage()
    }
}
