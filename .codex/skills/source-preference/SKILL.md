# Create Cloudstream Plugin Preferences

Use this skill when adding settings/preferences to a Cloudstream plugin extension. Pattern must stay reusable for any source/plugin, not tied to StreamPlay names.

## Goal

Create plugin settings that open from Cloudstream's plugin settings button, store values in `SharedPreferences`, and expose saved values to providers/fragments.

## Required pieces

1. Enable resources in plugin module:

```kotlin
cloudstream {
    requiresResources = true
    isCrossPlatform = false
}
```

2. Add Material dependency if using `BottomSheetDialogFragment` or Material widgets:

```kotlin
dependencies {
    implementation("com.google.android.material:material:<version>")
}
```

3. In plugin `load(context)`, create one plugin-scoped `SharedPreferences` instance:

```kotlin
private const val PREF_FILE = "YourPluginName"

override fun load(context: Context) {
    val sharedPref = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    registerMainAPI(YourProvider(sharedPref))

    openSettings = { ctx ->
        val activity = ctx as AppCompatActivity
        if (!activity.isFinishing && !activity.isDestroyed) {
            MainSettingsFragment(this, sharedPref)
                .show(activity.supportFragmentManager, "main_settings")
        }
    }
}
```

4. Pass `SharedPreferences` into providers that need saved settings:

```kotlin
class YourProvider(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {
    private val enabled = sharedPref?.getBoolean("your_setting_key", true) ?: true
}
```

5. Create main settings fragment as menu/router:

```kotlin
class MainSettingsFragment(
    private val plugin: Plugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Exception("View ID $name not found")
        return findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = getLayout("fragment_main_settings", inflater, container)

        val option: View = view.findView("yourOption")
        option.setOnClickListener {
            YourSettingFragment(plugin, sharedPref).show(
                activity?.supportFragmentManager ?: throw Exception("No FragmentManager"),
                "your_setting_fragment"
            )
        }

        return view
    }
}
```

6. Create layout for main settings menu. Keep IDs matched with `findView("...")` calls:

```xml
<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Settings"
            android:textSize="20sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/yourOption"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:focusable="true"
            android:padding="12dp"
            android:text="Your Setting" />
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
```

7. Create setting fragment that reads/writes same `SharedPreferences`:

```kotlin
class YourSettingFragment(
    private val plugin: Plugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val id = res.getIdentifier("fragment_your_setting", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val view = inflater.inflate(res.getLayout(id), container, false)

        val enabled = sharedPref.getBoolean("your_setting_key", true)

        sharedPref.edit {
            putBoolean("your_setting_key", enabled)
        }

        return view
    }
}
```

## Reusable rules

- Use one constant preference file name per plugin.
- Use stable, unique preference keys; prefix keys when feature-specific.
- Pass `SharedPreferences` from plugin `load()` into providers and fragments.
- Keep `MainSettingsFragment` as menu/router only; store actual preference logic in child fragments.
- Use `plugin.resources.getIdentifier(..., BuildConfig.LIBRARY_PACKAGE_NAME)` for plugin resource lookup.
- Match XML IDs exactly with Kotlin lookup strings.
- Use `androidx.core.content.edit` for concise writes.
- Use defaults when reading preferences so first launch works.
- If setting changes registered providers, tell user restart/reload may be needed.
- Do not hardcode source-specific names, provider lists, or preference keys inside reusable templates.

## Complete portable example

### Plugin wiring

```kotlin
package com.example

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    companion object {
        private const val PREF_FILE = "ExamplePlugin"
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        registerMainAPI(ExampleProvider(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            if (!activity.isFinishing && !activity.isDestroyed) {
                ExampleMainSettingsFragment(this, sharedPref)
                    .show(activity.supportFragmentManager, "example_main_settings")
            }
        }
    }
}
```

### Main settings menu fragment

```kotlin
package com.example

import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin

class ExampleMainSettingsFragment(
    private val plugin: Plugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Exception("Drawable $name not found")
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Exception("View ID $name not found")
        return findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (outlineId != 0) background = res.getDrawable(outlineId, null)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Exception("Layout $name not found")
        return inflater.inflate(res.getLayout(id), container, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = getLayout("fragment_example_main_settings", inflater, container)

        val toggleRow: View = view.findView("toggleRow")
        val textRow: View = view.findView("textRow")

        toggleRow.makeTvCompatible()
        textRow.makeTvCompatible()

        toggleRow.setOnClickListener {
            ExampleToggleSettingFragment(plugin, sharedPref).show(
                activity?.supportFragmentManager ?: throw Exception("No FragmentManager"),
                "example_toggle_setting"
            )
        }

        textRow.setOnClickListener {
            ExampleTextSettingFragment(plugin, sharedPref).show(
                activity?.supportFragmentManager ?: throw Exception("No FragmentManager"),
                "example_text_setting"
            )
        }

        return view
    }
}
```

### Main settings menu layout

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:layoutDirection="ltr">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingStart="?android:attr/listPreferredItemPaddingStart"
        android:paddingEnd="?android:attr/listPreferredItemPaddingEnd"
        android:paddingBottom="20dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:layout_marginBottom="10dp"
            android:text="Settings"
            android:textSize="20sp"
            android:textStyle="bold" />

        <LinearLayout
            android:id="@+id/toggleRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:focusable="true"
            android:orientation="horizontal"
            android:padding="12dp">

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Enable Feature"
                android:textSize="18sp" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/textRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:focusable="true"
            android:orientation="horizontal"
            android:padding="12dp">

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Edit Token"
                android:textSize="18sp" />
        </LinearLayout>
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
```

### Boolean preference fragment

```kotlin
package com.example

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin

class ExampleToggleSettingFragment(
    private val plugin: Plugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = res.getIdentifier("fragment_example_toggle_setting", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val view = inflater.inflate(res.getLayout(layoutId), container, false)
        val switchId = res.getIdentifier("featureSwitch", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        val featureSwitch = view.findViewById<Switch>(switchId)

        featureSwitch.isChecked = sharedPref.getBoolean("feature_enabled", true)
        featureSwitch.setOnCheckedChangeListener { _, checked ->
            sharedPref.edit { putBoolean("feature_enabled", checked) }
        }

        return view
    }
}
```

### Text preference fragment

```kotlin
package com.example

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

class ExampleTextSettingFragment(
    private val plugin: Plugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = res.getIdentifier("fragment_example_text_setting", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val view = inflater.inflate(res.getLayout(layoutId), container, false)
        val inputId = res.getIdentifier("tokenInput", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        val saveId = res.getIdentifier("saveButton", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        val tokenInput = view.findViewById<EditText>(inputId)
        val saveButton = view.findViewById<Button>(saveId)

        tokenInput.setText(sharedPref.getString("token", "") ?: "")
        saveButton.setOnClickListener {
            sharedPref.edit { putString("token", tokenInput.text.toString()) }
            dismiss()
        }

        return view
    }
}
```

### Child preference layouts

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="20dp">

    <Switch
        android:id="@+id/featureSwitch"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Enable Feature" />
</LinearLayout>
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="20dp">

    <EditText
        android:id="@+id/tokenInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Token" />

    <Button
        android:id="@+id/saveButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Save" />
</LinearLayout>
```
