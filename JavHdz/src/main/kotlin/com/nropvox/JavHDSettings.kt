package com.nropvox

import android.content.SharedPreferences
import androidx.core.content.edit
import com.lagradost.cloudstream3.app

/** The answer to translating one word with the key: Google answers 400 `API key not valid` for a wrong key and 403 for one without the Translation API. */
internal fun googleTranslateKeyCheck(code: Int): SettingCheck = when {
    code in 200..299 -> SettingCheck(true, "Key accepted")
    code == 400 -> SettingCheck(false, "Google rejected this key: it is wrong or expired")
    code == 403 -> SettingCheck(false, "This key can't use the Cloud Translation API: enable it for the project")
    code == 429 -> SettingCheck(false, "Google is rate limiting this key, try again in a minute")
    else -> SettingCheck(false, "Google answered HTTP $code")
}

internal fun javHDSettings(pref: SharedPreferences) = listOf(
    SettingSection(
        "Site",
        listOf(
            SettingField(
                kind = SettingField.Kind.URL,
                title = "Base URL",
                description = "The site's address changes now and then. Set the new one here when it does; " +
                    "leave it empty to use $DEFAULT_MAIN_URL.",
                label = "Base URL",
                hint = DEFAULT_MAIN_URL,
                unsetSummary = "Default · ${DEFAULT_MAIN_URL.removePrefix("https://")}",
                setSummary = { "Custom · ${it.removePrefix("https://").removePrefix("http://").substringBefore('/')}" },
                get = { pref.getString(BASE_URL_PREF_KEY, "").orEmpty().trim() },
                set = { pref.edit { putString(BASE_URL_PREF_KEY, it) } },
                normalize = ::normalizeSettingUrl,
                clearLabel = "Reset",
            ),
        ),
    ),
    SettingSection(
        "Accounts",
        listOf(
            SettingField(
                kind = SettingField.Kind.SECRET,
                title = "Google Translate API key",
                subtitle = "translation.googleapis.com",
                description = "Titles on this site are Vietnamese. With a Cloud Translation API key they are shown in English; without one they stay as they are.",
                label = "API key",
                hint = "Paste your API key",
                unsetSummary = "Not set · titles stay in Vietnamese",
                setSummary = { "On · key ending ${it.takeLast(4)}" },
                get = { pref.getString(GOOGLE_TRANSLATE_API_KEY_PREF_KEY, "").orEmpty().trim() },
                set = { pref.edit { putString(GOOGLE_TRANSLATE_API_KEY_PREF_KEY, it) } },
                linkText = "Get a key at console.cloud.google.com/apis/credentials",
                linkUrl = "https://console.cloud.google.com/apis/credentials",
                check = { key ->
                    try {
                        val response = app.get(
                            "https://translation.googleapis.com/language/translate/v2?key=$key&q=xin&source=vi&target=en&format=text",
                            timeout = 15L,
                        )
                        googleTranslateKeyCheck(response.code)
                    } catch (e: Exception) {
                        SettingCheck(false, "Couldn't reach Google, check your connection")
                    }
                },
            ),
        ),
    ),
)
