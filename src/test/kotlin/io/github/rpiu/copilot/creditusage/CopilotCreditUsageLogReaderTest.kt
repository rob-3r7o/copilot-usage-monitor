package io.github.rpiu.copilot.creditusage

import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CopilotCreditUsageLogReaderTest {
    @Test
    fun `keeps last usage when updated log has no quota entry`() {
        val log = Files.createTempFile("copilot-credit-usage", ".log")
        try {
            val reader = newReader(log)
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

            val usage = newReader(log).readLatest()

            assertEquals(
                CopilotCreditUsage(
                    quotaRemaining = BigDecimal("2037.5"),
                    entitlement = BigDecimal("2500"),
                    percentRemaining = BigDecimal("81.5")
                ),
                usage
            )
            assertEquals(BigDecimal("18.5"), usage?.percentUsed)
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

            val usage = newReader(log).readLatest()

            assertEquals(BigDecimal("2037.5"), usage?.quotaRemaining)
            assertEquals(BigDecimal("2500"), usage?.entitlement)
            assertEquals(BigDecimal("81.5"), usage?.percentRemaining)
            assertEquals(BigDecimal("18.5"), usage?.percentUsed)
        } finally {
            Files.deleteIfExists(log)
        }
    }

    @Test
    fun `calculates today's usage from dated cumulative quota entries`() {
        val log = Files.createTempFile("copilot-credit-usage", ".log")
        try {
            val today = LocalDate.now()
            val logLines = buildList {
                if (today.dayOfMonth > 1) {
                    add(quotaLine(today.minusDays(1), "20"))
                }
                add(quotaLine(today, "27"))
            }
            Files.writeString(log, logLines.joinToString("\n"))

            val usage = newReader(log).readLatest()

            assertEquals(
                if (today.dayOfMonth == 1) BigDecimal.ZERO else BigDecimal("7"),
                usage?.todayUsed
            )
        } finally {
            Files.deleteIfExists(log)
        }
    }

    @Test
    fun `reads quota entries from rotated IDE logs`() {
        val directory = Files.createTempDirectory("copilot-rotated-credit-usage")
        val currentLog = directory.resolve("idea.log")
        val newestRotatedLog = directory.resolve("idea.1.log")
        val olderRotatedLog = directory.resolve("idea.2.log")
        try {
            val today = LocalDate.now()
            val checkpointStore = InMemoryDailyUsageCheckpointStore()
            Files.writeString(
                currentLog,
                "Copilot credit logs contain no premium_interactions entries"
            )
            Files.writeString(newestRotatedLog, quotaLine(today, "27"))
            Files.writeString(olderRotatedLog, quotaLine(today.minusDays(1), "20"))

            val reader = CopilotCreditUsageLogReader(currentLog, checkpointStore)
            val usage = reader.readLatest()

            assertEquals(BigDecimal("27"), usage?.usedCredits)
            assertEquals(
                if (today.dayOfMonth == 1) BigDecimal.ZERO else BigDecimal("7"),
                usage?.todayUsed
            )

            Files.deleteIfExists(olderRotatedLog)
            assertEquals(
                if (today.dayOfMonth == 1) BigDecimal.ZERO else BigDecimal("7"),
                reader.readLatest()?.todayUsed
            )
            assertEquals(
                if (today.dayOfMonth == 1) BigDecimal.ZERO else BigDecimal("7"),
                CopilotCreditUsageLogReader(currentLog, checkpointStore).readLatest()?.todayUsed
            )
        } finally {
            Files.deleteIfExists(olderRotatedLog)
            Files.deleteIfExists(newestRotatedLog)
            Files.deleteIfExists(currentLog)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `uses first same-day quota entry when previous-day baseline is unavailable`() {
        val directory = Files.createTempDirectory("copilot-today-baseline")
        val currentLog = directory.resolve("idea.log")
        val rotatedLog = directory.resolve("idea.1.log")
        try {
            val today = LocalDate.now()
            val checkpointStore = InMemoryDailyUsageCheckpointStore()
            Files.writeString(
                currentLog,
                "Copilot credit logs contain no premium_interactions entries"
            )
            Files.writeString(
                rotatedLog,
                listOf(
                    quotaLine(today, "20", "09:00:00,000"),
                    quotaLine(today, "27", "10:00:00,000")
                ).joinToString("\n")
            )

            val reader = CopilotCreditUsageLogReader(currentLog, checkpointStore)
            assertEquals(BigDecimal("7"), reader.readLatest()?.todayUsed)
            assertEquals(BigDecimal("20"), checkpointStore.load()?.baselineUsed)

            Files.writeString(rotatedLog, quotaLine(today, "27", "10:00:00,000"))
            assertEquals(BigDecimal("7"), reader.readLatest()?.todayUsed)
            assertEquals(
                BigDecimal("7"),
                CopilotCreditUsageLogReader(currentLog, checkpointStore).readLatest()?.todayUsed
            )
        } finally {
            Files.deleteIfExists(rotatedLog)
            Files.deleteIfExists(currentLog)
            Files.deleteIfExists(directory)
        }
    }

    private fun quotaLine(date: LocalDate, used: String, time: String = "09:08:30,954"): String =
        "$date $time [8610] FINER - Copilot - [stdout] " +
            """{"jsonrpc":"2.0","method":"copilot/quotaChange","params":{"premium_interactions":{"quota":2500,"used":$used,"percentRemaining":99.2}}}"""

    private fun newReader(log: java.nio.file.Path): CopilotCreditUsageLogReader =
        CopilotCreditUsageLogReader(log, InMemoryDailyUsageCheckpointStore())

    private class InMemoryDailyUsageCheckpointStore : DailyUsageCheckpointStore {
        private var checkpoint: DailyUsageCheckpoint? = null

        override fun load(): DailyUsageCheckpoint? = checkpoint

        override fun save(checkpoint: DailyUsageCheckpoint) {
            this.checkpoint = checkpoint
        }
    }
}
