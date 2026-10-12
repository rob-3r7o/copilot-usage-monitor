package io.github.rpiu.copilot.creditusage

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class CopilotCreditUsageWidgetTest {
    private val usage = CopilotCreditUsage(
        quotaRemaining = BigDecimal("2037.5"),
        entitlement = BigDecimal("2500"),
        percentRemaining = BigDecimal("81.5")
    )

    @Test
    fun `shows both usage visualizations by default`() {
        assertEquals(
            "Usage: 462.5/2500 credits (18.5% credits used)",
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
            "(18.5% credits used)",
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
}
