package io.github.lengsukq.opaldesk.ime

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable snapshot of the editor currently attached to [OpalDeskImeService]. */
data class ImeEditorSnapshot(
    val serviceBound: Boolean = false,
    val inputActive: Boolean = false,
    val packageName: String = "",
    val inputType: Int = 0,
    val imeOptions: Int = 0,
    val fieldId: Int = 0,
    val acceptingText: Boolean = false,
)

/**
 * Caret anchor coordinates in the target app's display space, captured via
 * `InputMethodService.onUpdateCursorAnchorInfo` for Phase 2 floating candidate-bar positioning.
 */
data class ImeCursorAnchorSnapshot(
    val valid: Boolean = false,
    val insertionMarkerHorizontal: Float = 0f,
    val insertionMarkerTop: Float = 0f,
    val insertionMarkerBaseline: Float = 0f,
    val insertionMarkerBottom: Float = 0f,
)

/** Pure JVM-testable adapter abstracting Android's `InputConnection`. */
interface ImeConnectionAdapter {
    fun beginBatchEdit(): Boolean
    fun endBatchEdit(): Boolean
    fun commitText(text: String, newCursorPosition: Int): Boolean
    fun setComposingText(text: String, newCursorPosition: Int): Boolean
    fun finishComposingText(): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun sendEnterKey(): Boolean
    fun performEditorAction(editorAction: Int): Boolean
}

/**
 * Pure JVM-testable state machine and text-commit engine behind [OpalDeskImeChannel].
 *
 * Isolates newline normalization, non-editable `TYPE_NULL` rejection, self-package loop prevention,
 * and batch-edit semantics from Android Looper/Service bindings.
 */
class OpalDeskImeChannelCore {
    companion object {
        const val MAX_COMMIT_CHARS = 65_536
        const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000

        /**
         * Returns `true` when the attached `EditorInfo` represents an editable target rather than
         * Android's dummy `TYPE_NULL` connection for non-text windows. Preserves terminal emulators
         * (e.g. Termux) that set `inputType = TYPE_NULL` alongside explicit `imeOptions` flags.
         */
        fun isAcceptingTextInput(inputType: Int, imeOptions: Int): Boolean =
            inputType != 0 || imeOptions != 0

        fun normalizeNewlines(raw: String): String {
            if (!raw.contains('\r')) return raw
            return raw.replace("\r\n", "\n").replace('\r', '\n')
        }
    }

    private val lock = Any()
    private var adapter: ImeConnectionAdapter? = null
    private var snapshot = ImeEditorSnapshot()
    private var cursorAnchor = ImeCursorAnchorSnapshot()

    fun currentEditorSnapshot(): ImeEditorSnapshot = synchronized(lock) { snapshot }
    fun currentCursorAnchor(): ImeCursorAnchorSnapshot = synchronized(lock) { cursorAnchor }

    fun onServiceAttached(connectionAdapter: ImeConnectionAdapter) {
        synchronized(lock) {
            adapter = connectionAdapter
            snapshot = snapshot.copy(serviceBound = true)
        }
    }

    fun onServiceDetached(connectionAdapter: ImeConnectionAdapter) {
        synchronized(lock) {
            if (adapter === connectionAdapter) {
                adapter = null
                snapshot = ImeEditorSnapshot()
                cursorAnchor = ImeCursorAnchorSnapshot()
            }
        }
    }

    fun onStartInput(packageName: String, inputType: Int, imeOptions: Int, fieldId: Int) {
        val cleanPkg = packageName.trim()
        val accepting = isAcceptingTextInput(inputType, imeOptions)
        synchronized(lock) {
            snapshot = ImeEditorSnapshot(
                serviceBound = adapter != null,
                inputActive = true,
                packageName = cleanPkg,
                inputType = inputType,
                imeOptions = imeOptions,
                fieldId = fieldId,
                acceptingText = accepting,
            )
            cursorAnchor = ImeCursorAnchorSnapshot()
        }
    }

    fun onFinishInput() {
        synchronized(lock) {
            snapshot = snapshot.copy(
                inputActive = false,
                packageName = "",
                inputType = 0,
                imeOptions = 0,
                fieldId = 0,
                acceptingText = false,
            )
            cursorAnchor = ImeCursorAnchorSnapshot()
        }
    }

    fun onCursorAnchorUpdated(next: ImeCursorAnchorSnapshot) {
        synchronized(lock) {
            if (snapshot.inputActive) {
                cursorAnchor = next
            }
        }
    }

    fun canCommitNow(
        text: String,
        rejectSelfPackage: Boolean,
        selfPackageName: String,
    ): Boolean {
        if (text.isEmpty() || text.length > MAX_COMMIT_CHARS) return false
        val st = synchronized(lock) {
            if (adapter == null) return false
            snapshot
        }
        if (!st.serviceBound || !st.inputActive || !st.acceptingText) return false
        if (rejectSelfPackage && selfPackageName.isNotEmpty() && st.packageName == selfPackageName) {
            return false
        }
        return true
    }

