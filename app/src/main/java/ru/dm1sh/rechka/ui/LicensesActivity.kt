package ru.dm1sh.rechka.ui

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.textview.MaterialTextView
import ru.dm1sh.rechka.R

/**
 * Renders app/src/main/assets/third_party_licenses.txt inside a scroll view.
 *
 * Keeping the full licence text in an asset (not a string resource) means the
 * user can read exactly what ships with the APK — same file we point to from
 * the GitHub repo, no risk of drift.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)

        findViewById<MaterialToolbar>(R.id.topAppBar)
            .setNavigationOnClickListener { finish() }

        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        findViewById<MaterialTextView>(R.id.licensesBody).text = readLicenses()
    }

    private fun readLicenses(): String = try {
        assets.open("third_party_licenses.txt").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "third_party_licenses.txt not found"
    }
}
