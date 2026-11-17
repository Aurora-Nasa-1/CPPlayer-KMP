package cp.player.core.provider.conformance

import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ModuleManager
import cp.player.core.provider.ProviderCookieStorage
import cp.player.core.provider.ProviderManager
import cp.player.core.provider.typed.ProviderHost
import cp.player.core.util.PlatformContext
import cp.player.core.util.SettingsStorage
import cp.player.sdk.Capability
import cp.player.sdk.FailureCategory
import cp.player.sdk.ProviderException
import cp.player.sdk.ResolveRequest
import cp.player.sdk.SearchRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Host-side smoke test for an installed provider ZIP.
 *
 * This deliberately goes through [ModuleManager], [ProviderManager] and the
 * real [ProviderHost] instead of calling a provider factory directly, so the
 * checks cover manifest validation, session creation, capability gating and
 * response validation.
 */
private enum class Status { PASS, FAIL, SKIP }

private data class CheckResult(val name: String, val status: Status, val detail: String)

private class SkipCheck(message: String) : Exception(message)

private data class Options(
    val packagePath: File,
    val account: String = "default",
    val credential: String? = null,
    val settings: Map<String, String> = emptyMap(),
    val keyword: String = "all",
    val limit: Int = 5,
    val quality: String = "standard",
    val json: Boolean = false,
    val xml: String? = null,
)

private class MemorySettings : SettingsStorage {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String, default: String?): String? = values[key] ?: default
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override fun remove(key: String) {
        values.remove(key)
    }
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun clear() {
        values.clear()
    }
}

private fun usage(): Nothing {
    println(
        """
        Usage: gradlew :core:providerConformance -PproviderPackage=<package.zip> [-PproviderArgs="..."]

        Options:
          --account <id>          Account slot (default: default)
          --credential <value>    Credential snapshot for the session
          --setting key=value     Repeatable provider setting
          --keyword <text>        Search keyword (default: all)
          --limit <n>             Search limit 1..500 (default: 5)
          --quality <key>         Resolve quality key (default: standard)
          --json                  Print a JSON report
          --xml <path>            Write a JUnit XML report
        """.trimIndent(),
    )
    exitProcess(2)
}

private fun parseOptions(args: Array<String>): Options {
    var packagePath: String? = null
    var account = "default"
    var credential: String? = null
    val settings = linkedMapOf<String, String>()
    var keyword = "all"
    var limit = 5
    var quality = "standard"
    var json = false
    var xml: String? = null
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        fun value(): String {
            if (++index >= args.size) {
                System.err.println("$arg requires a value")
                exitProcess(2)
            }
            return args[index]
        }
        when (arg) {
            "--account" -> account = value()
            "--credential" -> credential = value().takeIf { it.isNotEmpty() }
            "--setting" -> {
                val pair = value()
                val split = pair.indexOf('=')
                if (split <= 0) {
                    System.err.println("--setting expects key=value")
                    exitProcess(2)
                }
                settings[pair.substring(0, split)] = pair.substring(split + 1)
            }
            "--keyword" -> keyword = value()
            "--limit" -> limit = value().toIntOrNull() ?: run {
                System.err.println("--limit must be an integer")
                exitProcess(2)
            }
            "--quality" -> quality = value()
            "--json" -> json = true
            "--xml" -> xml = value()
            "--help", "-h" -> usage()
            else -> {
                if (arg.startsWith("-")) {
                    System.err.println("Unknown option: $arg")
                    exitProcess(2)
                }
                if (packagePath != null) {
                    System.err.println("Only one provider package may be checked at a time")
                    exitProcess(2)
                }
                packagePath = arg
            }
        }
        index++
    }
    if (packagePath == null) usage()
    if (limit !in 1..500) {
        System.err.println("--limit must be in 1..500")
        exitProcess(2)
    }
    if (keyword.isBlank() || keyword.length > 4096) {
        System.err.println("--keyword must be non-blank and at most 4096 characters")
        exitProcess(2)
    }
    if (quality.isBlank() || quality.length > 128) {
        System.err.println("--quality must be non-blank and at most 128 characters")
        exitProcess(2)
    }
    if (account.isBlank() || account.length > 256) {
        System.err.println("--account must be non-blank and at most 256 characters")
        exitProcess(2)
    }
    return Options(
        packagePath = File(packagePath).absoluteFile,
        account = account,
        credential = credential,
        settings = settings,
        keyword = keyword,
        limit = limit,
        quality = quality,
        json = json,
        xml = xml,
    )
}

private fun jsonEscape(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}

private fun xmlEscape(value: String): String = buildString {
    for (character in value) {
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            // XML 1.0 forbids most C0 controls; provider data must not break the report.
            else -> if (character.code < 0x20 && character !in "\t\n\r") Unit else append(character)
        }
    }
}

