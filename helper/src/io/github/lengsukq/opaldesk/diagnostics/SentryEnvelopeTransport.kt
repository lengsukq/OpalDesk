package io.github.lengsukq.opaldesk.diagnostics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class ParsedSentryDsn(
    val rawDsn: String,
    val publicKey: String,
    val projectId: String,
    val envelopeEndpointUrl: String,
)

data class ManualReportPayload(
    val eventId: String = UUID.randomUUID().toString().replace("-", ""),
    val installationId: String,
    val reporterName: String,
    val problemDescription: String,
    val appVersion: String,
    val appVersionCode: Int,
    val environment: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val androidApiLevel: Int,
    val daemonConnected: Boolean,
    val helperLogCount: Int,
    val daemonLogCount: Int,
    val attachmentFilename: String,
    val compressedLogBytes: ByteArray,
    val sentAtIso: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
    val kind: FeedbackKind = FeedbackKind.BUG,
    val includeLogs: Boolean = true,
)

sealed class ManualUploadResult {
    data class Success(
        val eventId: String,
        val shortCode: String,
        val httpStatus: Int,
    ) : ManualUploadResult()

    data class Failure(
        val reason: String,
        val httpStatus: Int? = null,
    ) : ManualUploadResult()
}

/**
 * Pure JVM-testable Sentry Envelope builder and synchronous HTTP uploader.
 * Guarantees that local daily rate-limit counters are only incremented when
 * Sentry (or a Cloudflare Worker Tunnel) explicitly confirms receipt (HTTP 2xx).
 * Never attaches screenshots.
 */
