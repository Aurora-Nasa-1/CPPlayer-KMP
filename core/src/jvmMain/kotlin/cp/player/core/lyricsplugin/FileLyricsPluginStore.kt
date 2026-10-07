/*
 * Ported from Halcyon (https://github.com/Kifranei/Halcyon) — Apache License 2.0.
 * Upstream: app/src/main/java/com/ella/music/plugin/source/CustomPluginStore.kt
 * Changes: Android Context/assets replaced by a plain root directory + classpath resources;
 *          enabled-state and plugin config moved into a single state.json; i18n dropped.
 *          See THIRD_PARTY_LICENSES.md.
 */
package cp.player.core.lyricsplugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 歌词源插件的本地存储。
 *
 * 目录布局：
 * ```
 * <root>/
 *   ├── state.json                     # 启用状态 + 各插件配置
 *   └── <pluginId>/manifest.json + source.js + ...
 * ```
 *
 * 内置插件从 classpath 资源 `lyrics-plugins/<id>/` 读取，**不可删除**，
 * 且不允许被同名导入插件覆盖。
 */
class FileLyricsPluginStore(
    private val rootDir: File,
    private val bundledResourceRoot: String = "lyrics-plugins",
) {

    @Serializable
    private data class PluginState(
        val enabledIds: List<String> = emptyList(),
        val configs: Map<String, Map<String, String>> = emptyMap(),
    )

    private val stateFile: File get() = File(rootDir, "state.json")

    private fun readState(): PluginState =
        runCatching { pluginJson.decodeFromString<PluginState>(stateFile.readText()) }
            .getOrDefault(PluginState())

    private fun writeState(state: PluginState) {
        runCatching {
            rootDir.mkdirs()
            stateFile.writeText(pluginJson.encodeToString(state))
        }
    }

    // ============ 载入 ============

    fun loadSources(): List<LyricoPluginSource> {
        val bundled = loadBundledSources()
        val imported = rootDir.listFiles { file -> file.isDirectory }
            .orEmpty()
            .filterNot { it.name.startsWith(".") }
            .sortedBy { it.name }
            .mapNotNull { dir -> runCatching { loadPluginDir(dir, bundled = false) }.getOrNull() }
        // 内置源优先，同名导入插件不能顶掉它。
        return (bundled + imported).distinctBy { it.manifest.id }
    }

    private fun loadBundledSources(): List<LyricoPluginSource> {
        val ids = runCatching {
            val listing = javaClass.classLoader?.getResourceAsStream("$bundledResourceRoot/index.txt")
            listing?.bufferedReader()?.use { it.readLines() }?.filter { it.isNotBlank() } ?: emptyList()
        }.getOrDefault(emptyList())
        return ids.mapNotNull { id -> runCatching { loadBundledPlugin(id) }.getOrNull() }
    }

    private fun loadBundledPlugin(id: String): LyricoPluginSource {
        val base = "$bundledResourceRoot/$id"
        val manifestText = readResource("$base/manifest.json") ?: error("Missing bundled manifest: $id")
        val manifest = pluginJson.decodeFromString<PluginManifest>(manifestText)
        validateManifest(manifest, entryExists = { readResource("$base/${manifest.entry}") != null })
        val entry = readResource("$base/${manifest.entry}") ?: error("Missing bundled entry: $id")
        val includes = collectBundledIncludes(base, manifest)
        return LyricoPluginSource(
            manifest = manifest,
            assetDir = "resource://$base",
            script = composeScript(manifest, includes, entry),
            cacheRootDir = null,
            bundled = true,
        )
    }

    private fun collectBundledIncludes(base: String, manifest: PluginManifest): List<IncludedScript> =
        manifest.includeDirs.flatMap { dir ->
            // 内置插件的 include 目录在打包时已展开为显式清单（见 index.txt 旁的 includes.txt）。
            val listing = readResource("$base/$dir/index.txt")?.lineSequence()?.filter { it.isNotBlank() }?.toList()
                ?: emptyList()
            listing.mapNotNull { relative ->
                readResource("$base/$dir/$relative")?.let { IncludedScript("$dir/$relative", it) }
            }
        }.sortedBy { it.path }

    private fun loadPluginDir(dir: File, bundled: Boolean): LyricoPluginSource {
        val manifest = pluginJson.decodeFromString<PluginManifest>(File(dir, "manifest.json").readText())
        validateManifest(manifest, entryExists = { File(dir, manifest.entry).isFile })
        val entry = File(dir, manifest.entry).readText()
        val includes = manifest.includeDirs.flatMap { includeDir ->
            File(dir, includeDir).walkTopDown()
                .filter { it.isFile && it.extension.equals("js", ignoreCase = true) }
                .map { IncludedScript(it.relativeTo(dir).path.replace(File.separatorChar, '/'), it.readText()) }
                .toList()
        }.sortedBy { it.path }
        return LyricoPluginSource(
            manifest = manifest,
            assetDir = dir.absolutePath,
            script = composeScript(manifest, includes, entry),
            cacheRootDir = null,
            bundled = bundled,
        )
    }

    // ============ 导入 / 删除 ============

    fun importZip(zipFile: File): List<String> {
        rootDir.mkdirs()
        val tempDir = File(rootDir, ".import-${UUID.randomUUID()}")
        tempDir.mkdirs()
        try {
            unzip(zipFile, tempDir)
            val roots = tempDir.walkTopDown()
                .filter { it.isFile && it.name.equals("manifest.json", ignoreCase = true) }
                .mapNotNull { it.parentFile }
                .distinctBy { it.canonicalPath }
                .toList()
            require(roots.isNotEmpty()) { "Plugin manifest.json not found" }
            return roots.map { pluginDir ->
                val manifest = pluginJson.decodeFromString<PluginManifest>(File(pluginDir, "manifest.json").readText())
                validateManifest(manifest, entryExists = { File(pluginDir, manifest.entry).isFile })
                val target = File(rootDir, manifest.id.safeFileName())
                if (target.exists()) target.deleteRecursively()
                pluginDir.copyRecursively(target, overwrite = true)
                manifest.id
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun deletePlugin(id: String): Boolean {
        val source = loadSources().firstOrNull { it.manifest.id == id } ?: return false
        if (source.bundled) return false
        val dir = File(rootDir, id.safeFileName())
        val deleted = dir.isDirectory && dir.deleteRecursively()
        if (deleted) {
            val state = readState()
            writeState(state.copy(enabledIds = state.enabledIds - id, configs = state.configs - id))
        }
        return deleted
    }

    // ============ 启用状态 / 配置 ============

    fun enabledIds(): Set<String> = readState().enabledIds.toSet()

    fun setEnabled(id: String, enabled: Boolean) {
        val state = readState()
        val next = if (enabled) (state.enabledIds + id).distinct() else state.enabledIds - id
        writeState(state.copy(enabledIds = next))
    }

    fun pluginConfig(id: String): Map<String, String> = readState().configs[id].orEmpty()

    fun setConfigValue(id: String, key: String, value: String) {
        val state = readState()
        val pluginConfig = state.configs[id].orEmpty().toMutableMap()
        pluginConfig[key] = value
        writeState(state.copy(configs = state.configs + (id to pluginConfig)))
    }

    // ============ 脚本组装（与上游逐字一致） ============

    private fun composeScript(
        manifest: PluginManifest,
        includeSources: List<IncludedScript>,
        entryContent: String,
    ): String {
        val includePathSetJson = pluginJson.encodeToString(includeSources.map { it.path }.toSet())
        return buildString {
            append(
                """
                (function() {
                  var __lyricoDeclaredIncludes = $includePathSetJson;
                  var __lyricoDeclaredIncludeMap = Object.create(null);
                  __lyricoDeclaredIncludes.forEach(function(path) {
                    __lyricoDeclaredIncludeMap[path] = true;
                  });
                  globalThis.include = function(path) {
                    path = String(path || "");
                    if (!Object.prototype.hasOwnProperty.call(__lyricoDeclaredIncludeMap, path)) {
                      throw new Error("Include path is not declared in includeDirs: " + path);
                    }
                  };
                })();
                """.trimIndent(),
            )
            includeSources.forEach { source ->
                append("\n;\n// ===== Plugin include: ${source.path} =====\n")
                append(source.content)
                append("\n//# sourceURL=${source.path}\n")
            }
            append("\n;\n// ===== Plugin entry: ${manifest.entry} =====\n")
            append(entryContent)
            append("\n//# sourceURL=${manifest.entry}\n")
        }
    }

    // ============ 校验 / 工具 ============

    private fun validateManifest(manifest: PluginManifest, entryExists: () -> Boolean) {
        require(HostApiRegistry.supportsPluginApiVersion(manifest.apiVersion)) {
            "Unsupported plugin apiVersion: ${manifest.apiVersion}"
        }
        require(HostApiRegistry.supportsHostApiVersion(manifest.minHostApiVersion)) {
            "Unsupported minHostApiVersion: ${manifest.minHostApiVersion}"
        }
        require(manifest.entry.isNotBlank()) { "Missing plugin entry" }
        require(entryExists()) { "Missing plugin entry file: ${manifest.entry}" }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_PLUGIN_ZIP_ENTRIES) { "Plugin archive contains too many entries" }
                val target = File(targetDir, entry.name).canonicalFile
                require(target.path.startsWith(targetDir.canonicalPath + File.separator)) { "Invalid zip entry" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    val written = target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            written += count
                            require(written <= MAX_PLUGIN_ENTRY_BYTES) { "Plugin entry exceeds the size limit" }
                            output.write(buffer, 0, count)
                        }
                        written
                    }
                    totalBytes += written
                    require(totalBytes <= MAX_PLUGIN_ARCHIVE_BYTES) { "Plugin archive expands beyond the size limit" }
                }
                zip.closeEntry()
            }
        }
    }

    private fun readResource(path: String): String? =
        runCatching {
            javaClass.classLoader?.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

    private fun String.safeFileName(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "plugin" }

    private data class IncludedScript(val path: String, val content: String)

    private companion object {
        const val MAX_PLUGIN_ZIP_ENTRIES = 512
        const val MAX_PLUGIN_ENTRY_BYTES = 16L * 1024L * 1024L
        const val MAX_PLUGIN_ARCHIVE_BYTES = 64L * 1024L * 1024L
    }
}
