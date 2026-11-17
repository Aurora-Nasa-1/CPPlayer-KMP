package cp.player.core.util

import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException
import java.util.NoSuchElementException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * 基于 Java 25 JEP 506（Scoped Values）的现代不可变范围绑定值抽象。
 *
 * ## 为什么引入 ScopedValue 替代 ThreadLocal
 * 1. **不可变性（Immutability）**：一旦绑定无法随意 `set()` 篡改，数据流向清晰确定；
 * 2. **受限生命周期（Bounded Lifetime）**：在代码作用域退出时自动解绑，彻底杜绝虚拟线程下的内存泄漏；
 * 3. **为虚拟线程与结构化并发而生**：传统的 `ThreadLocal` 在生成数万/数十万虚拟线程时会导致
 *    每个虚拟线程持有一份独立的 Map，严重消耗内存并破坏 Carrier Thread 的执行局部性；
 *    `ScopedValue` 采用轻量不可变快照树在父子虚拟线程间高效共享，常量时间寻址且零复制成本；
 * 4. **跨端优雅降级**：在搭载 JBR 25 / JDK 25+ 的桌面端完整利用原生 `java.lang.ScopedValue`；
 *    在 Android ART 或旧版运行时上自动优雅降级，杜绝任何 `NoClassDefFoundError`。
 */
