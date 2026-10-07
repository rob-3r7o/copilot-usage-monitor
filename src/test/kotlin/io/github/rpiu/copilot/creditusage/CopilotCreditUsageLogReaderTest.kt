package io.github.rpiu.copilot.creditusage

import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CopilotCreditUsageLogReaderTest {
    @Test
    fun `keeps last usage when updated log has no quota entry`() {
        val log = Files.createTempFile("copilot-credit-usage", ".log")
        try {
            val reader = CopilotCreditUsageLogReader(log)
            assertEquals(null, reader.readLatest())

            Files.writeString(
                log,
                """
                2026-10-07 09:08:30,954 [8610] FINER - Copilot - [stdout] {"jsonrpc":"2.0","method":"copilot/quotaChange","params":{"premium_interactions":{"quota":2500,"used":462.5,"percentRemaining":81.5}}}
                """.trimIndent()
            )
            val usage = reader.readLatest()

            Files.writeString(log, "IDE log rotated; no quota entry has been written yet")

            assertEquals(usage, reader.readLatest())
        } finally {
            Files.deleteIfExists(log)
        }
    }

    @Test
    fun `reads current quota change format from prefixed idea log line`() {
        val log = Files.createTempFile("copilot-credit-usage", ".log")
        try {
            Files.writeString(
                log,
                """
                2026-10-07 09:08:30,954 [8610] FINER - Copilot - [stdout] {"jsonrpc":"2.0","method":"copilot/quotaChange","params":{"premium_interactions":{"quota":2500,"used":462.5,"percentRemaining":81.5}}}
                """.trimIndent()
            )

            val usage = CopilotCreditUsageLogReader(log).readLatest()

            assertEquals(
                CopilotCreditUsage(
                    quotaRemaining = BigDecimal("2037.5"),
                    entitlement = BigDecimal("2500"),
                    percentRemaining = BigDecimal("81.5")
                ),
                usage
            )
        } finally {
            Files.deleteIfExists(log)
        }
    }

    @Test
    fun `reads legacy nested chunk format`() {
        val log = Files.createTempFile("copilot-credit-usage", ".log")
        try {
            Files.writeString(
                log,
                """
                2026-10-07 09:08:30,954 [8610] FINER - Copilot - {"jsonrpc":"2.0","params":{"value":{"chunk":"{\"quota_snapshots\":{\"premium_interactions\":{\"quota_remaining\":2037.5,\"entitlement\":2500,\"percent_remaining\":81.5}}}"}}}
                """.trimIndent()
            )

            val usage = CopilotCreditUsageLogReader(log).readLatest()

            assertEquals(BigDecimal("2037.5"), usage?.quotaRemaining)
            assertEquals(BigDecimal("2500"), usage?.entitlement)
            assertEquals(BigDecimal("81.5"), usage?.percentRemaining)
        } finally {
            Files.deleteIfExists(log)
        }
    }
}
