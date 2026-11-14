package com.nropvox

import android.content.SharedPreferences
import androidx.core.content.edit

internal fun streamCornerSettings(pref: SharedPreferences) = listOf(
    SettingSection(
        "Mirror",
        listOf(
            SettingField(
                kind = SettingField.Kind.URL,
                title = "Mirror URL",
                description = "StreamCorner moves between domains. Tap a mirror below, or type your own address. The full list is on Beacons. " +
                    " Leave it empty to use ${StreamCorner.DEFAULT_URL}.",
                label = "Mirror URL",
                hint = StreamCorner.DEFAULT_URL,
                unsetSummary = "Default · ${StreamCorner.DEFAULT_URL.removePrefix("https://")}",
                setSummary = { "Custom · ${it.removePrefix("https://").removePrefix("http://").substringBefore('/')}" },
                get = { pref.getString(StreamCorner.MIRROR_KEY, "").orEmpty().trim() },
                set = { pref.edit { putString(StreamCorner.MIRROR_KEY, it) } },
                normalize = ::normalizeSettingUrl,
                clearLabel = "Reset",
                presets = StreamCorner.MIRRORS.map { it.removePrefix("https://").uppercase() to it },
                linkText = "Open mirror list",
                linkUrl = StreamCorner.MIRRORS_PAGE,
            ),
        ),
    ),
)
