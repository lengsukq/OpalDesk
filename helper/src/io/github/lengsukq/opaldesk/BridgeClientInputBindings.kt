package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.input.CapturedInputEvent
import io.github.lengsukq.opaldesk.input.InputBindingCodec
import io.github.lengsukq.opaldesk.input.InputBindingRule
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol

internal fun BridgeClient.fetchInputBindingsSyncImpl(): List<InputBindingRule> {
    val svc = getService() ?: return _inputBindingsFlow.value
    val json = transact(
        svc,
        BridgeProtocol.TX_GET_INPUT_BINDINGS,
        oneway = false,
        writeArgs = {},
    ) { reply ->
        reply.readString().orEmpty()
    }
    if (json == null) return _inputBindingsFlow.value
    val parsed = InputBindingCodec.decodeRules(json)
    _inputBindingsFlow.value = parsed
    OpalLogStore.i("InputBinding", "Synced ${parsed.size} input binding rule(s) from daemon")
    return parsed
}

internal fun BridgeClient.fetchInputBindingsAsyncImpl(
    onDone: ((List<InputBindingRule>) -> Unit)? = null,
) {
    ioExec.execute {
        val rules = fetchInputBindingsSyncImpl()
        if (onDone != null) {
            mainHandler.post { onDone(rules) }
        }
    }
}

internal fun BridgeClient.saveInputBindingsAsyncImpl(
    rules: List<InputBindingRule>,
    onDone: ((Boolean, List<InputBindingRule>) -> Unit)? = null,
) {
    val normalizedRules = rules.map { it.normalized() }.filter { it.isValid() }
    val payload = InputBindingCodec.encodeRules(normalizedRules)
    _inputBindingsFlow.value = normalizedRules
    ioExec.execute {
        val svc = getService()
        if (svc == null) {
            if (onDone != null) mainHandler.post { onDone(false, normalizedRules) }
            return@execute
        }
        val res = transact(
            svc,
            BridgeProtocol.TX_SET_INPUT_BINDINGS,
            oneway = false,
            writeArgs = { d -> d.writeString(payload) },
        ) { reply ->
            val ok = reply.readInt() != 0
            val normalizedJson = if (reply.dataAvail() > 0) reply.readString().orEmpty() else payload
            ok to InputBindingCodec.decodeRules(normalizedJson)
        }
        val ok = res?.first == true
        val finalRules = res?.second ?: normalizedRules
        _inputBindingsFlow.value = finalRules
        OpalLogStore.i("InputBinding", "Saved ${finalRules.size} input binding rule(s) ok=$ok")
        if (onDone != null) {
            mainHandler.post { onDone(ok, finalRules) }
        }
    }
}

internal fun BridgeClient.resetInputBindingsAsyncImpl(
    onDone: ((Boolean, List<InputBindingRule>) -> Unit)? = null,
) {
    val defaults = InputBindingRule.defaultRules()
    _inputBindingsFlow.value = defaults
    ioExec.execute {
        val svc = getService()
        if (svc == null) {
            if (onDone != null) mainHandler.post { onDone(false, defaults) }
            return@execute
        }
        val res = transact(
            svc,
            BridgeProtocol.TX_SET_INPUT_BINDINGS,
            oneway = false,
            writeArgs = { d -> d.writeString("") },
        ) { reply ->
            val ok = reply.readInt() != 0
            val normalizedJson = if (reply.dataAvail() > 0) reply.readString().orEmpty() else ""
            val parsed = if (normalizedJson.isBlank()) defaults else InputBindingCodec.decodeRules(normalizedJson)
            ok to parsed
        }
        val ok = res?.first == true
        val finalRules = res?.second ?: defaults
        _inputBindingsFlow.value = finalRules
        OpalLogStore.i("InputBinding", "Reset input binding rules to defaults (${finalRules.size}) ok=$ok")
        if (onDone != null) {
            mainHandler.post { onDone(ok, finalRules) }
        }
    }
}

internal fun BridgeClient.controlInputBindingCaptureAsyncImpl(
    op: Int,
    onResult: (Boolean, CapturedInputEvent?) -> Unit,
) {
    ioExec.execute {
        val svc = getService()
        if (svc == null) {
            mainHandler.post { onResult(false, null) }
            return@execute
        }
        val res = transact(
            svc,
            BridgeProtocol.TX_INPUT_BINDING_CAPTURE,
            oneway = false,
            writeArgs = { d -> d.writeInt(op) },
        ) { reply ->
            val active = reply.readInt() != 0
            val capturedJson = if (reply.dataAvail() > 0) reply.readString().orEmpty() else ""
            active to InputBindingCodec.decodeCapturedEvent(capturedJson)
        }
        val active = res?.first == true
        val captured = res?.second
        mainHandler.post { onResult(active, captured) }
    }
}
