package cp.player.kmp.util

import io.ktor.client.HttpClient

/**
 * 平台 HTTP 客户端工厂。
 *
 * commonMain 仅声明；jvmMain（Android/Desktop）使用 OkHttp 引擎实现。
 */
expect fun createHttpClient(): HttpClient