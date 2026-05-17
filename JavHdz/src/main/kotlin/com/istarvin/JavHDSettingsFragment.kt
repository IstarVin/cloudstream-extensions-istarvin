package com.istarvin

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin

private const val RESOURCE_PACKAGE = "com.istarvin"

class JavHDSettingsFragment(
    plugin: Plugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = res.getIdentifier("fragment_javhd_settings", "layout", RESOURCE_PACKAGE)
        if (layoutId == 0) throw Exception("Layout fragment_javhd_settings not found")
        val view = inflater.inflate(res.getLayout(layoutId), container, false)

        val apiKeyInputId = res.getIdentifier("googleTranslateApiKeyInput", "id", RESOURCE_PACKAGE)
        val saveButtonId = res.getIdentifier("saveGoogleTranslateApiKeyButton", "id", RESOURCE_PACKAGE)
        if (apiKeyInputId == 0) throw Exception("View ID googleTranslateApiKeyInput not found")
        if (saveButtonId == 0) throw Exception("View ID saveGoogleTranslateApiKeyButton not found")

        val apiKeyInput = view.findViewById<EditText>(apiKeyInputId)
        val saveButton = view.findViewById<Button>(saveButtonId)

        apiKeyInput.setText(sharedPref.getString(GOOGLE_TRANSLATE_API_KEY_PREF_KEY, "") ?: "")
        saveButton.setOnClickListener {
            sharedPref.edit { putString(GOOGLE_TRANSLATE_API_KEY_PREF_KEY, apiKeyInput.text.toString().trim()) }
            dismiss()
        }

        return view
    }
}