object SentryEnvelopeTransport {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 18_000
    private val RESPONSE_ID_REGEX = Regex(""""id"\s*:\s*"([0-9a-fA-F-]+)"""")

    fun parseDsn(rawDsn: String): ParsedSentryDsn? {
        val trimmed = rawDsn.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val userInfo = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val publicKey = userInfo.substringBefore(':').trim()
        if (publicKey.isEmpty()) return null
        val rawPath = uri.path?.trim('/') ?: return null
        if (rawPath.isEmpty()) return null
        val segments = rawPath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        val projectId = segments.last()
        val pathPrefix = if (segments.size > 1) {
            "/" + segments.dropLast(1).joinToString("/")
        } else {
            ""
        }
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val endpoint = "$scheme://$host$portPart$pathPrefix/api/$projectId/envelope/"
        return ParsedSentryDsn(
            rawDsn = trimmed,
            publicKey = publicKey,
            projectId = projectId,
            envelopeEndpointUrl = endpoint,
        )
    }

    fun buildEnvelopeBytes(
        dsn: String,
        payload: ManualReportPayload,
    ): ByteArray {
        val cleanEventId = payload.eventId.replace("-", "").lowercase(Locale.ROOT)
        val cleanReporter = DiagnosticSanitizer.sanitizeText(payload.reporterName.trim())
            .ifEmpty { "anonymous" }
            .take(64)
        val cleanDescription = DiagnosticSanitizer.sanitizeText(payload.problemDescription.trim())
            .take(2000)
        val isFeature = payload.kind == FeedbackKind.FEATURE
        val hasLogAttachment = payload.includeLogs && payload.compressedLogBytes.isNotEmpty()
        val headline = when {
            isFeature && cleanDescription.isNotEmpty() -> {
                val firstLine = cleanDescription.lineSequence().firstOrNull().orEmpty().take(90)
                "[Feature Request] $firstLine"
            }
            isFeature -> "[Feature Request] Feature suggestion from $cleanReporter"
            cleanDescription.isNotEmpty() -> {
                val firstLine = cleanDescription.lineSequence().firstOrNull().orEmpty().take(90)
                "[User Report] $firstLine"
            }
            else -> "[User Report] Manual diagnostic log from $cleanReporter"
        }
        val eventLevel = if (isFeature) "info" else "error"

        val envelopeHeader = buildString {
            append('{')
            append("\"event_id\":").append(jsonString(cleanEventId)).append(',')
            append("\"dsn\":").append(jsonString(dsn)).append(',')
            append("\"sent_at\":").append(jsonString(payload.sentAtIso)).append(',')
            append("\"sdk\":{\"name\":\"opaldesk.android.manual\",\"version\":")
                .append(jsonString(payload.appVersion)).append('}')
            append('}')
        }

        val eventJson = buildString {
            append('{')
            append("\"event_id\":").append(jsonString(cleanEventId)).append(',')
            append("\"timestamp\":").append(jsonString(payload.sentAtIso)).append(',')
            append("\"level\":").append(jsonString(eventLevel)).append(',')
            append("\"platform\":\"java\",")
            append("\"logger\":\"opaldesk.manual_report\",")
            append("\"release\":").append(jsonString("io.github.lengsukq.opaldesk@${payload.appVersion}+${payload.appVersionCode}")).append(',')
            append("\"environment\":").append(jsonString(payload.environment)).append(',')
            if (isFeature) {
                // Keep each feature suggestion isolated in its own Sentry issue so maintainer replies
                // and implementation versions stay scoped per user request.
                append("\"fingerprint\":[\"opaldesk-feature\",").append(jsonString(cleanEventId)).append("],")
            }
            append("\"message\":{\"formatted\":").append(jsonString(headline)).append("},")
            append("\"user\":{")
            append("\"id\":").append(jsonString(payload.installationId)).append(',')
            append("\"username\":").append(jsonString(cleanReporter))
            append("},")
            append("\"tags\":{")
            append("\"installation_id\":").append(jsonString(payload.installationId)).append(',')
            append("\"feedback_code\":").append(jsonString(formatShortCode(cleanEventId))).append(',')
            append("\"report_source\":\"manual_user_report\",")
            append("\"feedback_kind\":").append(jsonString(payload.kind.wireValue)).append(',')
            append("\"app_version\":").append(jsonString(payload.appVersion)).append(',')
            append("\"android_api\":").append(jsonString(payload.androidApiLevel.toString())).append(',')
            append("\"device_model\":").append(jsonString(payload.deviceModel)).append(',')
            append("\"daemon_connected\":").append(jsonString(payload.daemonConnected.toString()))
            append("},")
            append("\"contexts\":{")
            append("\"device\":{")
            append("\"manufacturer\":").append(jsonString(payload.deviceManufacturer)).append(',')
            append("\"model\":").append(jsonString(payload.deviceModel)).append(',')
            append("\"name\":").append(jsonString("${payload.deviceManufacturer} ${payload.deviceModel}".trim()))
            append("},")
            append("\"os\":{")
            append("\"name\":\"Android\",")
            append("\"version\":").append(jsonString(payload.androidVersion)).append(',')
            append("\"api_level\":").append(payload.androidApiLevel)
            append("},")
            append("\"app\":{")
            append("\"app_version\":").append(jsonString(payload.appVersion)).append(',')
            append("\"app_build\":").append(jsonString(payload.appVersionCode.toString()))
            append("}")
            append("},")
            append("\"extra\":{")
            append("\"feedback_kind\":").append(jsonString(payload.kind.wireValue)).append(',')
            append("\"reporter_name\":").append(jsonString(cleanReporter)).append(',')
            append("\"user_description\":").append(jsonString(cleanDescription.ifEmpty { "(未填写)" })).append(',')
            append("\"logs_attached\":").append(hasLogAttachment).append(',')
            append("\"helper_log_count\":").append(if (hasLogAttachment) payload.helperLogCount else 0).append(',')
            append("\"daemon_log_count\":").append(if (hasLogAttachment) payload.daemonLogCount else 0).append(',')
            append("\"log_gzip_bytes\":").append(if (hasLogAttachment) payload.compressedLogBytes.size else 0).append(',')
            append("\"screenshot_attached\":false")
            append('}')
            append('}')
        }

        val eventBytes = eventJson.toByteArray(StandardCharsets.UTF_8)
        val eventItemHeader = "{\"type\":\"event\",\"content_type\":\"application/json\",\"length\":${eventBytes.size}}"

        val out = ByteArrayOutputStream(eventBytes.size + (if (hasLogAttachment) payload.compressedLogBytes.size else 0) + 512)
        out.write(envelopeHeader.toByteArray(StandardCharsets.UTF_8))
        out.write('\n'.code)
        out.write(eventItemHeader.toByteArray(StandardCharsets.UTF_8))
        out.write('\n'.code)
        out.write(eventBytes)
        out.write('\n'.code)
        if (hasLogAttachment) {
            val attachmentHeader = buildString {
                append("{\"type\":\"attachment\",")
                append("\"filename\":").append(jsonString(payload.attachmentFilename)).append(',')
                append("\"content_type\":\"application/gzip\",")
                append("\"length\":").append(payload.compressedLogBytes.size)
                append('}')
            }
            out.write(attachmentHeader.toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            out.write(payload.compressedLogBytes)
            out.write('\n'.code)
        }
        return out.toByteArray()
    }

    fun sendEnvelope(
        rawDsn: String,
        tunnelUrl: String,
        payload: ManualReportPayload,
    ): ManualUploadResult {
        val parsed = parseDsn(rawDsn)
            ?: return ManualUploadResult.Failure("当前构建未配置 Sentry DSN，暂无法上传（本次未消耗次数）")
        val targetUrl = tunnelUrl.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: parsed.envelopeEndpointUrl
        val body = buildEnvelopeBytes(parsed.rawDsn, payload)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/x-sentry-envelope")
                setRequestProperty(
                    "X-Sentry-Auth",
                    "Sentry sentry_version=7, sentry_client=opaldesk-android-manual/${payload.appVersion}, sentry_key=${parsed.publicKey}",
                )
                setRequestProperty("X-OpalDesk-Installation-Id", payload.installationId)
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { os ->
                os.write(body)
                os.flush()
            }
            val code = conn.responseCode
            val respStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respBody = respStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            when {
                code in 200..299 -> {
                    val confirmedId = extractEventId(respBody)
                        ?: payload.eventId.replace("-", "").lowercase(Locale.ROOT)
                    ManualUploadResult.Success(
                        eventId = confirmedId,
                        shortCode = formatShortCode(confirmedId),
                        httpStatus = code,
                    )
                }
                code == 429 -> ManualUploadResult.Failure(
                    reason = "服务端触发频率限制（HTTP 429），请稍后再试；本次未消耗上传次数",
                    httpStatus = code,
                )
                else -> ManualUploadResult.Failure(
                    reason = "上报接口返回异常（HTTP $code），本次未消耗上传次数",
                    httpStatus = code,
                )
            }
        } catch (e: IOException) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            ManualUploadResult.Failure("网络连接失败或超时（$msg），本次未消耗上传次数")
        } catch (t: Throwable) {
            val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            ManualUploadResult.Failure("上报异常（$msg），本次未消耗上传次数")
        } finally {
            conn?.disconnect()
        }
    }

    fun extractEventId(responseJson: String): String? {
        val match = RESPONSE_ID_REGEX.find(responseJson) ?: return null
        val cleaned = match.groupValues[1].replace("-", "").lowercase(Locale.ROOT)
        return cleaned.takeIf { it.length >= 8 }
    }

    fun formatShortCode(eventId: String): String =
        eventId.replace("-", "").take(8).uppercase(Locale.ROOT)

    internal fun jsonString(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u%04x".format(ch.code))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}
