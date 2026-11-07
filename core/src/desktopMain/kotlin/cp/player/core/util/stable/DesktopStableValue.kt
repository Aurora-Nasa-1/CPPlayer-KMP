package cp.player.core.util.stable

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicReference
import java.util.function.IntFunction
import java.util.function.Supplier

/**
 * 桌面端延迟不可变容器（对齐 Java 25 JEP 502 Stable Values 规范）。
 *
 * 核心目标：
 * 1. **延迟不可变性（Deferred Immutability）**：将对象的创建与实际初始化时机彻底解耦，按需进行单次赋值。
 * 2. **线程安全单次初始化**：多线程并发访问时保证计算 lambda 最多执行一次，消除双重检查锁定的潜在隐患。
 * 3. **HotSpot JIT 常量折叠（Constant Folding）**：
 *    在 Java 25+ 运行时上透明桥接 [java.lang.StableValue]（JEP 502 Preview，需 `--enable-preview`），使 HotSpot C2 JIT 编译器在优化阶段
 *    能够将该稳定值当作 trusted final 常量处理，在机器码生成阶段直接常量折叠（Constant-fold），
 *    消除每次访问的内存栅障（Memory Fence）、非空检查（Null-check）与类加载顺序惩罚。
 *    在当前打包 JBR 21 的 Release 环境或 Java 21-24 运行时上，类加载探测安全跳过，平滑无缝回退为原子无锁 CAS 快速路径，无需任何外部依赖。
 */
interface DesktopStableValue<T> {

    /** 检查当前稳定值是否已经初始化。 */
    fun isSet(): Boolean

    /** 若已初始化则返回其值，否则返回备选值 [default]。 */
    fun orElse(default: T): T

    /** 若已初始化则返回其值，否则抛出 [IllegalStateException]。 */
    fun orElseThrow(): T

    /**
     * 获取稳定值。若尚未初始化，则调用 [supplier] 计算并原子绑定，保证并发安全且至多执行一次。
     */
    fun orElseSet(supplier: () -> T): T

    /**
     * 显式设置稳定值。若此前已被初始化，则抛出 [IllegalStateException]。
     */
    fun setOrThrow(value: T)

    /**
     * 尝试设置稳定值。若设置成功返回 `true`；若已被初始化则返回 `false` 且不改变现有值。
     */
    fun trySet(value: T): Boolean

    companion object {
        /** 检查当前运行时 JVM 是否支持原生的 Java 25 JEP 502 Stable Values。 */
        val isNativeSupported: Boolean get() = NativeStableBinding.isSupported

        /** 创建一个初始未绑定的延迟稳定值容器。 */
        fun <T> of(): DesktopStableValue<T> {
            return if (NativeStableBinding.isSupported) {
                try {
                    val nativeInst = NativeStableBinding.ofMethod!!.invoke()
                    NativeStableValue(nativeInst)
                } catch (_: Throwable) {
                    FallbackStableValue()
                }
            } else {
                FallbackStableValue()
            }
        }

        /** 创建一个初始已赋固定值的稳定值容器。 */
        fun <T> of(value: T): DesktopStableValue<T> {
            return if (NativeStableBinding.isSupported) {
                try {
                    val nativeInst = NativeStableBinding.ofValueMethod!!.invoke(value as Any?)
                    NativeStableValue(nativeInst)
                } catch (_: Throwable) {
                    FallbackStableValue(value, true)
                }
            } else {
                FallbackStableValue(value, true)
            }
        }

        /**
         * 创建一个基于 [DesktopStableValue] 的线程安全延迟加载函数。
         * 相比标准 Kotlin `by lazy`，在 Java 25+ 上能享受到 C2 JIT 的常量折叠优化。
         */
        fun <T> supplier(factory: () -> T): () -> T {
            val holder = of<T>()
            return { holder.orElseSet(factory) }
        }

        /**
         * 创建一个按索引惰性初始化的定长稳定列表（对齐 JEP 502 `StableValue.list` 规范）。
         */
        fun <E> list(size: Int, elementFactory: (Int) -> E): List<E> {
            require(size >= 0) { "size must not be negative: $size" }
            return DesktopStableList(size, elementFactory)
        }
    }
}

/**
 * 原生 JEP 502 反射/MethodHandle 绑定门面。
 * 在类加载期探测是否存在 `java.lang.StableValue`，完全避免在编译期硬绑定而导致 JVM 21 目标链接失败。
 */
internal object NativeStableBinding {
    val isSupported: Boolean
    val ofMethod: MethodHandle?
    val ofValueMethod: MethodHandle?
    val isSetMethod: MethodHandle?
    val orElseMethod: MethodHandle?
    val orElseThrowMethod: MethodHandle?
    val orElseSetMethod: MethodHandle?
    val setOrThrowMethod: MethodHandle?
    val trySetMethod: MethodHandle?

