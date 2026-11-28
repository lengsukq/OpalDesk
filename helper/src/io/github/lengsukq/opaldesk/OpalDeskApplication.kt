package io.github.lengsukq.opaldesk

import android.app.Application
import android.content.Context
import io.github.lengsukq.opaldesk.diagnostics.FeedbackHistoryStore
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.diagnostics.OpalSentry
import io.github.lengsukq.opaldesk.prefs.OpalLocalePolicy

class OpalDeskApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(OpalLocalePolicy.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        OpalLogStore.init(this)
        FeedbackHistoryStore.init(this)
        OpalSentry.init(this)
    }
}
