package io.github.lengsukq.opalcast.compat

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.policy.UplinkCoalescer
import kotlin.math.roundToInt

/**
 * Complete Android 5.0–17 (API 21–37) platform compatibility layer for OpalCast.
 * Isolates all SDK-version branches cleanly so UI and networking code stays concise and Lint-clean.
 */
object AndroidApiCompat {
    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    @Volatile
    private var cachedDecoderSummary: ReceiverPolicy.HardwareDecoderSummary? = null

    fun primaryAbiLabel(): String =
        ReceiverPolicy.primaryAbiLabel(Build.SUPPORTED_ABIS ?: emptyArray())

    fun primaryAbiInfo(): ReceiverPolicy.PrimaryAbiInfo? =
        ReceiverPolicy.primaryAbiInfo(Build.SUPPORTED_ABIS ?: emptyArray())

    fun overlayWindowType(): Int =
        ReceiverPolicy.overlayWindowType(Build.VERSION.SDK_INT)

    fun canDrawOverlays(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
    }

    fun hasPermission(ctx: Context, permission: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching {
            ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun hasLocalNetworkPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        return hasPermission(ctx, PERMISSION_ACCESS_LOCAL_NETWORK)
    }

    fun requestRuntimePermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.requestPermissions(permissions, requestCode)
        }
    }

    fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    fun probeLocalHardwareDecoders(): ReceiverPolicy.HardwareDecoderSummary {
        cachedDecoderSummary?.let { return it }
        val summary = ReceiverPolicy.summarizeHardwareDecoders(snapshotLocalDecoders())
        cachedDecoderSummary = summary
        return summary
    }

    fun resolvePreferredCastCodec(preference: Int): String =
        ReceiverPolicy.resolvePreferredCastCodec(preference, probeLocalHardwareDecoders().hasHardwareHevc)

    fun selectPreferredDecoder(mime: String): ReceiverPolicy.PreferredDecoderChoice =
        ReceiverPolicy.selectPreferredDecoder(snapshotLocalDecoders(mime), mime)

    private fun snapshotLocalDecoders(filterMime: String? = null): List<ReceiverPolicy.CodecDescriptor> {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val out = ArrayList<ReceiverPolicy.CodecDescriptor>()
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                val types = info.supportedTypes.toList()
                val matchedType = if (filterMime != null) {
                    types.firstOrNull { it.equals(filterMime, ignoreCase = true) } ?: continue
                } else {
                    types.firstOrNull {
                        it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ||
                            it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                    } ?: continue
                }
                val swFlag: Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { info.isSoftwareOnly }.getOrNull()
                } else {
                    null
                }
                val lowLatency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        info.getCapabilitiesForType(matchedType)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                    }.getOrDefault(false)
                } else {
                    false
                }
                out.add(
                    ReceiverPolicy.CodecDescriptor(
                        name = info.name,
                        isEncoder = false,
                        supportedTypes = types,
                        isSoftwareOnlyFlag = swFlag,
                        supportsLowLatency = lowLatency,
                    ),
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun configureSurfaceFrameRate(surface: Surface, fps: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }

    fun applyVideoDecoderPlan(fmt: MediaFormat, plan: UplinkCoalescer.DecoderConfigPlan) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (plan.priorityRealtime) {
                fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (plan.operatingRate > 0) {
                fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, plan.operatingRate)
            }
        }
        if (plan.lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
    }

    fun createAudioTrack(sampleRate: Int, channels: Int, bufferBytes: Int): AudioTrack {
        val chMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(chMask)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                builder.build()
            } catch (_: Throwable) {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }
        } else {
            AudioTrack(attrs, format, bufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        }
    }

    @SuppressLint("MissingPermission")
    fun createAudioRecord(sampleRate: Int, bufferBytes: Int): AudioRecord {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        }
    }

    @Suppress("DEPRECATION")
    fun resolveActivityDisplay(activity: Activity): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.display }.getOrNull()?.let { return it }
        }
        return runCatching { activity.windowManager.defaultDisplay }.getOrNull()
    }

    @Suppress("DEPRECATION")
    fun resolveContextDisplay(ctx: Context): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { ctx.display }.getOrNull()?.let { return it }
        }
        if (ctx is Activity) {
            runCatching { ctx.windowManager.defaultDisplay }.getOrNull()?.let { return it }
        }
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return runCatching { dm?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
            ?: runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay }.getOrNull()
    }

    @Suppress("DEPRECATION")
    fun resolveDisplayRefreshRateHz(display: Display?): Float {
        if (display == null) return 60f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { display.mode?.refreshRate }.getOrNull()?.let { if (it > 0f) return it }
        }
        return runCatching { display.refreshRate }.getOrDefault(60f).takeIf { it > 0f } ?: 60f
    }

    @Suppress("DEPRECATION")
    fun resolveWindowBoundsPx(activity: Activity): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching { activity.windowManager.currentWindowMetrics.bounds }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val realDm = DisplayMetrics()
        val gotReal = runCatching {
            val disp = resolveActivityDisplay(activity) ?: return@runCatching false
            disp.getRealMetrics(realDm)
            realDm.widthPixels > 0 && realDm.heightPixels > 0
        }.getOrDefault(false)
        if (gotReal) return realDm.widthPixels to realDm.heightPixels
        val dm = activity.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    @Suppress("DEPRECATION")
    fun applyPeakRefreshAndImmersive(activity: Activity) {
        val disp = resolveActivityDisplay(activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && disp != null) {
            runCatching {
                val cur = disp.mode
                val peak = disp.supportedModes
                    ?.filter { cur == null || (it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight) }
                    ?.maxByOrNull { it.refreshRate }
                if (peak != null) {
                    val lp = activity.window.attributes
                    lp.preferredDisplayModeId = peak.modeId
                    lp.preferredRefreshRate = peak.refreshRate
                    activity.window.attributes = lp
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                activity.window.setDecorFitsSystemWindows(false)
                activity.window.insetsController?.apply {
                    hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    @Suppress("DEPRECATION")
    fun applyHomeEdgeToEdge(activity: Activity, darkTheme: Boolean, bgColor: Int) {
        val window = activity.window
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor
        }
        val applyBars = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                val appearance = if (darkTheme) 0 else mask
                runCatching {
                    window.insetsController?.apply {
                        show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        setSystemBarsAppearance(appearance, mask)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var flags = window.decorView.systemUiVisibility
                flags = flags and (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    ).inv()
                flags = if (darkTheme) {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = if (darkTheme) {
                        flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    } else {
                        flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    }
                }
                window.decorView.systemUiVisibility = flags
            }
        }
        applyBars()
        window.decorView.post { applyBars() }
    }

    /**
     * Applies system bars (status bar, caption bar, navigation bar) + display cutout insets as
     * safe-area padding across Android 5.0–17 (API 21–37) so content never collides with the
     * status bar or tablet multi-window handle on edge-to-edge Android 15–17.
     */
    @Suppress("DEPRECATION")
    fun applySafeAreaPadding(
        targetView: View,
        baseLeft: Int = 0,
        baseTop: Int = 0,
        baseRight: Int = 0,
        baseBottom: Int = 0,
    ) {
        targetView.setOnApplyWindowInsetsListener { v, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = bars.left
                top = bars.top
                right = bars.right
                bottom = bars.bottom
            } else {
                val cutTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    insets.displayCutout?.safeInsetTop ?: 0
                } else {
                    0
                }
                left = insets.systemWindowInsetLeft
                top = maxOf(insets.systemWindowInsetTop, cutTop)
                right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom)
            insets
        }
        if (targetView.isAttachedToWindow) {
            targetView.requestApplyInsets()
        } else {
            targetView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    v.requestApplyInsets()
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    /**
     * Reports the top display cutout safe inset (in px) so fullscreen immersive toolbars never
     * overlap a punch-hole camera or tablet top cutout.
     */
    fun observeTopCutoutInset(hostView: View, onTopInset: (Int) -> Unit) {
        hostView.setOnApplyWindowInsetsListener { _, insets ->
            val topCutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.displayCutout()).top
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.safeInsetTop ?: 0
            } else {
                0
            }
            onTopInset(topCutout)
            insets
        }
        hostView.requestApplyInsets()
    }

    fun startForegroundServiceCompat(ctx: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.applicationContext.startForegroundService(intent)
        } else {
            ctx.applicationContext.startService(intent)
        }
    }

    fun startForegroundCompat(service: Service, notifId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            service.startForeground(
                notifId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            service.startForeground(notifId, notification)
        }
    }

    @Suppress("DEPRECATION")
    fun stopForegroundRemoveCompat(service: Service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            service.stopForeground(true)
        }
    }

    fun ensureNotificationChannel(
        nm: NotificationManager?,
        channelId: String,
        name: String,
        descriptionText: String,
    ) {
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW).apply {
            description = descriptionText
        }
        nm.createNotificationChannel(ch)
    }

    @Suppress("DEPRECATION")
    fun createNotificationBuilder(ctx: Context, channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, channelId)
        } else {
            Notification.Builder(ctx)
        }

    @Suppress("DEPRECATION")
    fun addNotificationAction(
        ctx: Context,
        builder: Notification.Builder,
        iconRes: Int,
        title: CharSequence,
        pendingIntent: PendingIntent,
    ): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val action = Notification.Action.Builder(Icon.createWithResource(ctx, iconRes), title, pendingIntent).build()
            builder.addAction(action)
        } else {
            builder.addAction(iconRes, title, pendingIntent)
        }
    }

    fun isMouseButtonPress(ev: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS

    fun isMouseButtonRelease(ev: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ev.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE

    fun extractActionButton(ev: MotionEvent): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ev.actionButton else 0

    fun resolveDefaultRefreshRateInt(ctx: Context): Int =
        resolveDisplayRefreshRateHz(resolveContextDisplay(ctx)).roundToInt().coerceIn(60, 120)
}
