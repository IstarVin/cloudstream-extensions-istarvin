package com.nropvox

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import okhttp3.Credentials

private const val RESOURCE_PACKAGE = "com.nropvox"

/**
 * OmniStream's settings in the style of StreamPlay's: a dark sheet of sections whose rows each open a dialog.
 * Plugin layouts inflate against the app's resources, so icons and backgrounds are set here from the plugin's own.
 */
class OmniStreamSettingsFragment(plugin: Plugin) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    private lateinit var pixeldrainSummary: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflate(inflater, "fragment_omnistream_settings", container)
        view.background = GradientDrawable().apply {
            setColor(SHEET)
            val r = dp(20f)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        view.find<View>("omniSettingsHandle").background = shape(0x33FFFFFF, 2f)

        val row = view.find<View>("omniPixeldrainRow")
        row.background = states(shape(CARD, 12f), pressed = shape(PRESSED, 12f), focused = shape(CARD, 12f, stroke = WHITE, strokeWidth = 2f))
        badge(view.find("omniPixeldrainBadge"), view.find("omniPixeldrainIcon"))
        view.find<ImageView>("omniPixeldrainChevron").setImageDrawable(icon("omni_ic_chevron", MUTED))
        pixeldrainSummary = view.find("omniPixeldrainSummary")
        refresh()
        row.setOnClickListener { showPixeldrainDialog() }
        return view
    }

    override fun onStart() {
        super.onStart()
        // The sheet's themed surface would show around the rounded top; open expanded so a TV shows every row.
        (view?.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun refresh() {
        val key = OmniSettings.pixeldrainKey
        pixeldrainSummary.text = if (key.isEmpty()) "Not set · links play as an anonymous visitor" else "On · key ending ${key.takeLast(4)}"
        pixeldrainSummary.setTextColor(if (key.isEmpty()) MUTED else GREEN)
    }

    private fun showPixeldrainDialog() {
        val context = requireContext()
        val view = inflate(LayoutInflater.from(context), "dialog_omnistream_pixeldrain", null)
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setOnDismissListener { if (isAdded) refresh() }
        }
        view.background = shape(DIALOG, 16f, stroke = 0x22FFFFFF)
        badge(view.find("omniKeyBadge"), view.find("omniKeyIcon"))

        val field = view.find<View>("omniKeyField")
        val input = view.find<EditText>("omniKeyInput")
        val status = view.find<TextView>("omniKeyStatus")
        val reveal = view.find<ImageView>("omniKeyReveal")
        val paste = view.find<ImageView>("omniKeyPaste")
        val remove = view.find<TextView>("omniKeyRemove")
        val check = view.find<TextView>("omniKeyCheck")
        val save = view.find<TextView>("omniKeySave")
        val link = view.find<TextView>("omniKeyLink")

        // The EditText takes focus, not the field around it, so the field outline follows it through `activated`.
        field.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), shape(INPUT_FOCUSED, 8f, stroke = WHITE, strokeWidth = 2f))
            addState(intArrayOf(), shape(INPUT, 8f, stroke = INPUT_STROKE))
        }
        input.setOnFocusChangeListener { _, hasFocus -> field.isActivated = hasFocus }
        listOf(paste, reveal).forEach {
            it.background = states(shape(Color.TRANSPARENT, 20f), pressed = shape(0x22FFFFFF, 20f), focused = shape(0x33FFFFFF, 20f))
        }
        paste.setImageDrawable(icon("omni_ic_paste", MUTED))
        link.background = states(shape(Color.TRANSPARENT, 6f), pressed = shape(0x22FFFFFF, 6f), focused = shape(0x22FFFFFF, 6f))
        remove.background = states(shape(Color.TRANSPARENT, 20f), pressed = shape(0x22FF6B6B, 20f), focused = shape(0x33FF6B6B, 20f))
        check.background = states(shape(Color.TRANSPARENT, 20f, stroke = INPUT_STROKE),
            pressed = shape(PRESSED, 20f, stroke = INPUT_STROKE), focused = shape(Color.TRANSPARENT, 20f, stroke = WHITE, strokeWidth = 2f))
        save.background = states(shape(WHITE, 20f), pressed = shape(0xFFDDDDDD.toInt(), 20f), focused = shape(WHITE, 20f, stroke = ACCENT, strokeWidth = 3f))

        fun say(text: String, color: Int) {
            status.text = text
            status.setTextColor(color)
            status.visibility = View.VISIBLE
        }

        val saved = OmniSettings.pixeldrainKey
        input.setText(saved)
        input.setSelection(input.length())
        remove.visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE
        input.doAfterTextChanged { status.visibility = View.GONE }

        var revealed = false
        fun showKey(show: Boolean) {
            revealed = show
            input.inputType = InputType.TYPE_CLASS_TEXT or
                if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Changing the input type resets the typeface.
            input.typeface = Typeface.MONOSPACE
            input.setSelection(input.length())
            reveal.setImageDrawable(icon(if (show) "omni_ic_visibility_off" else "omni_ic_visibility", MUTED))
            reveal.contentDescription = if (show) "Hide key" else "Show key"
        }
        showKey(false)
        reveal.setOnClickListener { showKey(!revealed) }

        paste.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            if (text.isNullOrEmpty()) say("The clipboard is empty", MUTED)
            else {
                input.setText(text)
                input.setSelection(input.length())
            }
        }

        check.setOnClickListener {
            val key = input.text.toString().trim()
            if (key.isEmpty()) return@setOnClickListener say("Enter a key first", RED)
            say("Checking…", MUTED)
            check.isEnabled = false
            check.alpha = 0.5f
            ioSafe {
                val result = try {
                    val response = app.get("https://pixeldrain.com/api/user", headers = mapOf("Authorization" to Credentials.basic("", key)), timeout = 15L)
                    OmniStreamParser.pixeldrainKeyCheck(response.code, response.text)
                } catch (e: Exception) {
                    OmniStreamParser.KeyCheck(false, "Couldn't reach Pixeldrain, check your connection")
                }
                runOnMainThread {
                    check.isEnabled = true
                    check.alpha = 1f
                    // A result for a key that has since been edited would describe the wrong key.
                    if (dialog.isShowing && input.text.toString().trim() == key) say(result.message, if (result.valid) GREEN else RED)
                }
            }
        }

        link.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pixeldrain.com/user/api_keys")))
            } catch (e: Exception) {
                say("No browser here: open pixeldrain.com/user/api_keys on another device", MUTED)
            }
        }
        save.setOnClickListener {
            OmniSettings.pixeldrainKey = input.text.toString()
            dialog.dismiss()
        }
        remove.setOnClickListener {
            OmniSettings.pixeldrainKey = ""
            dialog.dismiss()
        }

        dialog.show()
        val width = minOf(context.resources.displayMetrics.widthPixels - dp(32f).toInt(), dp(460f).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun badge(frame: View, icon: ImageView) {
        frame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BADGE)
        }
        icon.setImageDrawable(icon("omni_ic_key", BADGE_ICON))
    }

    @SuppressLint("DiscouragedApi")
    private fun identifier(name: String, type: String): Int =
        res.getIdentifier(name, type, RESOURCE_PACKAGE).takeIf { it != 0 } ?: throw Exception("Resource $type/$name not found")

    private fun inflate(inflater: LayoutInflater, layout: String, container: ViewGroup?): View =
        inflater.inflate(res.getLayout(identifier(layout, "layout")), container, false)

    private fun <T : View> View.find(name: String): T = findViewById(identifier(name, "id"))

    private fun icon(name: String, tint: Int): Drawable? =
        res.getDrawable(identifier(name, "drawable"), null)?.mutate()?.apply { setTint(tint) }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun shape(fill: Int, radius: Float, stroke: Int = 0, strokeWidth: Float = 1f) = GradientDrawable().apply {
        cornerRadius = dp(radius)
        setColor(fill)
        if (stroke != 0) setStroke(dp(strokeWidth).toInt(), stroke)
    }

    /** [normal], [pressed] while held and [focused] under a D-pad, like StreamPlay's rows. */
    private fun states(normal: Drawable, pressed: Drawable, focused: Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    private companion object {
        val SHEET = 0xFF141414.toInt()
        val DIALOG = 0xFF1A1A1A.toInt()
        val CARD = 0xFF1E1E1E.toInt()
        val PRESSED = 0xFF2D2D2D.toInt()
        val INPUT = 0xFF1F1F1F.toInt()
        val INPUT_FOCUSED = 0xFF222222.toInt()
        val INPUT_STROKE = 0xFF3A3A3A.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val MUTED = 0xFF888888.toInt()
        val GREEN = 0xFF5BC98C.toInt()
        val RED = 0xFFFF6B6B.toInt()
        val ACCENT = 0xFF7AA7FF.toInt()
        val BADGE = 0xFF164E4A.toInt()
        val BADGE_ICON = 0xFF5EEAD4.toInt()
    }
}