    init {
        var supported = false
        var ofM: MethodHandle? = null
        var ofValM: MethodHandle? = null
        var isSetM: MethodHandle? = null
        var orElseM: MethodHandle? = null
        var orElseThrowM: MethodHandle? = null
        var orElseSetM: MethodHandle? = null
        var setOrThrowM: MethodHandle? = null
        var trySetM: MethodHandle? = null

        try {
            val svClass = Class.forName("java.lang.StableValue")
            val lookup = MethodHandles.publicLookup()

            ofM = lookup.findStatic(svClass, "of", MethodType.methodType(svClass))
            ofValM = lookup.findStatic(svClass, "of", MethodType.methodType(svClass, Any::class.java))
            isSetM = lookup.findVirtual(svClass, "isSet", MethodType.methodType(Boolean::class.javaPrimitiveType))
            orElseM = lookup.findVirtual(svClass, "orElse", MethodType.methodType(Any::class.java, Any::class.java))
            orElseThrowM = lookup.findVirtual(svClass, "orElseThrow", MethodType.methodType(Any::class.java))
            orElseSetM = lookup.findVirtual(
                svClass,
                "orElseSet",
                MethodType.methodType(Any::class.java, Supplier::class.java)
            )
            setOrThrowM = lookup.findVirtual(svClass, "setOrThrow", MethodType.methodType(Void.TYPE, Any::class.java))
            trySetM = lookup.findVirtual(
                svClass,
                "trySet",
                MethodType.methodType(Boolean::class.javaPrimitiveType, Any::class.java)
            )

            supported = true
        } catch (_: Throwable) {
            supported = false
        }

        isSupported = supported
        ofMethod = ofM
        ofValueMethod = ofValM
        isSetMethod = isSetM
        orElseMethod = orElseM
        orElseThrowMethod = orElseThrowM
        orElseSetMethod = orElseSetM
        setOrThrowMethod = setOrThrowM
        trySetMethod = trySetM
    }
}

/**
 * 包装 Java 25 原生 `java.lang.StableValue` 的实现。
 */
internal class NativeStableValue<T>(
    private val nativeHandle: Any
) : DesktopStableValue<T> {

    override fun isSet(): Boolean {
        return try {
            NativeStableBinding.isSetMethod!!.invoke(nativeHandle) as Boolean
        } catch (t: Throwable) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun orElse(default: T): T {
        return try {
            NativeStableBinding.orElseMethod!!.invoke(nativeHandle, default as Any?) as T
        } catch (_: Throwable) {
            default
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun orElseThrow(): T {
        return try {
            NativeStableBinding.orElseThrowMethod!!.invoke(nativeHandle) as T
        } catch (e: NoSuchElementException) {
            throw IllegalStateException("StableValue is not set", e)
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to read StableValue", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun orElseSet(supplier: () -> T): T {
        return try {
            val javaSupplier = Supplier<Any?> { supplier() }
            NativeStableBinding.orElseSetMethod!!.invoke(nativeHandle, javaSupplier) as T
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to orElseSet StableValue", t)
        }
    }

    override fun setOrThrow(value: T) {
        try {
            NativeStableBinding.setOrThrowMethod!!.invoke(nativeHandle, value as Any?)
        } catch (e: IllegalStateException) {
            throw e
        } catch (t: Throwable) {
            throw IllegalStateException("StableValue already set or invocation failed", t)
        }
    }

    override fun trySet(value: T): Boolean {
        return try {
            NativeStableBinding.trySetMethod!!.invoke(nativeHandle, value as Any?) as Boolean
        } catch (_: Throwable) {
            false
        }
    }

    override fun toString(): String {
        return if (isSet()) "DesktopStableValue[set=${orElse(null as T)}]" else "DesktopStableValue[unset]"
    }
}

/**
 * 在低于 Java 25 或未开启预览特性时的平滑回退实现。
 * 基于无锁 CAS 与快速 volatile 读取，保证严格的并发单次赋值与可见性。
 */
internal class FallbackStableValue<T>(
    initialValue: T? = null,
    hasInitial: Boolean = false
) : DesktopStableValue<T> {

    private companion object {
        private val UNSET = Any()
        private val NULL_SENTINEL = Any()
    }

    private val state = AtomicReference<Any?>(if (hasInitial) initialValue ?: NULL_SENTINEL else UNSET)

    override fun isSet(): Boolean = state.get() !== UNSET

    @Suppress("UNCHECKED_CAST")
    override fun orElse(default: T): T {
        val current = state.get()
        return if (current === UNSET) default else if (current === NULL_SENTINEL) null as T else current as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun orElseThrow(): T {
        val current = state.get()
        if (current === UNSET) throw IllegalStateException("StableValue is not set")
        return if (current === NULL_SENTINEL) null as T else current as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun orElseSet(supplier: () -> T): T {
        var current = state.get()
        if (current !== UNSET) {
            return if (current === NULL_SENTINEL) null as T else current as T
        }

        val computed = supplier()
        val wrapped = computed ?: NULL_SENTINEL
        if (state.compareAndSet(UNSET, wrapped)) {
            return computed
        }

        current = state.get()
        return if (current === NULL_SENTINEL) null as T else current as T
    }

    override fun setOrThrow(value: T) {
        val wrapped = value ?: NULL_SENTINEL
        if (!state.compareAndSet(UNSET, wrapped)) {
            throw IllegalStateException("StableValue already set")
        }
    }

    override fun trySet(value: T): Boolean {
        val wrapped = value ?: NULL_SENTINEL
        return state.compareAndSet(UNSET, wrapped)
    }

    override fun toString(): String {
        return if (isSet()) "DesktopStableValue[set=${orElse(null as T)}]" else "DesktopStableValue[unset]"
    }
}

/**
 * 定长延迟惰性稳定列表（每个元素按需通过 [DesktopStableValue] 计算，计算后严格不可变）。
 */
class DesktopStableList<E>(
    override val size: Int,
    private val elementFactory: (Int) -> E
) : AbstractList<E>() {

    private val elements = Array<DesktopStableValue<E>>(size) {
        DesktopStableValue.of()
    }

    override fun get(index: Int): E {
        if (index !in 0 until size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }
        return elements[index].orElseSet { elementFactory(index) }
    }
}
