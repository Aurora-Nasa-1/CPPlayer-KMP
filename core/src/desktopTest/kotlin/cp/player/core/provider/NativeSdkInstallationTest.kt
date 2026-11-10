package cp.player.core.provider

import cp.player.core.pluginrepo.*
import cp.player.core.provider.typed.createSdkRuntime
import cp.player.core.util.PlatformContext
import cp.player.core.util.PlatformSupport
import cp.player.sdk.*
import cp.player.sdk.jni.NativeProviderFactory
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class NativeSdkInstallationTest {
    private fun manifest(version: String = "1.0.0") = ModuleManifest(
        "native-demo", "Native Demo", version, "jni", "adapter.jar",
        contract = PROVIDER_SDK_CONTRACT, nativeFactoryClass = "fixture.Factory",
        supportedAbis = listOf("x86_64"), capabilities = listOf(Capability.GET),
    )

    private fun packageZip(root: File, manifest: ModuleManifest): File {
        val workspace = File(root, manifest.version).apply { mkdirs() }
        val library = System.getProperty("cp.native.fixture")?.let(::File)
        val source = File(workspace, "Factory.java").apply {
            writeText("""
                package fixture;
                import cp.player.sdk.*;
                import cp.player.sdk.jni.*;
                public class Factory implements NativeProviderFactory {
                    static { ${if (library != null) """System.loadLibrary("provider_fixture");""" else ""} }
                    public Factory() {}
                    private static native String nativeTitle();
                    public JniProviderSession open(ProviderContext context) {
                        return new Session(context.getAccountId());
                    }
                    public static class Session implements JniProviderSession {
                        private final String account;
                        private boolean closed;
                        Session(String account) { this.account = account; }
                        public Object invoke(int operation, Object request) {
                            if (closed) throw new IllegalStateException("closed");
                            if (operation == ProviderProtocol.CAPABILITIES)
                                return new Capabilities(java.util.List.of(Capability.GET));
                            if (operation == ProviderProtocol.GET)
                                return new Track(((IdRequest)request).getId(),
                                    "${manifest.version}:" + account + ":" +
                                    ${if (library != null) "nativeTitle()" else "\"adapter\""},
                                    java.util.List.of(), 9500L, null, null);
                            throw new IllegalArgumentException("operation " + operation);
                        }
                        public void cancel() {}
                        public void close() { closed = true; }
                    }
                }
            """.trimIndent())
        }
        val classpath = listOf(NativeProviderFactory::class.java, Unit::class.java, kotlinx.coroutines.Job::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .joinToString(File.pathSeparator)
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(
            null, null, null, "-classpath", classpath, "-d", workspace.absolutePath, source.absolutePath,
        ))
        val jar = File(workspace, "adapter.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            File(workspace, "fixture").walkTopDown().filter { it.isFile }.forEach { file ->
                output.putNextEntry(JarEntry(file.relativeTo(workspace).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
            output.putNextEntry(JarEntry("padding"))
            output.write(ByteArray(1024 * 1024 + 1))
            output.closeEntry()
        }
        return File(workspace, "provider.zip").also { zip ->
            ZipOutputStream(zip.outputStream()).use { output ->
                fun add(path: String, bytes: ByteArray) {
                    output.putNextEntry(ZipEntry(path))
                    output.write(bytes)
                    output.closeEntry()
                }
                add("manifest.json", Json.encodeToString(manifest).encodeToByteArray())
                add("adapter.jar", jar.readBytes())
                library?.let { add("lib/x86_64/${System.mapLibraryName("provider_fixture")}", it.readBytes()) }
            }
        }
    }

    @Test
    fun `native packages install without registration survive moves update and restart`() {
        runBlocking {
            val root = Files.createTempDirectory("native-install-").toFile()
            val manager = ModuleManager(File(root, "modules").absolutePath, PlatformContext())
            val runtime = createSdkRuntime(emptySet())
            var firstSession: MusicProvider? = null
            var secondSession: MusicProvider? = null
            var restarted: BackendProvider? = null
            try {
                val first = assertNotNull(manager.importModuleDetails(packageZip(root, manifest()).absolutePath), manager.lastLoadError)
                assertEquals(ProviderType.JNI, first.provider.type)
                val context = ProviderContext("native-demo", "account-1", emptyMap(), null, runtime)
                firstSession = assertNotNull(first.provider.createSdkProvider(context))
                assertTrue(firstSession.get(IdRequest("001")).title.startsWith("1.0.0:account-1:"))
                val secondZip = packageZip(root, manifest("2.0.0").copy(type = "native"))
                val second = assertNotNull(manager.updateModuleDetails(secondZip.absolutePath, "native-demo"), manager.lastLoadError)
                assertTrue(second.isUpdate)
                // Closing the old descriptor cannot destroy a session that is still draining.
                assertEquals("001", firstSession.get(IdRequest("001")).id)
                secondSession = assertNotNull(second.provider.createSdkProvider(context))
                assertTrue(secondSession.get(IdRequest("002")).title.startsWith("2.0.0:account-1:"))
                firstSession.close()
                secondSession.close()
                restarted = assertNotNull(ProviderFactory.create(
                    manifest("2.0.0").copy(type = "native"), manager.getModuleDir("native-demo"),
                ))
                val session = assertNotNull(restarted.createSdkProvider(
                    ProviderContext("native-demo", "account-2", emptyMap(), null, runtime),
                ))
                try {
                    assertTrue(session.get(IdRequest("003")).title.startsWith("2.0.0:account-2:"))
                } finally { session.close() }
                // The standard DLL path needs neither an adapter JAR nor nativeFactoryClass.
                System.getProperty("cp.native.fixture")?.let { path ->
                    fun directZip(version: String): File {
                        val library = File(path)
                        val nativeManifest = manifest(version).copy(
                            nativeFactoryClass = null, entryPoint = library.name,
                        )
                        return File(root, "direct-$version.zip").also { zip ->
                            ZipOutputStream(zip.outputStream()).use { output ->
                                output.putNextEntry(ZipEntry("manifest.json"))
                                output.write(Json.encodeToString(nativeManifest).encodeToByteArray())
                                output.closeEntry()
                                output.putNextEntry(ZipEntry(library.name))
                                output.write(library.readBytes())
                                output.closeEntry()
                            }
                        }
                    }
                    val direct = assertNotNull(manager.importModuleDetails(directZip("3.0.0").absolutePath), manager.lastLoadError)
                    val older = assertNotNull(direct.provider.createSdkProvider(context))
                    var newer: MusicProvider? = null
                    try {
                        assertEquals(listOf(Capability.GET), older.capabilities().supported)
                        assertEquals("account-1", older.get(IdRequest("001")).title)
                        val updated = assertNotNull(manager.updateModuleDetails(
                            directZip("4.0.0").absolutePath, "native-demo",
                        ), manager.lastLoadError)
                        newer = assertNotNull(updated.provider.createSdkProvider(
                            ProviderContext("native-demo", "account-2", emptyMap(), null, runtime),
                        ))
                        assertEquals("account-2", newer.get(IdRequest("002")).title)
                        assertEquals("account-1", older.get(IdRequest("003")).title)
                        assertEquals(FailureCategory.UNSUPPORTED, assertFailsWith<ProviderException> {
                            newer.resolve(ResolveRequest("001"))
                        }.failure.category)
                    } finally { older.close(); newer?.close() }
                }
            } finally {
                firstSession?.close()
                secondSession?.close()
                restarted?.close()
                manager.getAvailableProviders().forEach { it.close() }
                runtime.close()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `repository accepts native adapter over script size limit and invalid update preserves installation`() {
        val root = Files.createTempDirectory("native-repository-").toFile()
        val manager = ModuleManager(File(root, "modules").absolutePath, PlatformContext())
        try {
            val zip = packageZip(root, manifest())
            val artifact = PluginArtifact(
                "windows", "x64", "jni", "https://example.org/native.zip",
                assertNotNull(PlatformSupport.sha256Hex(zip.absolutePath)), zip.length(),
            )
            val plugin = RepositoryPlugin("native-demo", RepositoryPluginKind.PROVIDER, "Native Demo", "1.0.0",
                artifacts = listOf(artifact))
            verifyRepositoryPackage(zip, plugin, artifact)
            val installed = assertNotNull(manager.installRepositoryModule(zip.absolutePath, "native-demo"))
            val bad = packageZip(root, manifest("3.0.0").copy(nativeFactoryClass = "missing.Factory"))
            assertNull(manager.updateModuleDetails(bad.absolutePath, "native-demo"))
            assertSame(installed.provider, manager.getProvider("native-demo"))
            assertEquals("1.0.0", Json.decodeFromString<ModuleManifest>(
                File(manager.getModuleDir("native-demo"), "manifest.json").readText(),
            ).version)
            assertTrue(manifest().copy(nativeFactoryClass = null).isLoadable)
            assertNull(ProviderFactory.create(
                manifest().copy(entryPoint = "../adapter.jar"), manager.getModuleDir("native-demo"),
            ))
            assertFailsWith<IllegalArgumentException> {
                ProviderFactory.create(manifest().copy(supportedAbis = listOf("not-this-abi")), manager.getModuleDir("native-demo"))
            }
        } finally {
            manager.getAvailableProviders().forEach { it.close() }
            root.deleteRecursively()
        }
    }
}
