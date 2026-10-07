package io.github.rpiu.copilot.creditusage

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

private val LOG = CopilotUsageMonitorLog

internal class CopilotCreditUsageLogReader(
    internal val logFile: Path = defaultLogFile()
) {
    private var cachedFileState: FileState? = null
    private var cachedUsage: CopilotCreditUsage? = null

    @Synchronized
    fun readLatest(): CopilotCreditUsage? {
        LOG.debug("Trying to read Copilot credit usage from $logFile")
        val fileState = try {
            val attributes = Files.readAttributes(logFile, BasicFileAttributes::class.java)
            LOG.debug("Copilot credit log found: $logFile (${attributes.size()} bytes)")
            FileState(attributes.size(), attributes.lastModifiedTime())
        } catch (_: NoSuchFileException) {
            LOG.warn("Copilot credit log does not exist: $logFile")
            cachedFileState = null
            cachedUsage = null
            return null
        } catch (e: IOException) {
            LOG.warn("Could not inspect the Copilot credit log at $logFile", e)
            return null
        }

        if (fileState == cachedFileState) {
            LOG.debug("Copilot credit log is unchanged; using cached usage: $cachedUsage")
            return cachedUsage
        }

        val latestUsage = try {
            Files.newBufferedReader(logFile, StandardCharsets.UTF_8).useLines { lines ->
                var latest: CopilotCreditUsage? = null
                var candidateCount = 0
                lines.forEach { line ->
                    if (line.contains("premium_interactions")) {
                        candidateCount++
                        parseLine(line)?.let { latest = it }
                    }
                }
                if (candidateCount == 0) {
                    LOG.warn("Copilot credit log contains no premium_interactions entries: $logFile")
                } else if (latest == null) {
                    LOG.warn("Found $candidateCount Copilot credit candidate entries, but none could be parsed")
                } else {
                    LOG.debug("Read Copilot credit usage successfully: $latest")
                }
                latest
            }
        } catch (_: NoSuchFileException) {
            cachedFileState = null
            cachedUsage = null
            return null
        } catch (e: IOException) {
            LOG.warn("Could not read the Copilot credit log at $logFile", e)
            return null
        }

        cachedFileState = fileState
        cachedUsage = latestUsage
        return latestUsage
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

    private data class FileState(val size: Long, val lastModified: FileTime)
}

private fun defaultLogFile(): Path {
    val pathSelector = PathManager.getPathsSelector()
        ?: ApplicationNamesInfo.getInstance().productName + ApplicationInfo.getInstance().versionName
    return Path.of(PathManager.getDefaultLogPathFor(pathSelector), "idea.log")
}
