package io.github.lengsukq.opaldesk.widgets

import android.content.Context
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent reactive store for desktop widget instances backed by [android.content.SharedPreferences].
 * Isolates widget layout & configuration per workspace profile slot (`0..4`), keeping slot 0
 * backward-compatible with legacy keys while storing other slots under `widgets_slot_<slotId>`.
 */
object DesktopWidgetStore {
    private const val PREFS_NAME = "opaldesk_desktop_widgets"
    private const val KEY_INSTANCES_JSON = "widget_instances_json_v1"
    private const val KEY_INITIALIZED = "widgets_initialized_v1"
    private const val SLOT_KEY_PREFIX = "widgets_slot_"

    private val lock = Any()
    private val idSeq = AtomicInteger(1)

    @Volatile
    private var hydrated = false

    @Volatile
    private var currentSlotId = 0

    private val _activeSlotIdFlow = MutableStateFlow(0)
    val activeSlotIdFlow: StateFlow<Int> = _activeSlotIdFlow.asStateFlow()

    private val _instancesFlow = MutableStateFlow<List<DesktopWidgetInstance>>(emptyList())
    val instancesFlow: StateFlow<List<DesktopWidgetInstance>> = _instancesFlow.asStateFlow()

    fun activeSlotId(): Int = currentSlotId

    fun defaultInstances(): List<DesktopWidgetInstance> = emptyList()

    fun hydrate(context: Context, slotId: Int? = null) {
        val targetSlot = clampSlotId(slotId ?: ProfileConfigStore.stateFlow.value.activeSlotId)
        if (hydrated && currentSlotId == targetSlot) return
        synchronized(lock) {
            if (hydrated && currentSlotId == targetSlot) return
            loadSlotLocked(context, targetSlot)
            hydrated = true
        }
    }

    /**
     * Switches the active widget layout to [slotId] (`0..4`), loading that slot's persisted widgets.
     */
    fun switchSlot(context: Context, slotId: Int) {
        val targetSlot = clampSlotId(slotId)
        synchronized(lock) {
            if (hydrated && currentSlotId == targetSlot) return
            loadSlotLocked(context, targetSlot)
            hydrated = true
        }
    }

    /**
     * Saves the current live desktop widget layout into [targetSlotId] (`0..4`) and binds to that slot.
     */
    fun saveToSlot(context: Context, targetSlotId: Int) {
        hydrate(context)
        val targetSlot = clampSlotId(targetSlotId)
        synchronized(lock) {
            currentSlotId = targetSlot
            _activeSlotIdFlow.value = targetSlot
            persistLocked(context, _instancesFlow.value, targetSlot)
        }
    }

    fun loadFromSlot(context: Context, slotId: Int) {
        val targetSlot = clampSlotId(slotId)
        synchronized(lock) {
            loadSlotLocked(context, targetSlot)
            hydrated = true
        }
    }

    fun addWidget(context: Context, type: DesktopWidgetType): DesktopWidgetInstance? {
        hydrate(context)
        synchronized(lock) {
            val current = _instancesFlow.value
            val spec = DesktopWidgetCatalog.specOf(type)
            if (!spec.allowMultiple && current.any { it.type == type }) {
                return null
            }
            val (col, row) = DesktopWidgetGridMath.findNextAvailableSlot(
                existing = current,
                colSpan = spec.defaultColSpan,
                rowSpan = spec.defaultRowSpan,
            )
            val id = "w_${type.name.lowercase(Locale.ROOT)}_${System.currentTimeMillis()}_${idSeq.getAndIncrement()}"
            val created = DesktopWidgetGridMath.clampInstance(
                DesktopWidgetInstance(
                    id = id,
                    type = type,
                    col = col,
                    row = row,
                    colSpan = spec.defaultColSpan,
                    rowSpan = spec.defaultRowSpan,
                    configJson = "{}",
                ),
            )
            persistLocked(context, current + created)
            return created
        }
    }

    fun removeWidget(context: Context, id: String) {
        hydrate(context)
        synchronized(lock) {
            val next = _instancesFlow.value.filterNot { it.id == id }
            if (next.size != _instancesFlow.value.size) {
                persistLocked(context, next)
            }
        }
    }

    fun moveWidget(context: Context, id: String, col: Int, row: Int) {
        hydrate(context)
        synchronized(lock) {
            val next = _instancesFlow.value.map { item ->
                if (item.id == id) {
                    DesktopWidgetGridMath.clampInstance(item.copy(col = col, row = row))
                } else {
                    item
                }
            }
            persistLocked(context, next)
        }
    }

    fun resizeWidget(context: Context, id: String, colSpan: Int, rowSpan: Int) {
        hydrate(context)
        synchronized(lock) {
            val next = _instancesFlow.value.map { item ->
                if (item.id == id) {
                    DesktopWidgetGridMath.clampInstance(item.copy(colSpan = colSpan, rowSpan = rowSpan))
                } else {
                    item
                }
            }
            persistLocked(context, next)
        }
    }

    fun updateWidgetConfig(context: Context, id: String, configJson: String) {
        hydrate(context)
        synchronized(lock) {
            val safeJson = configJson.ifBlank { "{}" }
            val next = _instancesFlow.value.map { item ->
                if (item.id == id) item.copy(configJson = safeJson) else item
            }
            persistLocked(context, next)
        }
    }

    fun resetToDefaults(context: Context) {
        synchronized(lock) {
            hydrated = true
            persistLocked(context, defaultInstances())
        }
    }

    private fun loadSlotLocked(context: Context, slotId: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initKey = initKeyForSlot(slotId)
        val jsonKey = jsonKeyForSlot(slotId)
        val alreadyInit = prefs.getBoolean(initKey, false)
        val loaded = if (!alreadyInit) {
            val defaults = defaultInstances()
            prefs.edit()
                .putBoolean(initKey, true)
                .putString(jsonKey, DesktopWidgetGridMath.serializeInstances(defaults))
                .apply()
            defaults
        } else {
            val raw = prefs.getString(jsonKey, "[]").orEmpty()
            DesktopWidgetGridMath.deserializeInstances(raw)
        }
        currentSlotId = slotId
        _activeSlotIdFlow.value = slotId
        _instancesFlow.value = loaded
    }

    private fun persistLocked(
        context: Context,
        instances: List<DesktopWidgetInstance>,
        slotId: Int = currentSlotId,
    ) {
        _instancesFlow.value = instances
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(initKeyForSlot(slotId), true)
            .putString(jsonKeyForSlot(slotId), DesktopWidgetGridMath.serializeInstances(instances))
            .apply()
    }

    private fun clampSlotId(slotId: Int): Int = slotId.coerceIn(0, ProfileConfigStore.SLOT_COUNT - 1)

    private fun jsonKeyForSlot(slotId: Int): String =
        if (slotId == 0) KEY_INSTANCES_JSON else "$SLOT_KEY_PREFIX$slotId"

    private fun initKeyForSlot(slotId: Int): String =
        if (slotId == 0) KEY_INITIALIZED else "${SLOT_KEY_PREFIX}${slotId}_init"
}
