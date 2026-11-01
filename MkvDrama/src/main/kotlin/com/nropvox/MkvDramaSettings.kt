package com.nropvox

import android.content.SharedPreferences
import androidx.core.content.edit
import com.lagradost.cloudstream3.app
import okhttp3.Credentials

/** Reads Pixeldrain's `/api/user` answer: the account on 200, `authentication_failed` (401) for a bad, revoked or expired key. */
internal fun pixeldrainKeyCheck(code: Int, body: String): SettingCheck = when {
    code in 200..299 -> {
        val user = Regex(""""username"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        SettingCheck(true, if (user == null) "Key accepted" else "Signed in as $user")
    }
    code == 401 || code == 403 -> SettingCheck(false, "Pixeldrain rejected this key: it is wrong, revoked or expired")
    code == 429 -> SettingCheck(false, "Pixeldrain is rate limiting this device, try again in a minute")
    else -> SettingCheck(false, "Pixeldrain answered HTTP $code")
}

internal fun mkvDramaSettings(pref: SharedPreferences) = listOf(
    SettingSection(
        "Accounts",
        listOf(
            SettingField(
                kind = SettingField.Kind.SECRET,
                title = "Pixeldrain API key",
                subtitle = "pixeldrain.com · pixeldrain.dev",
                description = "MkvDrama sends this key with every Pixeldrain link, so files download on your account's limits instead of the anonymous ones.",
                label = "API key",
                hint = "Paste your API key",
                unsetSummary = "Not set · links play as an anonymous visitor",
                setSummary = { "On · key ending ${it.takeLast(4)}" },
                get = { pref.getString(PIXELDRAIN_API_KEY_PREF_KEY, "").orEmpty().trim() },
                set = { pref.edit { putString(PIXELDRAIN_API_KEY_PREF_KEY, it) } },
                linkText = "Get a key at pixeldrain.com/user/api_keys",
                linkUrl = "https://pixeldrain.com/user/api_keys",
                check = { key ->
                    try {
                        val response = app.get("https://pixeldrain.com/api/user", headers = mapOf("Authorization" to Credentials.basic("", key)), timeout = 15L)
                        pixeldrainKeyCheck(response.code, response.text)
                    } catch (e: Exception) {
                        SettingCheck(false, "Couldn't reach Pixeldrain, check your connection")
                    }
                },
            ),
        ),
    ),
)
