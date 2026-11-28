package io.github.lengsukq.opalcast.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.lengsukq.opalcast.R
import io.github.lengsukq.opalcast.policy.ReceiverPolicy
import io.github.lengsukq.opalcast.prefs.ReceiverLocalePolicy
import io.github.lengsukq.opalcast.prefs.ReceiverPrefs

/**
 * Pure Android Framework View card and selector builders extracted from [CastHomeActivity]:
 * section card chrome, codec/theme/language segmented controls, and the About/Project card.
 */
internal fun buildCastSectionCard(
    context: Context,
    pal: OpalCastPalette,
    title: String,
    subtitle: String,
    headerExtra: ((LinearLayout) -> Unit)? = null,
): LinearLayout {
    val dp = { v: Int -> OpalCastTheme.dp(context, v) }
    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = OpalCastTheme.roundedDrawable(context, pal.bgCard, 18, pal.border, 1f)
        setPadding(dp(16), dp(15), dp(16), dp(15))
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    textCol.addView(TextView(context).apply {
        text = title
        setTextColor(pal.inkPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    })
    textCol.addView(TextView(context).apply {
        text = subtitle
        setTextColor(pal.inkMuted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
    })
    row.addView(
        textCol,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
    )
    headerExtra?.invoke(row)
    card.addView(row)
    return card
}

internal fun buildCastCodecSelectorRow(
    context: Context,
    pal: OpalCastPalette,
    onChanged: () -> Unit,
): LinearLayout {
    val dp = { v: Int -> OpalCastTheme.dp(context, v) }
    val cur = ReceiverPrefs.getVideoCodecPref(context)
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    val items = listOf(
        ReceiverPolicy.CODEC_PREF_AUTO to context.getString(R.string.home_codec_auto),
        ReceiverPolicy.CODEC_PREF_H264 to context.getString(R.string.home_codec_h264),
        ReceiverPolicy.CODEC_PREF_HEVC to context.getString(R.string.home_codec_hevc),
    )
    items.forEachIndexed { idx, (pref, label) ->
        val selected = (cur == pref)
        row.addView(
            OpalCastTheme.buildPillButton(
                context,
                label,
                if (selected) pal.accentSoft else pal.bgElevated,
                if (selected) pal.accent else pal.inkSecondary,
                if (selected) pal.accentBorder else pal.border,
                bold = selected,
            ) {
                ReceiverPrefs.setVideoCodecPref(context, pref)
                onChanged()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) leftMargin = dp(8)
            },
        )
    }
    return row
}

internal fun buildCastBitrateSelectorRow(
    context: Context,
    pal: OpalCastPalette,
    onChanged: () -> Unit,
): HorizontalScrollView {
    val dp = { v: Int -> OpalCastTheme.dp(context, v) }
    val cur = ReceiverPrefs.getPreferredBitrateKbps(context)
    val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    ReceiverPrefs.BITRATE_PRESETS_KBPS.forEachIndexed { idx, kbps ->
        val selected = (cur == kbps)
        val label = if (kbps == 0) "自动" else "${kbps / 1000}M"
        row.addView(
            OpalCastTheme.buildPillButton(
                context,
                label,
                if (selected) pal.accentSoft else pal.bgElevated,
                if (selected) pal.accent else pal.inkSecondary,
                if (selected) pal.accentBorder else pal.border,
                bold = selected,
            ) {
                ReceiverPrefs.setPreferredBitrateKbps(context, kbps)
                onChanged()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (idx > 0) leftMargin = dp(7)
            },
        )
    }
    scroll.addView(row)
    return scroll
}

internal fun buildCastThemeSelectorRow(
    context: Context,
    pal: OpalCastPalette,
    onChanged: () -> Unit,
): LinearLayout {
    val dp = { v: Int -> OpalCastTheme.dp(context, v) }
    val cur = ReceiverPrefs.getThemeMode(context)
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    val items = listOf(
        ReceiverPrefs.THEME_SYSTEM to context.getString(R.string.home_theme_system),
        ReceiverPrefs.THEME_LIGHT to context.getString(R.string.home_theme_light),
        ReceiverPrefs.THEME_DARK to context.getString(R.string.home_theme_dark),
    )
    items.forEachIndexed { idx, (mode, label) ->
        val selected = (cur == mode)
        row.addView(
            OpalCastTheme.buildPillButton(
                context,
                label,
                if (selected) pal.accentSoft else pal.bgElevated,
                if (selected) pal.accent else pal.inkSecondary,
                if (selected) pal.accentBorder else pal.border,
                bold = selected,
            ) {
                ReceiverPrefs.setThemeMode(context, mode)
                onChanged()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) leftMargin = dp(8)
            },
        )
    }
    return row
}

