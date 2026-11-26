package io.github.lengsukq.opaldesk

import android.app.Application
import io.github.lengsukq.opaldesk.diagnostics.FeedbackHistoryStore
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.diagnostics.OpalSentry

class OpalDeskApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OpalLogStore.init(this)
        FeedbackHistoryStore.init(this)
        OpalSentry.init(this)
    }
}
