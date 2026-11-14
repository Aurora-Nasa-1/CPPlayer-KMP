package cp.player.core.provider.typed

import cp.player.sdk.ProviderContext
import cp.player.sdk.jni.JniProviderSession
import cp.player.sdk.jni.NativeWireCodec
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * C ABI（`cpp_*`）的 FFM（Project Panama）实现 —— 见 `docs/dev/PROVIDER_NATIVE_ABI.md`。
 *
 * 为什么用 FFM 而不是 JNI / JNA：
 * - **JNI 不行**：JNI 绑定挂在类对象上，要「每包独立」就得按包 `defineClass`，
 *   而 native image 没有类文件资源、也不支持运行时定义类；
 * - **FFM 可以**：`SymbolLookup.libraryLookup(path, arena)` 每个包各自一个库句柄，
 *   按符号取 `MethodHandle` 直接调，绑定天然独立 —— 这正是 JVM 上 defineClass 想达到的效果；
 * - 项目里已有先例：`DesktopHiResEngine.setProcessEnv`（kernel32）与 `DesktopMonitoring`（libsentry），
 *   在 Windows 原生镜像里跑着。
 *
 * ⚠️ 两条硬约束，抄的时候别绕开：
 * 1. **不能用 `invokeExact`**：Kotlin 会把调用点描述符编译成返回 `Object`，与 handle 的
 *    `int`/`long` 返回类型不一致 ⇒ `WrongMethodTypeException`（`DesktopHiResEngine` 记着这次事故）。
 *    一律用 `invokeWithArguments`（内部走 `asType`，返回值装箱）。
 * 2. 只用 JDK 22 以前就有名字的 API（`Arena.allocate` + `asByteBuffer`），不要用
 *    `Arena.allocateFrom(String)` 这类 22+ 方法 —— 桌面运行时可能是 JBR 21。
 */
internal actual fun cAbiSessionFactory(library: File): CAbiSessionFactory? {
    // 探测失败（不是 C ABI 库、缺符号、版本不对、库加载不了）一律当作「不提供 C ABI」。
    val arena = Arena.ofShared()
    return try {
        FfmCAbiSessionFactory(arena, library)
    } catch (_: Throwable) {
        runCatching { arena.close() }
        null
    }
}

/** 本实现支持的 ABI 版本；不匹配就当不支持（未来 v2 在这里分支）。 */
private const val SUPPORTED_ABI_VERSION = 1

/** `cpp_invoke` 返回码，见规范第 4 节。 */
private const val INVOKE_OK = 0
private const val INVOKE_BUFFER_TOO_SMALL = 1

/** `cpp_create` 的错误文本缓冲；512 字节够放一句人话。 */
private const val ERROR_CAPACITY = 512

/** 响应缓冲初值 / 上限：Wire 报文远小于此，上限只用来防 provider 回一个荒唐长度。 */
private const val INITIAL_RESPONSE_CAPACITY = 16 * 1024
private const val MAX_RESPONSE_CAPACITY = 16 * 1024 * 1024