    fun commitTextDirect(
        text: String,
        rejectSelfPackage: Boolean = false,
        selfPackageName: String = "",
    ): Boolean {
        if (!canCommitNow(text, rejectSelfPackage, selfPackageName)) return false
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false

        val normalized = normalizeNewlines(text)
        if (!normalized.contains('\n')) {
            return conn.commitText(normalized, 1)
        }

        val multiLine = (st.inputType and TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val lines = normalized.split('\n')
        conn.beginBatchEdit()
        return try {
            var allOk = true
            lines.forEachIndexed { idx, segment ->
                if (segment.isNotEmpty()) {
                    if (!conn.commitText(segment, 1)) allOk = false
                }
                if (idx < lines.lastIndex) {
                    val newlineOk = if (multiLine) conn.commitText("\n", 1) else conn.sendEnterKey()
                    if (!newlineOk) allOk = false
                }
            }
            allOk
        } finally {
            conn.endBatchEdit()
        }
    }

    fun setComposingTextDirect(text: String): Boolean {
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false
        return conn.setComposingText(text, 1)
    }

    fun finishComposingTextDirect(): Boolean {
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false
        return conn.finishComposingText()
    }

    fun deleteSurroundingTextDirect(beforeLength: Int, afterLength: Int): Boolean {
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false
        return conn.deleteSurroundingText(beforeLength.coerceAtLeast(0), afterLength.coerceAtLeast(0))
    }

    fun performEditorActionDirect(editorAction: Int): Boolean {
        val (conn, st) = synchronized(lock) { adapter to snapshot }
        if (conn == null || !st.inputActive || !st.acceptingText) return false
        return conn.performEditorAction(editorAction)
    }
}

/**
 * In-process singleton channel linking [OpalDeskImeService], `BridgeProvider` (daemon remote cast
 * text commits), `WorkstationKeyDispatcher` (local trackpad text commits), and Compose UI status.
 */
object OpalDeskImeChannel {
    private const val MAIN_THREAD_COMMIT_TIMEOUT_MS = 350L

    private val core = OpalDeskImeChannelCore()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val _editorStateFlow = MutableStateFlow(ImeEditorSnapshot())
    val editorStateFlow: StateFlow<ImeEditorSnapshot> = _editorStateFlow.asStateFlow()

    private val _cursorAnchorFlow = MutableStateFlow(ImeCursorAnchorSnapshot())
    val cursorAnchorFlow: StateFlow<ImeCursorAnchorSnapshot> = _cursorAnchorFlow.asStateFlow()

    fun attachService(adapter: ImeConnectionAdapter) {
        core.onServiceAttached(adapter)
        publishSnapshots()
    }

    fun detachService(adapter: ImeConnectionAdapter) {
        core.onServiceDetached(adapter)
        publishSnapshots()
    }

    fun onStartInput(packageName: String, inputType: Int, imeOptions: Int, fieldId: Int) {
        core.onStartInput(packageName, inputType, imeOptions, fieldId)
        publishSnapshots()
    }

    fun onFinishInput() {
        core.onFinishInput()
        publishSnapshots()
    }

    fun onCursorAnchorUpdated(snapshot: ImeCursorAnchorSnapshot) {
        core.onCursorAnchorUpdated(snapshot)
        _cursorAnchorFlow.value = core.currentCursorAnchor()
    }

    /**
     * Synchronously commits UTF-8 text into the currently bound Android `InputConnection` on the
     * service's Main Looper. Returns `false` immediately without hopping threads if the built-in
     * channel IME is not bound to an editable target.
     */
    fun commitTextSync(
        text: String,
        rejectSelfPackage: Boolean = false,
        selfPackageName: String = "",
    ): Boolean {
        if (!core.canCommitNow(text, rejectSelfPackage, selfPackageName)) {
            return false
        }
        return runOnMainSync {
            core.commitTextDirect(text, rejectSelfPackage, selfPackageName)
        }
    }

    fun setComposingTextSync(text: String): Boolean = runOnMainSync {
        core.setComposingTextDirect(text)
    }

    fun finishComposingTextSync(): Boolean = runOnMainSync {
        core.finishComposingTextDirect()
    }

    fun deleteSurroundingTextSync(beforeLength: Int, afterLength: Int): Boolean = runOnMainSync {
        core.deleteSurroundingTextDirect(beforeLength, afterLength)
    }

    fun performEditorActionSync(editorAction: Int): Boolean = runOnMainSync {
        core.performEditorActionDirect(editorAction)
    }

    private fun runOnMainSync(action: () -> Boolean): Boolean {
        val mainLooper = Looper.getMainLooper() ?: return action()
        if (Looper.myLooper() == mainLooper) {
            return runCatching(action).getOrDefault(false)
        }
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                result.set(runCatching(action).getOrDefault(false))
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        return try {
            if (latch.await(MAIN_THREAD_COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                result.get()
            } else {
                false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun publishSnapshots() {
        _editorStateFlow.value = core.currentEditorSnapshot()
        _cursorAnchorFlow.value = core.currentCursorAnchor()
    }
}
