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

        // One-time migration on the first launch of 1.0.8: bump the pause
        // preset from "Short" (the silent default through 1.0.7) up to
        // "Medium". Multiple users complained that 0.5s was too aggressive
        // and cut sentences mid-thought; Medium gives a noticeably more
        // comfortable rhythm without surprising the niche who deliberately
        // chose Long. We don't touch users on Long — their explicit choice.
        Prefs.migrateTo108PauseDefault(this)

        // Extract the bundled GigaAM model from APK assets to filesDir on a
        // background thread. sherpa-onnx needs filesystem paths; assets are
        // read-only. Runs in parallel with welcome/permission onboarding
        // steps so by the time the user reaches the try-it step the copy is
        // already done on all but the slowest devices.
        thread(name = "model-extract", isDaemon = true, priority = Thread.NORM_PRIORITY - 1) {
            try {
                GigaAmModel.ensureInstalled(this)
            } catch (t: Throwable) {
                AppLog.log(this, "Model extract failed: ${t.message}")
            }
        }
    }
}
