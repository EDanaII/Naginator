package com.EDanaII.pillreminder

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal data class DeveloperOptions(val enabled: Boolean = false, val detailed: Boolean = false)

/** Separate device-protected preferences; available to alarm diagnostics before first unlock. */
internal object DeveloperSettings {
    fun preferences(context: Context): SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("developer_options", Context.MODE_PRIVATE)

    fun read(context: Context): DeveloperOptions = try {
        val prefs = preferences(context)
        val enabled = prefs.getBoolean("enabled", false)
        DeveloperOptions(enabled, enabled && prefs.getBoolean("detailed", false))
    } catch (_: RuntimeException) { DeveloperOptions() }

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled).apply {
            if (!enabled) putBoolean("detailed", false)
        }.apply()
    }

    fun setDetailed(context: Context, detailed: Boolean) {
        preferences(context).edit().putBoolean("detailed", detailed && read(context).enabled).apply()
    }
}

@Composable
internal fun rememberDeveloperOptions(): DeveloperOptions {
    val context = LocalContext.current
    var options by remember(context) { mutableStateOf(DeveloperSettings.read(context)) }
    DisposableEffect(context) {
        val prefs = DeveloperSettings.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            options = DeveloperSettings.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        options = DeveloperSettings.read(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}
