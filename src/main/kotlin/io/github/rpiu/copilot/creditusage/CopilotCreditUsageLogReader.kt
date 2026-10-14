package io.github.rpiu.copilot.creditusage

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val LOG = CopilotUsageMonitorLog
private val LOG_TIMESTAMP_PATTERN = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})")
private val LOG_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")

internal data class DailyUsageCheckpoint(
    val date: LocalDate,
    val baselineUsed: BigDecimal?,
    val latestUsed: BigDecimal?
)

internal interface DailyUsageCheckpointStore {
    fun load(): DailyUsageCheckpoint?
    fun save(checkpoint: DailyUsageCheckpoint)
}

internal class CopilotCreditUsageLogReader(
    internal val logFile: Path = defaultLogFile(),
    private val dailyUsageCheckpointStore: DailyUsageCheckpointStore = PropertiesDailyUsageCheckpointStore()
) {
    private var cachedFileState: FileState? = null
    private var cachedUsage: CopilotCreditUsage? = null
    private var cachedDate: LocalDate? = null
    private val cachedSamplesByFile = mutableMapOf<Path, CachedFileSamples>()

    @Synchronized
    fun readLatest(): CopilotCreditUsage? {
        LOG.debug("Trying to read Copilot credit usage from $logFile")
        val today = LocalDate.now()
        val logFiles = try {
            findLogFiles()
        } catch (e: IOException) {
            LOG.warn("Could not locate Copilot credit log files beside $logFile", e)
            return cachedUsage?.copy(todayUsed = null)
        }
        if (logFiles.isEmpty()) {
            LOG.warn("Copilot credit log does not exist: $logFile")
            cachedFileState = null
            cachedDate = today
            cachedSamplesByFile.clear()
            return cachedUsage?.copy(todayUsed = null)
        }

        val fileState = try {
            FileState(logFiles.mapNotNull { path ->
                try {
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                    FileStamp(path, attributes.size(), attributes.lastModifiedTime())
                } catch (_: NoSuchFileException) {
                    null
                }
            })
        } catch (_: NoSuchFileException) {
            FileState(emptyList())
        } catch (e: IOException) {
            LOG.warn("Could not inspect the Copilot credit log at $logFile", e)
            return cachedUsage?.copy(todayUsed = null)
        }
        if (fileState.files.isEmpty()) {
            cachedFileState = null
            cachedDate = today
            return cachedUsage?.copy(todayUsed = null)
        }

        if (fileState == cachedFileState && today == cachedDate) {
            LOG.debug("Copilot credit log is unchanged; using cached usage: $cachedUsage")
            return cachedUsage
        }

        val parsedLogs = try {
            val parsedSamples = mutableListOf<UsageSample>()
            var candidateCount = 0
            fileState.files.forEach { stamp ->
                var cachedFile = cachedSamplesByFile[stamp.path]
                if (cachedFile?.fileStamp != stamp) {
                    val parsedFile = readSamples(stamp.path)
                    cachedFile = CachedFileSamples(stamp, parsedFile.samples, parsedFile.candidateCount)
                    cachedSamplesByFile[stamp.path] = cachedFile
                }
                parsedSamples.addAll(cachedFile.samples)
                candidateCount += cachedFile.candidateCount
            }
            cachedSamplesByFile.keys.retainAll(fileState.files.map { it.path }.toSet())
            ParsedSamples(parsedSamples, candidateCount)
        } catch (_: NoSuchFileException) {
            cachedFileState = null
            return cachedUsage?.copy(todayUsed = null)
        } catch (e: IOException) {
            LOG.warn("Could not read the Copilot credit logs beside $logFile", e)
            return cachedUsage?.copy(todayUsed = null)
        }

        cachedFileState = fileState
        cachedDate = today
        if (parsedLogs.candidateCount == 0) {
            LOG.warn("Copilot credit logs contain no premium_interactions entries")
        } else if (parsedLogs.samples.isEmpty()) {
            LOG.warn("Found ${parsedLogs.candidateCount} Copilot credit candidate entries, but none could be parsed")
        } else {
            LOG.debug("Read Copilot credit usage successfully: ${parsedLogs.samples.last().usage}")
        }
        val latestUsage = parsedLogs.samples.lastOrNull()?.usage ?: cachedUsage
        cachedUsage = latestUsage?.copy(todayUsed = calculateTodayUsage(parsedLogs.samples, today))
        return cachedUsage
    }

    private fun findLogFiles(): List<Path> {
        val fileName = logFile.fileName.toString()
        if (!fileName.endsWith(".log")) {
            return listOf(logFile).filter { Files.isRegularFile(it) }
        }

        val directory = logFile.parent ?: return listOf(logFile).filter { Files.isRegularFile(it) }
        val prefix = fileName.removeSuffix(".log")
        val rotationPattern = Regex("^${Regex.escape(prefix)}\\.(\\d+)\\.log$")
        val rotatedFiles = mutableListOf<Pair<Int, Path>>()
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { path ->
                val match = rotationPattern.matchEntire(path.fileName.toString()) ?: return@forEach
                val rotation = match.groupValues[1].toIntOrNull() ?: return@forEach
                if (Files.isRegularFile(path)) {
                    rotatedFiles.add(rotation to path)
                }
            }
        }

        return rotatedFiles
            .sortedByDescending { it.first }
            .map { it.second }
            .plus(listOfNotNull(logFile.takeIf { Files.isRegularFile(it) }))
    }

    private fun readSamples(path: Path): ParsedSamples =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines ->
            val samples = mutableListOf<UsageSample>()
            var candidateCount = 0
            lines.forEach { line ->
                if (line.contains("premium_interactions") && line.indexOf('{') >= 0) {
                    candidateCount++
                    parseLine(line)?.let { usage ->
                        samples.add(UsageSample(parseTimestamp(line), usage))
                    }
                }
            }
            ParsedSamples(samples, candidateCount)
        }

    private fun calculateTodayUsage(samples: List<UsageSample>, today: LocalDate): BigDecimal? {
        val todaySamples = samples.filter { it.timestamp?.toLocalDate() == today }
        val todaySample = todaySamples.maxByOrNull { it.timestamp!! }
        val firstTodaySample = todaySamples.minByOrNull { it.timestamp!! }
        val storedCheckpoint = dailyUsageCheckpointStore.load()
        val latestUsed = todaySample?.usage?.usedCredits
            ?: storedCheckpoint?.takeIf { it.date == today }?.latestUsed

        val previousSample = samples
            .filter {
                val sampleDate = it.timestamp?.toLocalDate()
                sampleDate != null &&
                    YearMonth.from(sampleDate) == YearMonth.from(today) &&
                    sampleDate.isBefore(today)
            }
            .maxByOrNull { it.timestamp!! }

        val baseline = previousSample?.usage?.usedCredits
            ?: storedCheckpoint?.takeIf { it.date == today }?.baselineUsed
            ?: storedCheckpoint
                ?.takeIf { it.date == today.minusDays(1) }
                ?.latestUsed
            ?: firstTodaySample?.usage?.usedCredits
            ?: if (today.dayOfMonth == 1) BigDecimal.ZERO else null

        if (baseline != null || latestUsed != null) {
            val newCheckpoint = DailyUsageCheckpoint(today, baseline, latestUsed)
            if (newCheckpoint != storedCheckpoint) {
                dailyUsageCheckpointStore.save(newCheckpoint)
            }
        }
        if (baseline == null || latestUsed == null) return null
        return latestUsed.subtract(baseline).takeIf { it.signum() >= 0 }
    }

    private fun parseTimestamp(line: String): LocalDateTime? {
        val timestamp = LOG_TIMESTAMP_PATTERN.find(line)?.groupValues?.getOrNull(1) ?: return null
        return try {
            LocalDateTime.parse(timestamp, LOG_TIMESTAMP_FORMAT)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseLine(line: String): CopilotCreditUsage? {
        val jsonStart = line.indexOf('{')
        if (jsonStart < 0) {
            LOG.warn("Copilot credit candidate has no JSON object")
            return null
        }

        val root = try {
            JsonParser.parseString(line.substring(jsonStart))
        } catch (e: JsonParseException) {
            LOG.warn("Could not parse the JSON in a Copilot credit log entry.", e)
            return null
        }
        if (!root.isJsonObject) {
            LOG.warn("Copilot credit log entry JSON is not an object")
            return null
        }

        val params = root.asJsonObject.objectValue("params")
        if (params == null) {
            LOG.warn("Copilot credit log entry has no object-valued params field")
            return null
        }
        val payload = params.objectValue("value")
            ?.get("chunk")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.let { chunk ->
                try {
                    JsonParser.parseString(chunk)
                } catch (e: JsonParseException) {
                    LOG.warn("Could not parse a Copilot credit payload.", e)
                    return null
                }
            }
            ?: params
        val premiumInteractions = findPremiumInteractions(payload)
        if (premiumInteractions == null) {
            LOG.warn("Copilot credit payload has no premium_interactions object")
            return null
        }

        val quotaRemaining = premiumInteractions.numberValue("quota_remaining")
            ?: premiumInteractions.numberValue("quota")?.let { quota ->
                premiumInteractions.numberValue("used")?.let { used -> quota - used }
            }
        val entitlement = premiumInteractions.numberValue("entitlement")
            ?: premiumInteractions.numberValue("quota")
        val percentRemaining = premiumInteractions.numberValue("percent_remaining")
            ?: premiumInteractions.numberValue("percentRemaining")
        if (quotaRemaining == null || entitlement == null || percentRemaining == null) {
            LOG.warn(
                "Copilot credit data is missing valid quota, used, entitlement, or percent remaining values"
            )
            return null
        }

        return CopilotCreditUsage(quotaRemaining, entitlement, percentRemaining)
    }

    private fun JsonObject.objectValue(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.numberValue(name: String): BigDecimal? {
        val primitive = get(name)
            ?.takeIf { it.isJsonPrimitive }
            ?.asJsonPrimitive
            ?: return null
        if (!primitive.isNumber && !primitive.isString) return null
        return primitive.asString.toBigDecimalOrNull()
    }

    private fun findPremiumInteractions(element: JsonElement): JsonObject? {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            obj.objectValue("premium_interactions")?.let { return it }
            obj.entrySet().forEach { (_, value) ->
                findPremiumInteractions(value)?.let { return it }
            }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { value ->
                findPremiumInteractions(value)?.let { return it }
            }
        }
        return null
    }

    private data class FileStamp(val path: Path, val size: Long, val lastModified: FileTime)
    private data class FileState(val files: List<FileStamp>)
    private data class CachedFileSamples(
        val fileStamp: FileStamp,
        val samples: List<UsageSample>,
        val candidateCount: Int
    )
    private data class ParsedSamples(val samples: List<UsageSample>, val candidateCount: Int)
    private data class UsageSample(val timestamp: LocalDateTime?, val usage: CopilotCreditUsage)
}

private class PropertiesDailyUsageCheckpointStore : DailyUsageCheckpointStore {
    override fun load(): DailyUsageCheckpoint? {
        val properties = PropertiesComponent.getInstance()
        val dateText = properties.getValue(DATE_KEY) ?: return null
        val date = try {
            LocalDate.parse(dateText)
        } catch (e: DateTimeParseException) {
            LOG.warn("Could not parse the saved Copilot usage checkpoint date.", e)
            return null
        }
        return DailyUsageCheckpoint(
            date = date,
            baselineUsed = properties.getValue(BASELINE_KEY)?.toBigDecimalOrNull(),
            latestUsed = properties.getValue(LATEST_KEY)?.toBigDecimalOrNull()
        )
    }

    override fun save(checkpoint: DailyUsageCheckpoint) {
        val properties = PropertiesComponent.getInstance()
        properties.setValue(DATE_KEY, checkpoint.date.toString())
        if (checkpoint.baselineUsed == null) {
            properties.unsetValue(BASELINE_KEY)
        } else {
            properties.setValue(BASELINE_KEY, checkpoint.baselineUsed.toPlainString())
        }
        if (checkpoint.latestUsed == null) {
            properties.unsetValue(LATEST_KEY)
        } else {
            properties.setValue(LATEST_KEY, checkpoint.latestUsed.toPlainString())
        }
    }

    private companion object {
        const val DATE_KEY = "copilot.creditUsage.dailyCheckpointDate"
        const val BASELINE_KEY = "copilot.creditUsage.dailyBaselineUsed"
        const val LATEST_KEY = "copilot.creditUsage.dailyLatestUsed"
    }
}

private fun defaultLogFile(): Path {
    return Path.of(PathManager.getLogPath(), "idea.log")
}