private class FfmCAbiSessionFactory(
    private val arena: Arena,
    library: File,
) : CAbiSessionFactory {
    private val linker: Linker = Linker.nativeLinker()
    private val symbols: SymbolLookup = SymbolLookup.libraryLookup(library.absolutePath, arena)

    private val createHandle: MethodHandle = downcall(
        "cpp_create",
        FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
    )
    private val invokeHandle: MethodHandle = downcall(
        "cpp_invoke",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
    )
    private val cancelHandle: MethodHandle = downcall("cpp_cancel", FunctionDescriptor.ofVoid(JAVA_LONG))
    private val closeHandle: MethodHandle = downcall("cpp_close", FunctionDescriptor.ofVoid(JAVA_LONG))

    @Volatile
    private var closed = false

    init {
        val version = downcall("cpp_abi_version", FunctionDescriptor.of(JAVA_INT)).invokeWithArguments() as Int
        check(version == SUPPORTED_ABI_VERSION) { "Unsupported native provider ABI version: $version" }
    }

    private fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(symbols.find(name).orElse(null) ?: error("Missing native export: $name"), descriptor)

    override fun open(context: ProviderContext, settingsJson: String): JniProviderSession {
        check(!closed) { "Native provider closed" }
        Arena.ofConfined().use { call ->
            val err = call.allocate(ERROR_CAPACITY.toLong())
            val handle = createHandle.invokeWithArguments(
                cString(call, context.providerId),
                cString(call, context.accountId),
                context.credential?.let { cString(call, it) } ?: MemorySegment.NULL,
                cString(call, settingsJson),
                err,
                ERROR_CAPACITY,
            ) as Long
            check(handle != 0L) {
                "Native SDK session creation failed: ${readCString(err, ERROR_CAPACITY)}"
            }
            return FfmCAbiNativeSession(this, handle)
        }
    }

    /**
     * 一次 `cpp_invoke`：请求字节从 Kotlin 拷进 native 内存，响应拷回来。
     * 容量不足（返回 1）时按库回填的 `outLen` 扩容重试 —— 规范第 4 节，重试是幂等的。
     */
    fun call(handle: Long, operation: Int, request: ByteArray): ByteArray {
        check(!closed) { "Native provider closed" }
        // 每次调用一个 confined arena：请求/响应缓冲随调用结束释放，
        // 库句柄仍挂在共享 arena 上（多线程可并发 invoke 不同会话）。
        Arena.ofConfined().use { call ->
            val requestSegment = call.allocate(request.size.toLong().coerceAtLeast(1L))
            if (request.isNotEmpty()) requestSegment.asByteBuffer().put(request)
            var capacity = (request.size * 4).coerceIn(INITIAL_RESPONSE_CAPACITY, MAX_RESPONSE_CAPACITY)
            repeat(3) {
                val out = call.allocate(capacity.toLong())
                val outLength = call.allocate(JAVA_INT)
                val code = invokeHandle.invokeWithArguments(
                    handle,
                    operation,
                    requestSegment,
                    request.size,
                    out,
                    capacity,
                    outLength,
                ) as Int
                when (code) {
                    INVOKE_OK -> {
                        val length = outLength.get(JAVA_INT, 0L)
                        check(length in 0..capacity) { "Native provider returned an invalid response length: $length" }
                        val response = ByteArray(length)
                        if (length > 0) out.asSlice(0L, length.toLong()).asByteBuffer().get(response)
                        return response
                    }
                    INVOKE_BUFFER_TOO_SMALL -> {
                        val needed = outLength.get(JAVA_INT, 0L)
                        check(needed in (capacity + 1)..MAX_RESPONSE_CAPACITY) {
                            "Native provider asked for an invalid response capacity: $needed"
                        }
                        capacity = needed
                    }
                    else -> throw IllegalStateException("Native provider invoke failed (code $code)")
                }
            }
        }
        throw IllegalStateException("Native provider response exceeded $MAX_RESPONSE_CAPACITY bytes")
    }

    fun cancel(handle: Long) {
        if (handle == 0L) return
        runCatching { cancelHandle.invokeWithArguments(handle) }
    }

    fun closeSession(handle: Long) {
        if (handle == 0L) return
        closeHandle.invokeWithArguments(handle)
    }

    /** 只在所有会话关闭后调用：关掉 arena 就等于卸载库，之后该目录才能被替换/删除。 */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { arena.close() }
    }
}

private class FfmCAbiNativeSession(
    private val factory: FfmCAbiSessionFactory,
    private var handle: Long,
) : JniProviderSession {
    override fun invoke(operation: Int, request: Any): Any {
        val current = handle
        check(current != 0L) { "Native SDK session closed" }
        val response = factory.call(current, operation, NativeWireCodec.encodeRequest(operation, request))
        return NativeWireCodec.decodeResponse(operation, response)
    }

    override fun cancel() = factory.cancel(handle)

    override fun close() {
        val previous = handle
        handle = 0L
        if (previous != 0L) factory.closeSession(previous)
    }
}

/** UTF-8 + NUL 结尾的 C 串；内存归 [arena]，provider 不得留存。 */
private fun cString(arena: Arena, value: String): MemorySegment {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val segment = arena.allocate(bytes.size.toLong() + 1L)
    if (bytes.isNotEmpty()) segment.asByteBuffer().put(bytes)
    segment.set(JAVA_BYTE, bytes.size.toLong(), 0)
    return segment
}

/** 读到第一个 NUL 为止；provider 没写 NUL 也不会跑飞（有 [capacity] 上限）。 */
private fun readCString(segment: MemorySegment, capacity: Int): String {
    val bytes = ByteArray(capacity)
    var length = 0
    while (length < capacity) {
        val byte = segment.get(JAVA_BYTE, length.toLong())
        if (byte.toInt() == 0) break
        bytes[length] = byte
        length++
    }
    return bytes.decodeToString(0, length)
}
