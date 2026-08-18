package com.example.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val title: String) {
    DARK("Dark Theme"),
    LIGHT("Light Theme"),
    SYSTEM("System Default")
}

object ThemeManager {
    private const val PREFS_NAME = "vocalmorph_theme_prefs"
    private const val KEY_THEME_MODE = "key_theme_mode"

    private val _themeMode = MutableStateFlow(AppThemeMode.DARK)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_THEME_MODE, AppThemeMode.DARK.name)
        _themeMode.value = try {
            AppThemeMode.valueOf(saved ?: AppThemeMode.DARK.name)
        } catch (e: Exception) {
            AppThemeMode.DARK
        }
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        _themeMode.value = mode
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun toggleNextTheme(context: Context) {
        val next = when (_themeMode.value) {
            AppThemeMode.DARK -> AppThemeMode.LIGHT
            AppThemeMode.LIGHT -> AppThemeMode.SYSTEM
            AppThemeMode.SYSTEM -> AppThemeMode.DARK
        }
        setThemeMode(context, next)
    }
}
