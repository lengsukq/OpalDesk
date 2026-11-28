package io.github.lengsukq.opaldesk.service

/**
 * Pure-JVM lifecycle policy for [io.github.lengsukq.opaldesk.BridgeService].
 *
 * Keeps Foreground Service promotion strictly compliant with Android 14–17 background start
 * restrictions so background Binder handshakes or Recents swipes never arm an unfulfillable
 * `fgRequired` timeout (`OPALDESK-N`).
 */
object BridgeServiceLifecyclePolicy {
    /** Matches `ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND`. */
    const val IMPORTANCE_FOREGROUND = 100

    /** Matches `ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE`. */
    const val IMPORTANCE_GONE = 1000

    /**
     * Returns true when calling `Context.startForegroundService(BridgeService)` is both needed and
     * safe against Android 14–17 background FGS ANRs.
     */
    fun shouldStartForegroundService(
        userStopping: Boolean,
        alreadyForegroundActive: Boolean,
        requireForegroundProcess: Boolean,
        processImportance: Int,
    ): Boolean {
        if (userStopping || alreadyForegroundActive) return false
        if (!requireForegroundProcess) return true
        return processImportance in 1..IMPORTANCE_FOREGROUND
    }

    /**
     * Returns true when `BridgeService` is already promoted to foreground and callers should only
     * trigger an in-process refresh rather than calling `startForegroundService()` again.
     */
    fun shouldRefreshRunningServiceOnly(
        userStopping: Boolean,
        alreadyForegroundActive: Boolean,
    ): Boolean = !userStopping && alreadyForegroundActive

    /**
     * Returns true when `BridgeService.onTaskRemoved()` should cleanly stop itself because neither
     * a physical external display session nor a remote cast session requires the helper FGS.
     *
     * Note: the `uid=2000` daemon keeps running independently and can still deliver incoming pairing
     * requests directly through `BridgeProvider` without keeping an idle FGS alive.
     */
    fun shouldStopSelfOnTaskRemoved(
        hasPhysicalExternal: Boolean,
        hasRemoteCast: Boolean,
    ): Boolean = !hasPhysicalExternal && !hasRemoteCast
}
