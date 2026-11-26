package io.github.lengsukq.opaldesk

import com.sun.net.httpserver.HttpServer
import io.github.lengsukq.opaldesk.diagnostics.FeedbackBatchSyncResult
import io.github.lengsukq.opaldesk.diagnostics.FeedbackHistoryLogic
import io.github.lengsukq.opaldesk.diagnostics.FeedbackLookupSyncResult
import io.github.lengsukq.opaldesk.diagnostics.FeedbackRecord
import io.github.lengsukq.opaldesk.diagnostics.FeedbackRemoteItem
import io.github.lengsukq.opaldesk.diagnostics.FeedbackResolutionStatus
import io.github.lengsukq.opaldesk.diagnostics.FeedbackStatusClient
import io.github.lengsukq.opaldesk.diagnostics.FeedbackStatusParser
import io.github.lengsukq.opaldesk.diagnostics.FeedbackVersionRelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class FeedbackTrackingTest {

    @Test
    fun extractSemverAndEvaluateVersionRelationCoversAllStates() {
        assertEquals("1.0.16", FeedbackStatusParser.extractSemverFromSentryRelease("io.github.lengsukq.opaldesk@1.0.16+10016"))
        assertEquals("1.0.18-beta", FeedbackStatusParser.extractSemverFromSentryRelease("v1.0.18-beta"))
        assertEquals("", FeedbackStatusParser.extractSemverFromSentryRelease("untagged"))

        val openRec = FeedbackRecord(
            eventId = "11111111222222223333333344444444",
            shortCode = "11111111",
            summary = "test",
            reportedVersion = "1.0.15",
            createdAtMs = 1000L,
            status = FeedbackResolutionStatus.OPEN,
        )
        assertEquals(
            FeedbackVersionRelation.NOT_FIXED_YET,
            FeedbackStatusParser.evaluateVersionRelation("1.0.15", openRec),
        )

        val fixedNewer = openRec.copy(
            status = FeedbackResolutionStatus.FIXED_IN_VERSION,
            fixedVersion = "1.0.17",
        )
        assertEquals(
            FeedbackVersionRelation.FIXED_IN_NEWER_VERSION,
            FeedbackStatusParser.evaluateVersionRelation("1.0.15", fixedNewer),
        )
        assertEquals(
            FeedbackVersionRelation.FIXED_IN_CURRENT_OR_OLDER,
            FeedbackStatusParser.evaluateVersionRelation("1.0.17", fixedNewer),
        )
        assertEquals(
            FeedbackVersionRelation.FIXED_IN_CURRENT_OR_OLDER,
            FeedbackStatusParser.evaluateVersionRelation("1.0.18", fixedNewer),
        )
    }

    @Test
    fun parserRoundTripsLocalHistoryAndRemoteBatchJson() {
        val sampleBatch = """
            {
              "schema": 1,
              "generatedAt": "2026-11-26T06:00:00Z",
              "items": {
                "a1b2c3d4e5f64789a0b1c2d3e4f56789": {
                  "shortCode": "A1B2C3D4",
                  "status": "fixed_in_version",
                  "fixedVersion": "io.github.lengsukq.opaldesk@1.0.16+10016",
                  "replyNote": "已修复 UHID 触摸映射",
                  "updatedAt": "2026-11-26T05:50:00Z"
                }
              }
            }
        """.trimIndent()
        val parsed = FeedbackStatusParser.parseBatchStatusJson(sampleBatch)
        assertNotNull(parsed)
        parsed!!
        assertEquals(1, parsed.schema)
        val item = parsed.items["a1b2c3d4e5f64789a0b1c2d3e4f56789"]
        assertNotNull(item)
        assertEquals(FeedbackResolutionStatus.FIXED_IN_VERSION, item!!.status)
        assertEquals("1.0.16", item.fixedVersion)
        assertEquals("已修复 UHID 触摸映射", item.replyNote)

        val records = listOf(
            FeedbackRecord(
                eventId = "a1b2c3d4e5f64789a0b1c2d3e4f56789",
                shortCode = "A1B2C3D4",
                summary = "外接屏\"拖动\"异常",
                reportedVersion = "1.0.15",
                createdAtMs = 1700000000000L,
                status = FeedbackResolutionStatus.FIXED_IN_VERSION,
                fixedVersion = "1.0.16",
                replyNote = "请升级验证",
                lastCheckedAtMs = 1700000005000L,
                remoteUpdatedAt = "2026-11-26T05:50:00Z",
            ),
        )
        val json = FeedbackStatusParser.serializeRecordsJson(records)
        val restored = FeedbackStatusParser.parseRecordsJson(json)
        assertEquals(1, restored.size)
        assertEquals(records[0], restored[0])
    }

    @Test
    fun historyLogicCapsAtFiftyMergesRemoteAndRespectsAutoRefreshThrottle() {
        var list = emptyList<FeedbackRecord>()
        for (i in 1..55) {
            val hexSuffix = String.format("%08x", i)
            val eid = "${hexSuffix}000000000000000000000000"
            list = FeedbackHistoryLogic.recordSubmitted(
                existing = list,
                eventId = eid,
                shortCode = hexSuffix.uppercase(),
                problemDescription = "issue $i\nwith second line",
                reportedVersion = "v1.0.15",
                createdAtMs = 1000L * i,
            )
        }
        assertEquals(FeedbackHistoryLogic.MAX_STORED_RECORDS, list.size)
        assertEquals("issue 55", list.first().summary)
        assertEquals("1.0.15", list.first().reportedVersion)

        val topId = list.first().eventId
        val merged = FeedbackHistoryLogic.mergeRemoteStatuses(
            existing = list,
            remoteItems = mapOf(
                topId to FeedbackRemoteItem(
                    eventId = topId,
                    shortCode = list.first().shortCode,
                    status = FeedbackResolutionStatus.FIXED_IN_VERSION,
                    fixedVersion = "1.0.16",
                    replyNote = "已解决",
                    updatedAt = "2026-11-26T06:00:00Z",
                ),
            ),
            checkedAtMs = 200_000L,
        )
        assertEquals(FeedbackResolutionStatus.FIXED_IN_VERSION, merged.first().status)
        assertEquals("1.0.16", merged.first().fixedVersion)
        assertEquals("issue 55", merged.first().summary)

        assertFalse(FeedbackHistoryLogic.shouldAutoRefresh(merged, nowMs = 200_000L + 60_000L))
        assertTrue(FeedbackHistoryLogic.shouldAutoRefresh(merged, nowMs = 200_000L + FeedbackHistoryLogic.AUTO_REFRESH_INTERVAL_MS))

        val terminalOnly = listOf(merged.first())
        assertFalse(FeedbackHistoryLogic.shouldAutoRefresh(terminalOnly, nowMs = 9_999_999L))

        val upserted = FeedbackHistoryLogic.upsertLookedUpRecord(
            existing = terminalOnly,
            remoteItem = FeedbackRemoteItem(
                eventId = "a1b2c3d4e5f64789a0b1c2d3e4f56789",
                shortCode = "A1B2C3D4",
                status = FeedbackResolutionStatus.FIXED_PENDING_RELEASE,
                summary = "感觉好像是扩展屏没有启动成功。",
                reportedVersion = "1.0.16",
            ),
            checkedAtMs = 300_000L,
        )
        assertEquals(2, upserted.size)
        assertEquals("A1B2C3D4", upserted.first().shortCode)
        assertEquals("感觉好像是扩展屏没有启动成功。", upserted.first().summary)
        assertEquals("1.0.16", upserted.first().reportedVersion)
        assertTrue(upserted.first().fromCodeLookup)
    }

    @Test
    fun endpointResolverEnforcesHttpsAndDerivesOriginFromTunnel() {
        assertEquals(
            "https://feedback.example.invalid",
            FeedbackHistoryLogic.resolveStatusBaseUrl("", "https://feedback.example.invalid/envelope"),
        )
        assertEquals(
            "https://custom.example.com/api",
            FeedbackHistoryLogic.resolveStatusBaseUrl("https://custom.example.com/api/", "https://other.example.com/envelope"),
        )
        assertEquals(
            "",
            FeedbackHistoryLogic.resolveStatusBaseUrl("http://insecure.example.com", ""),
        )
    }

    @Test
    fun statusClientHandlesBatchLookupNotFoundAndRateLimitOverHttp() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val eid = "a1b2c3d4e5f64789a0b1c2d3e4f56789"
        try {
            server.createContext("/v1/feedback/status") { ex ->
                val query = ex.requestURI.rawQuery.orEmpty()
                assertTrue(query.contains("installation_id=inst-test-01"))
                assertTrue(query.contains("event_ids=$eid"))
                val resp = """
                    {
                      "schema": 1,
                      "generatedAt": "2026-11-26T07:00:00Z",
                      "items": {
                        "$eid": {
                          "shortCode": "A1B2C3D4",
                          "status": "fixed_in_version",
                          "fixedVersion": "1.0.16",
                          "replyNote": "已修复扩展屏连接问题",
                          "updatedAt": "2026-11-26T06:55:00Z"
                        }
                      }
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            }
            server.createContext("/v1/feedback/lookup") { ex ->
                val q = ex.requestURI.rawQuery.orEmpty()
                when {
                    q.contains("code=A1B2C3D4") -> {
                        val resp = """
                            {
                              "schema": 1,
                              "found": true,
                              "item": {
                                "eventId": "$eid",
                                "shortCode": "A1B2C3D4",
                                "status": "fixed_pending_release",
                                "fixedVersion": "",
                                "replyNote": "将在下一版发布",
                                "updatedAt": "2026-11-26T06:55:00Z"
                              }
                            }
                        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                        ex.sendResponseHeaders(200, resp.size.toLong())
                        ex.responseBody.use { it.write(resp) }
                    }
                    q.contains("code=DEADBEEF") -> {
                        val resp = """{"schema":1,"found":false,"item":null}""".toByteArray(StandardCharsets.UTF_8)
                        ex.sendResponseHeaders(404, resp.size.toLong())
                        ex.responseBody.use { it.write(resp) }
                    }
                    else -> {
                        ex.sendResponseHeaders(429, -1)
                    }
                }
            }
            server.start()
            val baseUrl = "http://127.0.0.1:${server.address.port}"

            val batchRes = FeedbackStatusClient.fetchBatchStatuses(
                baseUrl = baseUrl,
                installationId = "inst-test-01",
                eventIds = listOf(eid),
                allowLocalhostForTests = true,
            )
            assertTrue(batchRes is FeedbackBatchSyncResult.Success)
            val success = batchRes as FeedbackBatchSyncResult.Success
            assertEquals("1.0.16", success.items[eid]?.fixedVersion)
            assertEquals("已修复扩展屏连接问题", success.items[eid]?.replyNote)

            val foundRes = FeedbackStatusClient.lookupByShortCode(baseUrl, "#a1b2c3d4", allowLocalhostForTests = true)
            assertTrue(foundRes is FeedbackLookupSyncResult.Found)
            assertEquals(FeedbackResolutionStatus.FIXED_PENDING_RELEASE, (foundRes as FeedbackLookupSyncResult.Found).item.status)

            val notFoundRes = FeedbackStatusClient.lookupByShortCode(baseUrl, "DEADBEEF", allowLocalhostForTests = true)
            assertTrue(notFoundRes is FeedbackLookupSyncResult.NotFound)

            val rateLimitRes = FeedbackStatusClient.lookupByShortCode(baseUrl, "AAAA1111", allowLocalhostForTests = true)
            assertTrue(rateLimitRes is FeedbackLookupSyncResult.Failure)
            assertEquals(429, (rateLimitRes as FeedbackLookupSyncResult.Failure).httpStatus)

            val cleartextBlocked = FeedbackStatusClient.lookupByShortCode("http://evil.example.com", "A1B2C3D4")
            assertTrue(cleartextBlocked is FeedbackLookupSyncResult.Failure)
        } finally {
            server.stop(0)
        }
    }
}