private fun reportJson(provider: BackendProvider, results: List<CheckResult>): String {
    val passed = results.count { it.status == Status.PASS }
    val failed = results.count { it.status == Status.FAIL }
    val skipped = results.count { it.status == Status.SKIP }
    return buildString {
        append("{\n")
        append("  \"provider\": {")
        append("\"id\": ").append(jsonEscape(provider.id)).append(", ")
        append("\"name\": ").append(jsonEscape(provider.name)).append(", ")
        append("\"version\": ").append(jsonEscape(provider.version)).append(", ")
        append("\"type\": ").append(jsonEscape(provider.type.name)).append(", ")
        append("\"contract\": ").append(jsonEscape(provider.contract.orEmpty()))
        append("},\n")
        append("  \"checks\": [\n")
        results.forEachIndexed { index, result ->
            append("    {\"name\": ").append(jsonEscape(result.name))
            append(", \"status\": ").append(jsonEscape(result.status.name.lowercase()))
            append(", \"detail\": ").append(jsonEscape(result.detail)).append("}")
            if (index != results.lastIndex) append(',')
            append('\n')
        }
        append("  ],\n")
        append("  \"summary\": {\"passed\": $passed, \"failed\": $failed, \"skipped\": $skipped}\n")
        append("}\n")
    }
}

private fun reportXml(provider: BackendProvider, results: List<CheckResult>): String {
    val failed = results.count { it.status == Status.FAIL }
    val skipped = results.count { it.status == Status.SKIP }
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<testsuite name=\"provider-conformance\" tests=\"${results.size}\" ")
        append("failures=\"$failed\" skipped=\"$skipped\" errors=\"0\">\n")
        append("  <properties><property name=\"provider.id\" value=\"")
        append(xmlEscape(provider.id)).append("\"/></properties>\n")
        for (result in results) {
            append("  <testcase classname=\"cp.player.provider.conformance\" name=\"")
            append(xmlEscape(result.name)).append("\">")
            when (result.status) {
                Status.PASS -> append("<system-out>").append(xmlEscape(result.detail)).append("</system-out>")
                Status.SKIP -> append("<skipped message=\"").append(xmlEscape(result.detail)).append("\"/>")
                Status.FAIL -> append("<failure message=\"").append(xmlEscape(result.detail))
                    .append("\">").append(xmlEscape(result.detail)).append("</failure>")
            }
            append("</testcase>\n")
        }
        append("</testsuite>\n")
    }
}

private fun printHuman(provider: BackendProvider, results: List<CheckResult>) {
    println("Provider conformance: ${provider.id} ${provider.version} (${provider.type.name})")
    for (result in results) {
        val mark = when (result.status) {
            Status.PASS -> "PASS"
            Status.FAIL -> "FAIL"
            Status.SKIP -> "SKIP"
        }
        println("  $mark ${result.name}: ${result.detail}")
    }
    val passed = results.count { it.status == Status.PASS }
    val failed = results.count { it.status == Status.FAIL }
    val skipped = results.count { it.status == Status.SKIP }
    println("Result: $passed passed, $failed failed, $skipped skipped")
}

