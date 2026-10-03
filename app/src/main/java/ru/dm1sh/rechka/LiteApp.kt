package ru.dm1sh.rechka

import android.app.Application
import com.google.android.material.color.DynamicColors
import ru.dm1sh.rechka.model.GigaAmModel
import ru.dm1sh.rechka.util.AppLog
import ru.dm1sh.rechka.util.Prefs
import kotlin.concurrent.thread

class LiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        Prefs.migrateTo108PauseDefault(this)

        // Validate only the private working copy. The SAF folder selected during
        // onboarding is intentionally not persisted or accessed after copying.
        thread(name = "model-validate", isDaemon = true, priority = Thread.NORM_PRIORITY - 1) {
            try {
                if (GigaAmModel.isInstalled(this) && !GigaAmModel.validateInstalled(this)) {
                    AppLog.log(this, "Installed model checksum validation failed")
                }
            } catch (t: Throwable) {
                AppLog.log(this, "Model validation failed: ${t.message}")
            }
        }
    }
}
