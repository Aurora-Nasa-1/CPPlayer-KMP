package cp.player.core.provider.typed

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers

/**
 * QuickJS 运行时异步预热器。
 *
 * 桌面与 Android JVM 进程拉起后即刻调用 [warmupAsync]，
 * 在后台守护线程提前完成 QuickJS 类的加载、本地 C 动态库（.so / .dll / .dylib）的 dlopen，
 * 并预热基础 JNI 交互链路，消除用户首次访问 JS 音源/插件时的首发延迟。
 */
object QuickJsWarmup {
    @Volatile
    private var isWarmed = false

    fun warmupAsync() {
        if (isWarmed) return
        Thread {
            runCatching {
                if (isWarmed) return@runCatching
                val qjs = QuickJs.create(Dispatchers.Default)
                try {
                    qjs.evaluate<Int>("1 + 1")
                } finally {
                    qjs.close()
                }
                isWarmed = true
            }
        }.apply {
            isDaemon = true
            name = "QuickJs-Prewarm"
        }.start()
    }
}
