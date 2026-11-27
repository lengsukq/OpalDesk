package io.github.lengsukq.opaldesk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.ime.OpalDeskImeChannel
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.IpcTrustVerifier

/**
 * Receives daemon Binder handshakes (`setBinder`), zero-roundtrip compositor scene pushes (`syncScene`),
 * and cert-verified companion YuyanIme plugin state syncs (`pluginImeSync`).
 */
class BridgeProvider : ContentProvider() {
    companion object {
        private const val TAG = "OpalDeskProvider"
        private val procToken = Binder()
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callerUid = Binder.getCallingUid()
        if (method == BridgeProtocol.PROVIDER_METHOD_PLUGIN_IME_SYNC) {
            if (!IpcTrustVerifier.isTrustedSameCertPluginUid(context, callerUid) &&
                !IpcTrustVerifier.isTrustedCallerUid(callerUid)
            ) {
                Log.w(TAG, "Rejected pluginImeSync from untrusted uid=$callerUid")
                return Bundle().apply { putBoolean("ok", false) }
            }
            return OpalDeskImeChannel.handlePluginProviderSync(arg.orEmpty(), extras)
        }
        if (!IpcTrustVerifier.isTrustedCallerUid(callerUid)) {
            Log.w(TAG, "Rejected BridgeProvider.call($method) from untrusted uid=$callerUid")
            if (method == BridgeProtocol.PROVIDER_METHOD_SET_BINDER) {
                OpalLogStore.w("IPC", "BridgeProvider rejected setBinder from untrusted uid=$callerUid")
            }
            return Bundle().apply { putBoolean("ok", false) }
        }
        return when (method) {
            BridgeProtocol.PROVIDER_METHOD_SET_BINDER -> handleSetBinder(extras, callerUid)
            BridgeProtocol.PROVIDER_METHOD_SYNC_SCENE -> handleSyncScene(extras)
            BridgeProtocol.PROVIDER_METHOD_PAIRING_EVENT -> handlePairingEvent(extras)
            BridgeProtocol.PROVIDER_METHOD_IME_REQUEST -> handleImeRequest(extras)
            BridgeProtocol.PROVIDER_METHOD_IME_COMMIT_TEXT -> handleImeCommitText(extras)
            BridgeProtocol.PROVIDER_METHOD_REMOTE_STATE_CHANGED -> handleRemoteStateChanged()
            else -> Bundle()
        }
    }

    private fun handleSetBinder(extras: Bundle?, callerUid: Int): Bundle {
        if (extras == null) {
            OpalLogStore.w("IPC", "BridgeProvider rejected setBinder: null extras (uid=$callerUid)")
            return Bundle().apply { putBoolean("ok", false) }
        }
        if (BridgeService.sUserStopping) {
            OpalLogStore.w("IPC", "BridgeProvider ignored setBinder while stopping (uid=$callerUid)")
            return Bundle().apply { putBoolean("ok", false) }
        }
        val cb = extras.getBinder(BridgeProtocol.EXTRA_CALLBACK_BINDER)
        if (!IpcTrustVerifier.isValidDaemonBinder(cb)) {
            OpalLogStore.w(
                "IPC",
                "BridgeProvider rejected invalid/unreachable daemon binder (uid=$callerUid, null=${cb == null}, alive=${cb?.pingBinder() == true})",
            )
            return Bundle().apply { putBoolean("ok", false) }
        }
        BridgeClient.setBinder(cb)
        BridgeService.ensureStarted(context)
        return Bundle().apply {
            putBoolean("ok", true)
            putBinder(BridgeProtocol.EXTRA_PROC_TOKEN, procToken)
        }
    }

    private fun handleSyncScene(extras: Bundle?): Bundle {
        if (!BridgeService.sUserStopping && extras != null) {
            val sceneJson = extras.getString(BridgeProtocol.EXTRA_SYNC_SCENE_JSON).orEmpty()
            val seq = extras.getLong(BridgeProtocol.EXTRA_SYNC_STATE_SEQ, 0L)
            if (sceneJson.isNotEmpty()) {
                BridgeClient.onPushedSceneJson(sceneJson, seq)
            }
        }
        return Bundle().apply { putBoolean("ok", true) }
    }

    private fun handlePairingEvent(extras: Bundle?): Bundle {
        if (!BridgeService.sUserStopping && extras != null) {
            val pairingJson = extras.getString(BridgeProtocol.EXTRA_PAIRING_JSON).orEmpty()
            val pending = BridgeClient.onPushedPairingJson(pairingJson)
            BridgeService.updatePairingNotification(context, pending)
        }
        return Bundle().apply { putBoolean("ok", true) }
    }

    private fun handleImeRequest(extras: Bundle?): Bundle {
        if (!BridgeService.sUserStopping && extras != null) {
            val windowId = extras.getInt(BridgeProtocol.EXTRA_IME_WINDOW_ID, -1)
            val show = extras.getBoolean(BridgeProtocol.EXTRA_IME_SHOW, true)
            val inputType = extras.getInt(BridgeProtocol.EXTRA_IME_INPUT_TYPE, 0)
            BridgeClient.onPushedImeRequest(windowId, show, inputType)
        }
        return Bundle().apply { putBoolean("ok", true) }
    }

    private fun handleImeCommitText(extras: Bundle?): Bundle {
        val committed = if (!BridgeService.sUserStopping && extras != null) {
            val text = extras.getString(BridgeProtocol.EXTRA_IME_COMMIT_TEXT).orEmpty()
            if (text.isNotEmpty()) {
                OpalDeskImeChannel.commitTextSync(
                    text = text,
                    rejectSelfPackage = false,
                )
            } else {
                false
            }
        } else {
            false
        }
        return Bundle().apply {
            putBoolean("ok", true)
            putBoolean(BridgeProtocol.EXTRA_IME_COMMITTED, committed)
        }
    }

    private fun handleRemoteStateChanged(): Bundle {
        if (!BridgeService.sUserStopping) {
            BridgeClient.onPushedRemoteStateChanged()
        }
        return Bundle().apply { putBoolean("ok", true) }
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
