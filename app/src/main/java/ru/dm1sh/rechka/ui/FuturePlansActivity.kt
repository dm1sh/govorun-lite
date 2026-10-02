package ru.dm1sh.rechka.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import ru.dm1sh.rechka.R

/**
 * "Что будет дальше" — the future-features teaser with a review CTA.
 * Accessed via the MainActivity toolbar overflow menu.
 */
class FuturePlansActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_future_plans)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.setNavigationOnClickListener { finish() }

        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        findViewById<MaterialButton>(R.id.proReviewButton).setOnClickListener {
            openReview()
        }
        findViewById<MaterialButton>(R.id.proShareButton).setOnClickListener {
            shareApp()
        }
    }

    private fun openReview() {
        val uri = Uri.parse(getString(R.string.main_pro_review_url))
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No handler — silently no-op.
        }
    }

    private fun shareApp() {
        val text = getString(R.string.main_share_app_text)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.main_share_chooser)))
        } catch (_: Exception) {
        }
    }
}
