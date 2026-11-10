package cp.player.core.provider

import cp.player.core.provider.typed.*
import cp.player.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Measurements are observations, not relative performance promises. */
class KmpSdkBenchmarkTest {
    @Test
    fun `record cold steady search batch and value bridge costs`() {
        runBlocking {
            for (kind in listOf("kotlin", "js")) {
                val runtime = createSdkRuntime(emptySet())
                val start = System.nanoTime()
                val provider: MusicProvider = if (kind == "js") JsSdkProvider("""
                    JSON.parse = JSON.stringify = () => { throw Error("JSON forbidden"); };
                    const make = id => ({ id, title: "Benchmark", durationMs: 9500, artists: [] });
                    globalThis.provider = {
                        capabilities: ["tracks.search", "tracks.get", "tracks.getMany"],
                        search(r) { return { items: ["001","UUID-A","A","a"].map(make), total: 4 }; },
                        get(r) { return make(r.id); },
                        getMany(r) { return { items: r.ids.map(id => ({ id, track: make(id) })) }; }
                    };
                """.trimIndent(), ProviderContext("bench", "default", emptyMap(), null, runtime))
                else object : MusicProvider {
                    override suspend fun capabilities() = Capabilities(listOf(Capability.GET, Capability.BATCH, Capability.SEARCH))
                    override suspend fun get(request: IdRequest) = Track(request.id, "Benchmark", durationMs = 9_500)
                    override suspend fun search(request: SearchRequest) = TrackPage(
                        listOf("001", "UUID-A", "A", "a").map { Track(it, "Benchmark", durationMs = 9_500) }, 4)
                }
                val host = ProviderHost(ProviderCallContext(ProviderSessionKey("bench", "default", 0)), provider)
                try {
                    host.search(SearchRequest("all", 4, 0))
                    val cold = System.nanoTime() - start
                    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                    if (bean.isThreadAllocatedMemorySupported) bean.isThreadAllocatedMemoryEnabled = true
                    fun allocations(): Map<Long, Long> = if (!bean.isThreadAllocatedMemoryEnabled) emptyMap()
                    else bean.allThreadIds.associateWith { bean.getThreadAllocatedBytes(it).coerceAtLeast(0) }
                    val cases: List<Pair<String, suspend () -> Unit>> = listOf(
                        "search-4" to { assertEquals(4, host.search(SearchRequest("all", 4, 0)).items.size) },
                        "batch-500" to { assertEquals(500, host.getMany(List(500) { "001" }).items.size) },
                        "get-value-100" to { repeat(100) { host.get("001") }; Unit },
                    )
                    val results = buildJsonArray {
                        for ((name, block) in cases) {
                            repeat(3) { block() }
                            val samples = List(9) {
                                val before = allocations()
                                val elapsed = measureNanoTime { block() }
                                val after = allocations()
                                elapsed to after.entries.sumOf { (id, total) -> (total - (before[id] ?: total)).coerceAtLeast(0) }
                            }
                            add(buildJsonObject {
                                put("case", name); put("warmup", 3); put("iterations", 9)
                                put("elapsedNs", JsonArray(samples.map { JsonPrimitive(it.first) }))
                                put("jvmAllocatedBytesEstimate", JsonArray(samples.map { JsonPrimitive(it.second) }))
                            })
                        }
                    }
                    val report = buildJsonObject {
                        put("adapter", kind); put("os", System.getProperty("os.name"))
                        put("arch", System.getProperty("os.arch")); put("java", System.getProperty("java.runtime.version"))
                        put("processors", Runtime.getRuntime().availableProcessors())
                        put("coldCreateAndFirstSearchNs", cold)
                        put("allocationScope", "JVM live-thread counters only; excludes QuickJS native heap and terminated threads")
                        put("network", "none; isolates SDK and value bridge, not production service latency")
                        put("results", results)
                    }
                    val directory = File(System.getProperty("cp.typed.repo", ".."), "artifacts/provider-sdk").apply { mkdirs() }
                    directory.resolve("benchmark-$kind.json").writeText(report.toString(), Charsets.UTF_8)
                } finally { host.close(); runtime.close() }
            }
        }
    }
}
