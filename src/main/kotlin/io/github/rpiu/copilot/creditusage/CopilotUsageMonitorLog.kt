package io.github.rpiu.copilot.creditusage

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger as IdeaLogger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.ErrorManager
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.SimpleFormatter

internal object CopilotUsageMonitorLog {
    private const val LOGGER_NAME = "io.github.rpiu.copilot.creditusage"
    private const val MAX_LOG_SIZE_BYTES = 1_048_576
    private const val LOG_FILE_COUNT = 3

    private val ideaLogger = IdeaLogger.getInstance(CopilotUsageMonitorLog::class.java)
    private val logDirectory = Path.of(PathManager.getLogPath())

    val currentLogFile: Path = logDirectory.resolve("copilot-usage-monitor-0.log")

    private val fileHandler = createFileHandler()

    fun debug(message: String) {
        ideaLogger.debug(message)
        write(Level.FINE, message)
    }

    fun warn(message: String) {
        ideaLogger.warn(message)
        write(Level.WARNING, message)
    }

    fun warn(message: String, error: Throwable) {
        ideaLogger.warn(message, error)
        write(Level.WARNING, message, error)
    }

    private fun createFileHandler(): FileHandler? =
        try {
            Files.createDirectories(logDirectory)
            FileHandler(
                logDirectory.resolve("copilot-usage-monitor-%g.log").toString(),
                MAX_LOG_SIZE_BYTES,
                LOG_FILE_COUNT,
                true
            ).apply {
                level = Level.ALL
                formatter = SimpleFormatter()
                errorManager = object : ErrorManager() {
                    override fun error(message: String?, error: Exception?, code: Int) {
                        val errorMessage = message ?: "Could not write the plugin log file at $currentLogFile"
                        if (error == null) {
                            ideaLogger.error(errorMessage)
                        } else {
                            ideaLogger.error(errorMessage, error)
                        }
                    }
                }
            }
        } catch (error: IOException) {
            ideaLogger.error("Could not create the plugin log file at $currentLogFile", error)
            null
        } catch (error: SecurityException) {
            ideaLogger.error("Could not create the plugin log file at $currentLogFile", error)
            null
        }

    private fun write(level: Level, message: String, error: Throwable? = null) {
        val record = LogRecord(level, message).apply {
            loggerName = LOGGER_NAME
            thrown = error
        }
        fileHandler?.publish(record)
    }
}
