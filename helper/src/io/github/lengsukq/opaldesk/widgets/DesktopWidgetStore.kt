package io.github.lengsukq.opaldesk.widgets

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent reactive store for desktop widget instances backed by [android.content.SharedPreferences].
 */
object DesktopWidgetStore {
    private const val PREFS_NAME = "opaldesk_desktop_widgets"
    private const val KEY_INSTANCES_JSON = "widget_instances_json_v1"
    private const val KEY_INITIALIZED = "widgets_initialized_v1"

    private val lock = Any()
    private val idSeq = AtomicInteger(1)

    @Volatile
    private var hydrated = false

    private val _instancesFlow = MutableStateFlow<List<DesktopWidgetInstance>>(emptyList())
    val instancesFlow: StateFlow<List<DesktopWidgetInstance>> = _instancesFlow.asStateFlow()

    fun defaultInstances(): List<DesktopWidgetInstance> = listOf(
        DesktopWidgetInstance(
            id = "default_desktop_hub",
            type = DesktopWidgetType.DESKTOP_HUB,
            col = 1,
            row = 0,
            colSpan = 6,
            rowSpan = 3,
            configJson = "{}",
        ),
        DesktopWidgetInstance(
            id = "default_clock_timer",
            type = DesktopWidgetType.CLOCK_TIMER,
            col = 7,
            row = 0,
            colSpan = 4,
            rowSpan = 3,
            configJson = "{}",
        ),
        DesktopWidgetInstance(
            id = "default_media_player",
            type = DesktopWidgetType.MEDIA_PLAYER,
            col = 1,
            row = 3,
            colSpan = 6,
            rowSpan = 2,
            configJson = "{}",
        ),
        DesktopWidgetInstance(
            id = "default_todo_checklist",
            type = DesktopWidgetType.TODO_CHECKLIST,
            col = 7,
            row = 3,
            colSpan = 4,
            rowSpan = 2,
            configJson = "{}",
        ),
    )

    fun hydrate(context: Context) {
        if (hydrated) return
        synchronized(lock) {
            if (hydrated) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val alreadyInit = prefs.getBoolean(KEY_INITIALIZED, false)
            val loaded = if (!alreadyInit) {
                val defaults = defaultInstances()
                prefs.edit()
                    .putBoolean(KEY_INITIALIZED, true)
                    .putString(KEY_INSTANCES_JSON, DesktopWidgetGridMath.serializeInstances(defaults))
                    .apply()
                defaults
            } else {
                val raw = prefs.getString(KEY_INSTANCES_JSON, "[]").orEmpty()
                DesktopWidgetGridMath.deserializeInstances(raw)
            }
            _instancesFlow.value = loaded
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

    private fun persistLocked(context: Context, instances: List<DesktopWidgetInstance>) {
        _instancesFlow.value = instances
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putString(KEY_INSTANCES_JSON, DesktopWidgetGridMath.serializeInstances(instances))
            .apply()
    }
}