private suspend fun runChecks(host: ProviderHost, provider: BackendProvider, options: Options): List<CheckResult> {
    val results = mutableListOf<CheckResult>()
    suspend fun check(name: String, block: suspend () -> String) {
        results += try {
            CheckResult(name, Status.PASS, block())
        } catch (error: SkipCheck) {
            CheckResult(name, Status.SKIP, error.message.orEmpty())
        } catch (error: Throwable) {
            CheckResult(name, Status.FAIL, error.message ?: error::class.simpleName ?: "failed")
        }
    }

    var supported: List<String> = emptyList()
    var firstTrackId: String? = null

    check("manifest") {
        require(provider.id.isNotBlank()) { "Provider ID is blank" }
        require(provider.version.isNotBlank()) { "Provider version is blank" }
        require(provider.contract == "kmp-sdk-v1") { "Unexpected contract: ${provider.contract}" }
        "id=${provider.id}, contract=${provider.contract}"
    }

    check("capabilities") {
        val capabilities = host.capabilities().supported
        require(capabilities.size <= 256) { "Too many capabilities: ${capabilities.size}" }
        capabilities.forEach { value ->
            require(value.isNotBlank() && value.length <= 128) { "Invalid capability: $value" }
        }
        supported = capabilities
        "${capabilities.size} declared${if (capabilities.isEmpty()) "" else ": ${capabilities.joinToString(", ")}"}"
    }

    check("search") {
        if (Capability.SEARCH !in supported) throw SkipCheck("tracks.search is not declared")
        val page = host.search(SearchRequest(options.keyword, options.limit, 0))
        require(page.items.size <= options.limit) { "Returned ${page.items.size} items for limit ${options.limit}" }
        firstTrackId = page.items.firstOrNull()?.id
        if (firstTrackId == null) {
            "0 items for keyword ${options.keyword}"
        } else {
            "${page.items.size} items; first=${firstTrackId}"
        }
    }

    check("get") {
        if (Capability.GET !in supported) throw SkipCheck("tracks.get is not declared")
        val id = firstTrackId ?: throw SkipCheck("search returned no track")
        val track = host.get(id)
        require(track.id == id) { "Returned ${track.id} for requested $id" }
        "id=$id, title=${track.title}"
    }

    check("batch order") {
        if (Capability.GET !in supported && Capability.BATCH !in supported) {
            throw SkipCheck("tracks.get and tracks.getMany are not declared")
        }
        val id = firstTrackId ?: throw SkipCheck("search returned no track")
        val batch = host.getMany(listOf(id, id))
        require(batch.items.size == 2) { "Expected 2 outcomes, got ${batch.items.size}" }
        require(batch.items.all { it.id == id }) { "Batch did not preserve input order" }
        "2 outcomes for duplicated id $id"
    }

    check("resolve") {
        if (Capability.RESOLVE !in supported) throw SkipCheck("tracks.resolve is not declared")
        val id = firstTrackId ?: throw SkipCheck("search returned no track")
        val media = host.resolve(ResolveRequest(id, options.quality))
        require(media.url.startsWith("http://") || media.url.startsWith("https://")) {
            "Resolved URL is not HTTP(S)"
        }
        "quality=${media.actualQuality}, url=${media.url.substringBefore('?')}"
    }

    check("unsupported path") {
        val probeId = firstTrackId ?: "conformance-probe"
        val candidates = buildList<Pair<String, suspend () -> Any?>> {
            if (Capability.LYRICS !in supported) {
                add("lyrics.get" to { host.getLyrics(probeId) })
            }
            if (Capability.DISCOVERY_BANNERS !in supported) {
                add("discovery.banners" to { host.getBanners() })
            }
        }
        val candidate = candidates.firstOrNull() ?: throw SkipCheck("all probe capabilities are declared")
        val error = runCatching { candidate.second() }.exceptionOrNull()
        require(error is ProviderException) { "Undeclared ${candidate.first} did not fail with ProviderException" }
        val failure = error.failure
        require(failure.category == FailureCategory.UNSUPPORTED) {
            "Undeclared ${candidate.first} returned ${failure.category}"
        }
        "${candidate.first} -> Unsupported"
    }

    return results
}

private fun mainImpl(options: Options): Int = runBlocking {
    val tempRoot = Files.createTempDirectory("cpplayer-conformance-").toFile()
    // Keep provider settings out of the user's real ~/.cpplayer directory.
    System.setProperty("cp.player.dataHome", tempRoot.absolutePath)
    val modules = File(tempRoot, "modules").apply { mkdirs() }
    val settings = MemorySettings()
    val cookies = ProviderCookieStorage(settings)
    val providerManager = ProviderManager(settings, cookies)
    val moduleManager = ModuleManager(modules.absolutePath, PlatformContext())
    try {
        moduleManager.init(providerManager)
        val imported = moduleManager.importModuleDetails(options.packagePath.absolutePath)
            ?: error(
                "${moduleManager.lastLoadError ?: "Cannot import provider package"}: " +
                    options.packagePath.absolutePath,
            )
        val provider = imported.provider
        if (options.settings.isNotEmpty()) provider.updateCustomSettings(options.settings)
        options.credential?.let { cookies.saveCookie(provider.id, it, options.account) }
        check(providerManager.switchProvider(provider)) {
            providerManager.lastSwitchError ?: "Cannot activate provider"
        }
        providerManager.sdkSessions.selectAccount(options.account)
        val host = providerManager.sdkSessions.current()
            ?: error("Provider did not create an SDK session")
        val results = runChecks(host, provider, options)
        if (options.json) {
            print(reportJson(provider, results))
        } else {
            printHuman(provider, results)
        }
        options.xml?.let { path ->
            File(path).absoluteFile.parentFile?.mkdirs()
            File(path).writeText(reportXml(provider, results), Charsets.UTF_8)
            if (!options.json) println("JUnit XML: ${File(path).absolutePath}")
        }
        if (results.any { it.status == Status.FAIL }) 1 else 0
    } finally {
        runCatching { providerManager.sdkSessions.close() }
        runCatching { providerManager.close() }
        moduleManager.getAvailableProviders().forEach { runCatching { it.close() } }
        tempRoot.deleteRecursively()
    }
}

fun main(args: Array<String>) {
    val code = mainImpl(parseOptions(args))
    if (code != 0) exitProcess(code)
}