class DesktopScopedValue<T> internal constructor(
    val name: String,
    internal val nativeScopedValue: Any?,
) {

    /** 检查当前作用域内是否已绑定此 ScopedValue。 */
    fun isBound(): Boolean {
        // 1. 优先检查协程挂起上下文 / 回退绑定（协程 withBindings 作用域快速命中，避免无效反射调用）
        val fallbackMap = FallbackBinding.holder.get()
        if (fallbackMap != null && fallbackMap.containsKey(this)) {
            return true
        }
        // 2. 检查 Java 25 原生 ScopedValue（同步 runWith 路径）
        if (nativeScopedValue != null && NativeBinding.isSupported) {
            try {
                if (NativeBinding.isBoundMethod!!.invoke(nativeScopedValue) as Boolean) {
                    return true
                }
            } catch (_: Throwable) {
                // fall through
            }
        }
        return false
    }

    /**
     * 获取当前作用域绑定的值。
     * 若未绑定，抛出 [NoSuchElementException]。
     */
    @Suppress("UNCHECKED_CAST")
    fun get(): T {
        // 1. 优先检查协程挂起上下文 / 回退绑定
        val fallbackMap = FallbackBinding.holder.get()
        if (fallbackMap != null && fallbackMap.containsKey(this)) {
            return fallbackMap[this] as T
        }
        // 2. 检查 Java 25 原生 ScopedValue
        if (nativeScopedValue != null && NativeBinding.isSupported) {
            try {
                if (NativeBinding.isBoundMethod!!.invoke(nativeScopedValue) as Boolean) {
                    return NativeBinding.getMethod!!.invoke(nativeScopedValue) as T
                }
            } catch (e: NoSuchElementException) {
                throw e
            } catch (e: InvocationTargetException) {
                val target = e.targetException
                if (target is NoSuchElementException) throw target
            } catch (_: Throwable) {
                // fall through
            }
        }
        throw NoSuchElementException("ScopedValue [$name] not bound")
    }

    /**
     * 获取当前作用域绑定的值，若未绑定返回 `null`。
     */
    fun getOrNull(): T? {
        return if (isBound()) {
            try {
                get()
            } catch (_: NoSuchElementException) {
                null
            }
        } else {
            null
        }
    }

    /**
     * 获取当前作用域绑定的值，若未绑定返回 [default]。
     */
    fun orElse(default: T): T {
        return if (isBound()) {
            try {
                get()
            } catch (_: NoSuchElementException) {
                default
            }
        } else {
            default
        }
    }

    /**
     * 在绑定当前 [value] 的受限作用域中执行同步代码块 [block]。
     * 退出代码块时自动解绑，天然无泄漏。
     */
    fun <R> runWith(value: T, block: () -> R): R {
        return DesktopScopedContext.runWith(this to value, block = block)
    }

    /**
     * 在绑定当前 [value] 的受限作用域中执行挂起代码块 [block]。
     */
    suspend fun <R> withValue(value: T, block: suspend () -> R): R {
        return DesktopScopedContext.withBindings(this to value, block = block)
    }

    override fun toString(): String = "DesktopScopedValue($name)"

    companion object {
        /**
         * 检查当前 JVM 运行时是否真正支持原生的 Java 25 JEP 506 Scoped Values。
         */
        val isNativeSupported: Boolean get() = NativeBinding.isSupported

        /**
         * 创建一个新的强类型 [DesktopScopedValue]。
         */
        fun <T> create(name: String): DesktopScopedValue<T> {
            val nativeObj = if (NativeBinding.isSupported) {
                try {
                    NativeBinding.newInstanceMethod!!.invoke(null)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            return DesktopScopedValue(name, nativeObj)
        }
    }

    /** JEP 506 原生反射绑定门面；只使用旧版 Android 可链接的 java.lang.reflect API。 */
    internal object NativeBinding {
        val isSupported: Boolean
        val newInstanceMethod: Method?
        val whereMethod: Method?
        val getMethod: Method?
        val isBoundMethod: Method?
        val carrierWhereMethod: Method?
        val carrierRunMethod: Method?

        init {
            var supported = false
            var newInst: Method? = null
            var where: Method? = null
            var get: Method? = null
            var isBound: Method? = null
            var carrierWhere: Method? = null
            var carrierRun: Method? = null

            if (!PlatformInfo.isAndroid) {
                runCatching {
                    val svClass = Class.forName("java.lang.ScopedValue")
                    val carrierClass = Class.forName("java.lang.ScopedValue\$Carrier")
                    newInst = svClass.getMethod("newInstance")
                    where = svClass.getMethod("where", svClass, Any::class.java)
                    get = svClass.getMethod("get")
                    isBound = svClass.getMethod("isBound")
                    carrierWhere = carrierClass.getMethod("where", svClass, Any::class.java)
                    carrierRun = carrierClass.getMethod("run", Runnable::class.java)
                    supported = true
                }
            }

            isSupported = supported
            newInstanceMethod = newInst
            whereMethod = where
            getMethod = get
            isBoundMethod = isBound
            carrierWhereMethod = carrierWhere
            carrierRunMethod = carrierRun
        }
    }

    /** 跨端（如 Android ART 或协程挂起上下文）安全回退绑定。 */
    internal object FallbackBinding {
        val holder = ThreadLocal<Map<DesktopScopedValue<*>, Any?>>()

        fun currentMap(): Map<DesktopScopedValue<*>, Any?> = holder.get() ?: emptyMap()

        fun isBound(key: DesktopScopedValue<*>): Boolean {
            val map = holder.get() ?: return false
            return map.containsKey(key)
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> get(key: DesktopScopedValue<T>): T {
            val map = holder.get()
            if (map == null || !map.containsKey(key)) {
                throw NoSuchElementException("ScopedValue [${key.name}] not bound")
            }
            return map[key] as T
        }

        fun <R> runWith(bindings: Map<DesktopScopedValue<*>, Any?>, block: () -> R): R {
            val prev = holder.get()
            val next = if (prev != null) prev + bindings else bindings
            holder.set(next)
            return try {
                block()
            } finally {
                if (prev != null) {
                    holder.set(prev)
                } else {
                    holder.remove()
                }
            }
        }
    }
}

/**
 * 多 [DesktopScopedValue] 批量范围绑定与上下文管理门面。
 */
object DesktopScopedContext {

    /**
     * 批量绑定一组键值并在该受限作用域内执行同步代码块 [block]。
     * 若在支持 JEP 506 的 JVM 上运行，原生 `ScopedValue` 载体链生效且无 ThreadLocal 污染；
     * 若在不支持原生 ScopedValue 的环境，则平滑降级到基于线程局部变量的回退表。
     */
    fun <R> runWith(vararg bindings: Pair<DesktopScopedValue<*>, Any?>, block: () -> R): R {
        if (bindings.isEmpty()) return block()

        val map = bindings.toMap()
        if (DesktopScopedValue.NativeBinding.isSupported) {
            val validBindings = bindings.filter { it.first.nativeScopedValue != null }
            if (validBindings.size == bindings.size && validBindings.isNotEmpty()) {
                try {
                    val first = validBindings.first()
                    var carrier = DesktopScopedValue.NativeBinding.whereMethod!!.invoke(
                        null,
                        first.first.nativeScopedValue,
                        first.second,
                    )
                    for (i in 1 until validBindings.size) {
                        val item = validBindings[i]
                        carrier = DesktopScopedValue.NativeBinding.carrierWhereMethod!!.invoke(
                            carrier,
                            item.first.nativeScopedValue,
                            item.second,
                        )
                    }

                    var result: R? = null
                    var failure: Throwable? = null
                    val runnable = Runnable {
                        try {
                            // 原生路径直接执行 block，无需二次写入 FallbackBinding ThreadLocal，消除冗余开销与双重状态
                            result = block()
                        } catch (t: Throwable) {
                            failure = t
                        }
                    }

                    DesktopScopedValue.NativeBinding.carrierRunMethod!!.invoke(carrier, runnable)
                    failure?.let { throw it }
                    @Suppress("UNCHECKED_CAST")
                    return result as R
                } catch (e: InvocationTargetException) {
                    throw e.targetException ?: e
                } catch (_: Throwable) {
                    // 原生异常回退
                }
            }
        }

        return DesktopScopedValue.FallbackBinding.runWith(map, block)
    }

    /**
     * 批量绑定一组键值并在该受限作用域内执行挂起代码块 [block]。
     * 利用协程上下文载体确保挂起点与恢复点自动传递已绑定的 ScopedValue。
     */
    suspend fun <R> withBindings(vararg bindings: Pair<DesktopScopedValue<*>, Any?>, block: suspend () -> R): R {
        if (bindings.isEmpty()) return block()
        val current = DesktopScopedValue.FallbackBinding.currentMap()
        val merged = current + bindings.toMap()

        return withContext(DesktopScopedValue.FallbackBinding.holder.asContextElement(merged)) {
            block()
        }
    }
}
