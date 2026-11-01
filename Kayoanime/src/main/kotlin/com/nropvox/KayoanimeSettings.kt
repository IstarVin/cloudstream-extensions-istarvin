package com.nropvox

import android.content.SharedPreferences
import androidx.core.content.edit

internal fun kayoanimeSettings(pref: SharedPreferences) = listOf(
    SettingSection(
        "Google Drive proxy",
        listOf(
            SettingField(
                kind = SettingField.Kind.URL,
                title = "Proxy URL",
                description = "Episodes are Google Drive files served by your own Drive proxy. " +
                    "Leave the URL empty to use ${Kayoanime.DEFAULT_PROXY_URL}.",
                label = "Proxy URL",
                hint = Kayoanime.DEFAULT_PROXY_URL,
                unsetSummary = "Default · ${Kayoanime.DEFAULT_PROXY_URL.removePrefix("https://")}",
                setSummary = { "Custom · ${it.removePrefix("https://").removePrefix("http://").substringBefore('/')}" },
                get = { pref.getString(Kayoanime.PROXY_URL_KEY, "").orEmpty().trim() },
                set = { pref.edit { putString(Kayoanime.PROXY_URL_KEY, it) } },
                normalize = ::normalizeSettingUrl,
                clearLabel = "Reset",
            ),
            SettingField(
                kind = SettingField.Kind.SECRET,
                title = "Proxy API key",
                description = "The key your Drive proxy expects with every request. Posts hosted on the site's Drive index mirrors play without it.",
                label = "API key",
                hint = "Paste your API key",
                unsetSummary = "Not set · Drive files won't play",
                setSummary = { "On · key ending ${it.takeLast(4)}" },
                get = { pref.getString(Kayoanime.PROXY_KEY_KEY, "").orEmpty().trim() },
                set = { pref.edit { putString(Kayoanime.PROXY_KEY_KEY, it) } },
            ),
        ),
    ),
)
