package ru.dm1sh.rechka.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.util.AppLog
import ru.dm1sh.rechka.util.Prefs

/**
 * Editor for the user dictionary (post-recognition word replacements).
 *
 * Layout/insets pattern matches SettingsActivity to be consistent under
 * SDK 35 edge-to-edge enforcement.
 *
 * One action button (Save) — copy is the EditText's native long-press
 * gesture (Select All → Copy), no need to ship a redundant button. Pasting
 * is also native long-press → Вставить.
 *
 * Help is split between an inline one-liner under the toolbar (always
 * visible, sets expectations immediately) and a full-text dialog behind
 * the toolbar info icon (deeper details for those who want them).
 */
class DictionaryActivity : AppCompatActivity() {

    private lateinit var editor: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_help) {
                showHelpDialog()
                true
            } else false
        }

        // Add bottom inset (nav bar + soft keyboard) to the content column.
        // Capture original XML padding once so we add insets ON TOP of it
        // instead of replacing it. updatePadding only touches the side we
        // pass — left/right/top from XML stay.
        val content = findViewById<View>(R.id.content)
        val baseBottomPad = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(bottom = baseBottomPad + maxOf(bars.bottom, ime.bottom))
            insets
        }

        // Master on/off — saves immediately on toggle so leaving via back
        // button persists the choice without requiring a separate Save tap.
        // Default OFF (Prefs default) so a brand-new user sees rules without
        // them silently transforming dictation right away.
        val enabledSwitch = findViewById<MaterialSwitch>(R.id.enabledSwitch)
        enabledSwitch.isChecked = Prefs.isDictionaryEnabled(this)
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setDictionaryEnabled(this, checked)
            AppLog.log(this, "Dictionary enabled=$checked")
        }

        editor = findViewById(R.id.editor)
        val saved = Prefs.getDictionary(this)
        // First-run: pre-fill with a self-documenting starter template so
        // the user immediately sees the format and a couple of safe examples
        // (commented out — won't fire). Once they save anything non-empty,
        // we never overwrite their data.
        editor.setText(if (saved.isBlank()) getString(R.string.dict_starter_template) else saved)

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { onSave() }
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dict_help_dialog_title)
            .setMessage(R.string.dict_help_dialog_body)
            .setPositiveButton(R.string.dict_help_dialog_close, null)
            .show()
    }

    private fun onSave() {
        Prefs.setDictionary(this, editor.text?.toString() ?: "")
        Toast.makeText(this, R.string.dict_saved, Toast.LENGTH_SHORT).show()
        AppLog.log(this, "Dictionary saved (${editor.text?.length ?: 0} chars)")
        finish()
    }
}
