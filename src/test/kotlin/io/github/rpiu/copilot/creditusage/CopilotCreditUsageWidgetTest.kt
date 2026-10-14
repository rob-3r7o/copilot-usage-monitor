package io.github.rpiu.copilot.creditusage

import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopilotCreditUsageWidgetTest {
    private val usage = CopilotCreditUsage(
        quotaRemaining = BigDecimal("2037.5"),
        entitlement = BigDecimal("2500"),
        percentRemaining = BigDecimal("81.5"),
        todayUsed = BigDecimal("12")
    )

    @Test
    fun `shows both usage visualizations by default`() {
        assertEquals(
            "Usage: 462.5/2500 credits | 18.5% used",
            formatUsageText(usage, showCreditUsage = true, showPercentUsed = true)
        )
    }

    @Test
    fun `shows only credit usage when percentage is disabled`() {
        assertEquals(
            "Usage: 462.5/2500 credits",
            formatUsageText(usage, showCreditUsage = true, showPercentUsed = false)
        )
    }

    @Test
    fun `shows only percentage when credit usage is disabled`() {
        assertEquals(
            "18.5% used",
            formatUsageText(usage, showCreditUsage = false, showPercentUsed = true)
        )
    }

    @Test
    fun `keeps widget available when both visualizations are disabled`() {
        assertEquals(
            "Usage hidden",
            formatUsageText(usage, showCreditUsage = false, showPercentUsed = false)
        )
    }

    @Test
    fun `shows today's usage and daily allowance when enabled`() {
        val usage = CopilotCreditUsage(
            quotaRemaining = BigDecimal("3000"),
            entitlement = BigDecimal("5000"),
            percentRemaining = BigDecimal("60"),
            todayUsed = BigDecimal("42")
        )

        assertEquals(
            "Today: 42/200",
            formatUsageText(
                usage,
                showCreditUsage = false,
                showPercentUsed = false,
                showTodayBudget = true,
                today = LocalDate.of(2026, 4, 10)
            )
        )
    }

    @Test
    fun `uses one day for allowance on the last day of the month`() {
        assertEquals(
            BigDecimal("3000.0"),
            dailyCreditAllowance(BigDecimal("3000"), LocalDate.of(2026, 4, 30))
        )
    }

    @Test
    fun `counts weekdays and includes today only when it is a weekday`() {
        assertEquals(
            BigDecimal("200.0"),
            dailyCreditAllowance(BigDecimal("3000"), LocalDate.of(2026, 4, 10))
        )
        assertEquals(
            BigDecimal("214.3"),
            dailyCreditAllowance(BigDecimal("3000"), LocalDate.of(2026, 4, 11))
        )
    }

    @Test
    fun `has no daily allowance when no working days remain this month`() {
        assertEquals(
            null,
            dailyCreditAllowance(BigDecimal("3000"), LocalDate.of(2026, 5, 31))
        )
    }

    @Test
    fun `shows unavailable today usage when its history is missing`() {
        val usageWithoutTodayHistory = usage.copy(todayUsed = null)

        assertEquals(
            "Today: unavailable/135.8",
            formatUsageText(
                usageWithoutTodayHistory,
                showCreditUsage = false,
                showPercentUsed = false,
                showTodayBudget = true,
                today = LocalDate.of(2026, 4, 10)
            )
        )
    }

    @Test
    fun `plugin log is unavailable when it does not exist`() {
        val directory = Files.createTempDirectory("copilot-plugin-log")
        try {
            assertFalse(isPluginLogAvailable(directory.resolve("missing.log")))
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `plugin log is available when it is a readable file`() {
        val log = Files.createTempFile("copilot-plugin-log", ".log")
        try {
            assertTrue(isPluginLogAvailable(log))
        } finally {
            Files.deleteIfExists(log)
        }
    }
}
