package io.github.rpiu.copilot.creditusage

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Alarm
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent
import java.math.BigDecimal
import java.math.RoundingMode
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

private const val WIDGET_ID = "CopilotCreditUsage"
private const val REFRESH_INTERVAL_KEY = "copilot.creditUsage.refreshIntervalSeconds"
private const val DEFAULT_REFRESH_INTERVAL_SECONDS = 10
private const val MIN_REFRESH_INTERVAL_SECONDS = 1
private const val MAX_REFRESH_INTERVAL_SECONDS = 3_600

class CopilotCreditUsageWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "Copilot Credit Usage"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = CopilotCreditUsageWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class CopilotCreditUsageWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private val logReader = CopilotCreditUsageLogReader()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    @Volatile
    private var refreshIntervalMs = PropertiesComponent.getInstance()
        .getInt(REFRESH_INTERVAL_KEY, DEFAULT_REFRESH_INTERVAL_SECONDS)
        .coerceIn(MIN_REFRESH_INTERVAL_SECONDS, MAX_REFRESH_INTERVAL_SECONDS) * 1_000

    @Volatile
    private var usage: CopilotCreditUsage? = null

    @Volatile
    private var disposed = false

    @Volatile
    private var pollGeneration = 0L

    private var statusBar: StatusBar? = null

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = usage?.let {
        val usedCredits = it.entitlement - it.quotaRemaining
        "Copilot: ${usedCredits.display()} / ${it.entitlement.display()} credits (${it.percentRemaining.display()}% remaining)"
    } ?: "Copilot: unavailable"

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String =
        "Reading credits from ${logReader.logFile} every ${refreshIntervalMs / 1_000} seconds. Click to change."

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer(::showRefreshMenu)

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        scheduleRefresh(0, pollGeneration)
    }

    override fun dispose() {
        disposed = true
        pollGeneration++
        statusBar = null
        alarm.dispose()
    }

    private fun scheduleRefresh(delayMs: Int, generation: Long) {
        if (disposed || generation != pollGeneration) return
        alarm.addRequest({
            if (disposed || generation != pollGeneration) return@addRequest
            try {
                val latestUsage = logReader.readLatest()
                if (latestUsage != usage) {
                    usage = latestUsage
                    ApplicationManager.getApplication().invokeLater(
                        {
                            if (!disposed) {
                                statusBar?.updateWidget(WIDGET_ID)
                            }
                        },
                        ModalityState.any()
                    )
                }
            } finally {
                scheduleRefresh(refreshIntervalMs, generation)
            }
        }, delayMs)
    }

    private fun showRefreshMenu(event: MouseEvent) {
        val menu = JPopupMenu()
        val currentRate = refreshIntervalMs / 1_000
        val currentRateItem = JMenuItem("Current interval: $currentRate seconds")
        currentRateItem.isEnabled = false
        menu.add(currentRateItem)
        menu.addSeparator()
        menu.add(JMenuItem("Set custom interval...").apply {
            addActionListener { promptForRefreshInterval() }
        })
        menu.show(event.component, event.x, event.y)
    }

    private fun promptForRefreshInterval() {
        val currentRate = refreshIntervalMs / 1_000
        val input = Messages.showInputDialog(
            project,
            "Enter a refresh interval in seconds ($MIN_REFRESH_INTERVAL_SECONDS-$MAX_REFRESH_INTERVAL_SECONDS):",
            "Copilot Usage Refresh Interval",
            null,
            currentRate.toString(),
            null
        ) ?: return

        val seconds = input.toIntOrNull()
        if (seconds == null || seconds !in MIN_REFRESH_INTERVAL_SECONDS..MAX_REFRESH_INTERVAL_SECONDS) {
            Messages.showErrorDialog(
                project,
                "Enter a whole number between $MIN_REFRESH_INTERVAL_SECONDS and $MAX_REFRESH_INTERVAL_SECONDS seconds.",
                "Invalid Refresh Interval"
            )
            return
        }

        PropertiesComponent.getInstance().setValue(REFRESH_INTERVAL_KEY, seconds.toString())
        refreshIntervalMs = seconds * 1_000
        pollGeneration++
        alarm.cancelAllRequests()
        scheduleRefresh(0, pollGeneration)
        statusBar?.updateWidget(WIDGET_ID)
    }
}

internal data class CopilotCreditUsage(
    val quotaRemaining: BigDecimal,
    val entitlement: BigDecimal,
    val percentRemaining: BigDecimal
)

private fun BigDecimal.display(): String =
    setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