internal fun buildCastLanguageSelectorRow(
    activity: Activity,
    pal: OpalCastPalette,
): LinearLayout {
    val dp = { v: Int -> OpalCastTheme.dp(activity, v) }
    val cur = ReceiverPrefs.getLanguagePref(activity)
    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    val items = listOf(
        ReceiverLocalePolicy.LANG_SYSTEM to activity.getString(R.string.lang_follow_system),
        ReceiverLocalePolicy.LANG_ZH_CN to activity.getString(R.string.lang_zh_cn),
        ReceiverLocalePolicy.LANG_EN to activity.getString(R.string.lang_en),
    )
    items.forEachIndexed { idx, (pref, label) ->
        val selected = (cur == pref)
        row.addView(
            OpalCastTheme.buildPillButton(
                activity,
                label,
                if (selected) pal.accentSoft else pal.bgElevated,
                if (selected) pal.accent else pal.inkSecondary,
                if (selected) pal.accentBorder else pal.border,
                bold = selected,
            ) {
                if (ReceiverPrefs.getLanguagePref(activity) != pref) {
                    ReceiverPrefs.setLanguagePref(activity, pref)
                    activity.recreate()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) leftMargin = dp(8)
            },
        )
    }
    return row
}

internal fun buildCastProjectAboutCard(
    activity: Activity,
    pal: OpalCastPalette,
): LinearLayout {
    val dp = { v: Int -> OpalCastTheme.dp(activity, v) }
    val card = buildCastSectionCard(
        activity,
        pal,
        activity.getString(R.string.home_about_title),
        activity.getString(R.string.home_about_subtitle),
    )

    // Host sender (OpalDesk) introduction box
    val hostBox = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = OpalCastTheme.roundedDrawable(activity, pal.bgElevated, 14, pal.accentBorder, 0.9f)
        setPadding(dp(13), dp(12), dp(13), dp(12))
    }
    val hostTitleRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    hostTitleRow.addView(
        TextView(activity).apply {
            text = activity.getString(R.string.home_about_host_title)
            setTextColor(pal.inkPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
    )
    hostTitleRow.addView(
        OpalCastTheme.buildBadge(
            activity,
            activity.getString(R.string.home_about_host_badge),
            pal.accentSoft,
            pal.accent,
            pal.accentBorder,
        ),
    )
    hostBox.addView(hostTitleRow)

    hostBox.addView(
        TextView(activity).apply {
            text = activity.getString(R.string.home_about_host_desc)
            setTextColor(pal.inkSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f)
            setLineSpacing(dp(3).toFloat(), 1f)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        },
    )
    hostBox.addView(
        TextView(activity).apply {
            text = activity.getString(R.string.home_about_host_tip)
            setTextColor(pal.inkMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        },
    )
    card.addView(
        hostBox,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        },
    )

    // Showcase / Intro page (includes download links)
    card.addView(
        buildCastProjectLinkBlock(
            activity = activity,
            pal = pal,
            title = activity.getString(R.string.home_about_intro_title),
            url = ReceiverPolicy.PROJECT_INTRO_URL,
            openLabel = activity.getString(R.string.home_about_intro_open),
            openBg = pal.accent,
            openText = Color.WHITE,
            openBorder = Color.TRANSPARENT,
        ),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        },
    )

    // GitHub open-source repository
    card.addView(
        buildCastProjectLinkBlock(
            activity = activity,
            pal = pal,
            title = activity.getString(R.string.home_about_github_title),
            url = ReceiverPolicy.PROJECT_GITHUB_URL,
            openLabel = activity.getString(R.string.home_about_github_open),
            openBg = pal.secondarySoft,
            openText = pal.secondaryAccent,
            openBorder = pal.secondaryAccent,
        ),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(9)
        },
    )

    // QQ community group entry (matching host OpalDesk app)
    val qqBox = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = OpalCastTheme.roundedDrawable(activity, pal.bgElevated, 12, pal.border, 0.8f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    qqBox.addView(TextView(activity).apply {
        text = activity.getString(R.string.home_about_qq_title_fmt, ReceiverPolicy.COMMUNITY_QQ_GROUP_NUMBER)
        setTextColor(pal.inkPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    })
    qqBox.addView(
        TextView(activity).apply {
            text = activity.getString(R.string.home_about_qq_subtitle)
            setTextColor(pal.inkMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        },
    )
    val qqBtnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    qqBtnRow.addView(
        OpalCastTheme.buildPillButton(
            activity,
            activity.getString(R.string.home_about_qq_join),
            pal.secondarySoft,
            pal.secondaryAccent,
            pal.secondaryAccent,
        ) {
            joinCastCommunityQqGroup(activity)
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
    )
    qqBtnRow.addView(
        OpalCastTheme.buildPillButton(
            activity,
            activity.getString(R.string.home_about_qq_copy),
            pal.chipBg,
            pal.inkSecondary,
            pal.border,
            bold = false,
        ) {
            copyCastTextToClipboard(activity, "OpalDesk QQ Group", ReceiverPolicy.COMMUNITY_QQ_GROUP_NUMBER)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    qqBox.addView(
        qqBtnRow,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        },
    )
    card.addView(
        qqBox,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(9)
        },
    )

    return card
}

internal fun joinCastCommunityQqGroup(activity: Activity) {
    val groupNo = ReceiverPolicy.COMMUNITY_QQ_GROUP_NUMBER
    runCatching {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk QQ Group", groupNo))
    }
    val uri = Uri.parse(ReceiverPolicy.buildQqGroupDeepLink(groupNo))
    val launched = runCatching {
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    val msg = if (launched) {
        activity.getString(R.string.home_about_qq_opening_fmt, groupNo)
    } else {
        activity.getString(R.string.home_about_qq_copied_fmt, groupNo)
    }
    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}

private fun buildCastProjectLinkBlock(
    activity: Activity,
    pal: OpalCastPalette,
    title: String,
    url: String,
    openLabel: String,
    openBg: Int,
    openText: Int,
    openBorder: Int,
): LinearLayout {
    val dp = { v: Int -> OpalCastTheme.dp(activity, v) }
    val box = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = OpalCastTheme.roundedDrawable(activity, pal.bgElevated, 12, pal.border, 0.8f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    box.addView(TextView(activity).apply {
        text = title
        setTextColor(pal.inkPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    })
    box.addView(
        TextView(activity).apply {
            text = url
            setTextColor(pal.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        },
    )
    val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    btnRow.addView(
        OpalCastTheme.buildPillButton(activity, openLabel, openBg, openText, openBorder) {
            openCastExternalUrl(activity, url)
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) },
    )
    btnRow.addView(
        OpalCastTheme.buildPillButton(
            activity,
            activity.getString(R.string.home_about_copy_url),
            pal.chipBg,
            pal.inkSecondary,
            pal.border,
            bold = false,
        ) {
            copyCastTextToClipboard(activity, title, url)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    box.addView(
        btnRow,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        },
    )
    return box
}

private fun openCastExternalUrl(activity: Activity, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    } catch (_: Throwable) {
        copyCastTextToClipboard(activity, activity.getString(R.string.home_about_link_label), url)
        Toast.makeText(activity, activity.getString(R.string.home_about_no_browser_toast), Toast.LENGTH_LONG).show()
    }
}

private fun copyCastTextToClipboard(context: Context, label: String, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, context.getString(R.string.common_copied_fmt, text), Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
