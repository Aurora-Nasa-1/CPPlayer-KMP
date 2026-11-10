package cp.player.core.monitor

import cp.player.core.BackendResult
import cp.player.core.music.UnifiedMusicSourceImpl
import cp.player.core.provider.ProviderCookieStorage
import cp.player.core.provider.ProviderManager
import cp.player.core.provider.ProviderType
import cp.player.core.provider.SdkBackendProvider
import cp.player.core.provider.SdkEmptyLocal
import cp.player.core.provider.SdkMemorySettings
import cp.player.sdk.Capabilities
import cp.player.sdk.Capability
import cp.player.sdk.IdRequest
import cp.player.sdk.MusicProvider
import cp.player.sdk.Track
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 取数边界必须把每次调用报给 [HealthMonitor]。
 *
 * 这条链路此前挂在 `MusicApiServiceImpl.callApi` 上，JSON 层删除后中断过一段时间，
 * 表现是诊断页「操作统计 / 调用日志」恒空。这里钉住恢复后的行为：
 * 操作名、音源 id、结果等级三者都要对得上。
 */
class BoundaryHealthReportingTest {

    @Test
    fun `every source call is reported with its operation name`() = runBlocking {
        HealthMonitor.clearRecords()
        val settings = SdkMemorySettings()
        val manager = ProviderManager(settings, ProviderCookieStorage(settings))
        val descriptor = SdkBackendProvider("monitored", "Monitored", "1", ProviderType.SCRIPT, {
            object : MusicProvider {
                override suspend fun capabilities() = Capabilities(listOf(Capability.GET))
                override suspend fun get(request: IdRequest) = Track(request.id, "Track", durationMs = 9_500)
            }
        })
        manager.switchProvider(descriptor)
        val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { manager.sdkSessions.current() }, { descriptor })
        try {
            assertIs<BackendResult.Success<*>>(source.getTrackDetail("monitored://song/001"))
            // 能力缺失不是故障：必须落成 WARNING（Unsupported），否则前端会渲染成「出错了请重试」。
            assertIs<BackendResult.Unsupported>(source.getComments("monitored://song/001", "music", 20, 0, 1))

            val records = HealthMonitor.recordsFlow.value
            val detail = records.single { it.operation == "getTrackDetail" }
            assertEquals("monitored", detail.providerId)
            assertEquals(HealthMonitor.HealthLevel.OK, detail.level)
            assertEquals(true, detail.success)

            val comments = records.single { it.operation == "getComments" }
            assertEquals(HealthMonitor.HealthLevel.WARNING, comments.level)
            assertEquals(false, comments.success)

            assertEquals(1, HealthMonitor.getStats("monitored").successCount)
            assertEquals(1, HealthMonitor.getStatsByOperation().getValue("getComments").warningCount)
            assertTrue(HealthMonitor.getRecentRecords(operation = "getComments").isNotEmpty())
        } finally {
            manager.sdkSessions.close()
            descriptor.close()
        }
    }
}
