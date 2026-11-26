package io.github.lengsukq.opaldesk.diagnostics

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class FeedbackBatchSyncResult {
    data class Success(
        val items: Map<String, FeedbackRemoteItem>,
        val generatedAt: String,
    ) : FeedbackBatchSyncResult()

    data class Failure(
        val reason: String,
        val httpStatus: Int? = null,
    ) : FeedbackBatchSyncResult()
}

sealed class FeedbackLookupSyncResult {
    data class Found(val item: FeedbackRemoteItem) : FeedbackLookupSyncResult()
    data object NotFound : FeedbackLookupSyncResult()
    data class Failure(
        val reason: String,
        val httpStatus: Int? = null,
    ) : FeedbackLookupSyncResult()
}

/**
 * Pure JVM-testable read-only HTTPS client for querying feedback resolution status and
 * developer notes from the OpalDesk Cloudflare Worker endpoint.
 */
object FeedbackStatusClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000

    fun fetchBatchStatuses(
        baseUrl: String,
        installationId: String,
        eventIds: List<String>,
        allowLocalhostForTests: Boolean = false,
    ): FeedbackBatchSyncResult {
        val cleanBase = FeedbackHistoryLogic.resolveStatusBaseUrl(
            feedbackStatusUrl = baseUrl,
            sentryTunnelUrl = "",
            allowLocalhostForTests = allowLocalhostForTests,
        )
        if (cleanBase.isEmpty()) {
            return FeedbackBatchSyncResult.Failure("当前构建未配置合法的 HTTPS 状态同步地址")
        }
        val cleanInstall = installationId.trim()
        if (cleanInstall.isEmpty()) {
            return FeedbackBatchSyncResult.Failure("缺少设备安装标识，暂无法查询")
        }
        val normalizedIds = eventIds.asSequence()
            .map { FeedbackHistoryLogic.normalizeEventId(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(FeedbackHistoryLogic.MAX_BATCH_QUERY_IDS)
            .toList()
        if (normalizedIds.isEmpty()) {
            return FeedbackBatchSyncResult.Success(emptyMap(), "")
        }

        val queryUrl = buildString {
            append(cleanBase)
            append("/v1/feedback/status?installation_id=")
            append(URLEncoder.encode(cleanInstall, StandardCharsets.UTF_8.name()))
            append("&event_ids=")
            append(URLEncoder.encode(normalizedIds.joinToString(","), StandardCharsets.UTF_8.name()))
        }
        if (!FeedbackHistoryLogic.isAllowedEndpointUrl(queryUrl, allowLocalhostForTests)) {
            return FeedbackBatchSyncResult.Failure("状态查询地址安全校验未通过")
        }

        return executeGet(
            urlStr = queryUrl,
            onFailure = { reason -> FeedbackBatchSyncResult.Failure(reason) },
        ) { code, body ->
            when {
                code in 200..299 -> {
                    val parsed = FeedbackStatusParser.parseBatchStatusJson(body)
                    if (parsed != null) {
                        FeedbackBatchSyncResult.Success(parsed.items, parsed.generatedAt)
                    } else {
                        FeedbackBatchSyncResult.Failure("状态服务返回格式异常", code)
                    }
                }
                code == 429 -> FeedbackBatchSyncResult.Failure("状态查询过于频繁，请稍后再试", code)
                else -> FeedbackBatchSyncResult.Failure("状态服务响应异常（HTTP $code）", code)
            }
        }
    }

    fun lookupByShortCode(
        baseUrl: String,
        rawCode: String,
        allowLocalhostForTests: Boolean = false,
    ): FeedbackLookupSyncResult {
        val cleanBase = FeedbackHistoryLogic.resolveStatusBaseUrl(
            feedbackStatusUrl = baseUrl,
            sentryTunnelUrl = "",
            allowLocalhostForTests = allowLocalhostForTests,
        )
        if (cleanBase.isEmpty()) {
            return FeedbackLookupSyncResult.Failure("当前构建未配置合法的 HTTPS 状态查询地址")
        }
        val code = FeedbackHistoryLogic.normalizeShortCode(rawCode)
        if (code.isEmpty()) {
            return FeedbackLookupSyncResult.Failure("请输入 8 位反馈码（如 A1B2C3D4）")
        }
        val queryUrl = "$cleanBase/v1/feedback/lookup?code=${URLEncoder.encode(code, StandardCharsets.UTF_8.name())}"
        if (!FeedbackHistoryLogic.isAllowedEndpointUrl(queryUrl, allowLocalhostForTests)) {
            return FeedbackLookupSyncResult.Failure("状态查询地址安全校验未通过")
        }

        return executeGet(
            urlStr = queryUrl,
            onFailure = { reason -> FeedbackLookupSyncResult.Failure(reason) },
        ) { httpCode, body ->
            when {
                httpCode == 404 -> FeedbackLookupSyncResult.NotFound
                httpCode in 200..299 -> {
                    val parsed = FeedbackStatusParser.parseLookupJson(body)
                    when {
                        parsed == null -> FeedbackLookupSyncResult.Failure("查询接口返回格式异常", httpCode)
                        parsed.found && parsed.item != null -> FeedbackLookupSyncResult.Found(parsed.item)
                        else -> FeedbackLookupSyncResult.NotFound
                    }
                }
                httpCode == 429 -> FeedbackLookupSyncResult.Failure("查询过于频繁，请稍后再试", httpCode)
                else -> FeedbackLookupSyncResult.Failure("查询服务响应异常（HTTP $httpCode）", httpCode)
            }
        }
    }

    private inline fun <T> executeGet(
        urlStr: String,
        onFailure: (String) -> T,
        onResponse: (Int, String) -> T,
    ): T {
        var conn: HttpURLConnection? = null
        return try {
            conn = openGetConnection(urlStr)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            onResponse(code, body)
        } catch (e: IOException) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            onFailure("网络请求失败（$detail）")
        } catch (t: Throwable) {
            val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            onFailure("状态查询异常（$detail）")
        } finally {
            conn?.disconnect()
        }
    }

    private fun openGetConnection(urlStr: String): HttpURLConnection =
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpalDesk-Android-FeedbackClient/1.0")
        }
}
