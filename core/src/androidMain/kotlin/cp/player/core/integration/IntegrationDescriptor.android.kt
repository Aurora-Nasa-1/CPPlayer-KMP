package cp.player.core.integration

/**
 * Android 描述符写入器：**空实现**（`docs/history/INTEGRATION_PLAN.md` §10）。
 *
 * ### 为什么不做
 * 描述符的价值在于「第三方进程能读到它」。Android 上两个选项都不成立：
 *
 * 1. **应用私有目录**（`context.filesDir`）：别的 App 根本读不到，
 *    写一个只有自己看得见的文件毫无意义。
 * 2. **外部存储**：需要存储权限，且会把**明文令牌**写到全机可读的位置 ——
 *    这是把「收紧权限」的初衷反过来了，属于净负收益。
 *
 * ### 那 Android 上怎么发现
 * 退化为「用户在设置页读地址与令牌」（见 `ExternalAccessSettingsScreen`）。
 * 将来若真要给 Android 上的第三方 App 供数，正确的形态是 **ContentProvider / AIDL
 * 适配层加在 [IntegrationService] 之上**，而不是文件发现 ——
 * 保持「一套用例，多种传输」，别另写一套逻辑。
 *
 * 注意这是**发现方式**的平台差异，**契约本身不变**：同一份 `core`、
 * 同一套 `/api/v1/...` 端点在两端都可用。
 */
private object NoopIntegrationDescriptorWriter : IntegrationDescriptorWriter {
    override fun publish(baseUrl: String, token: String) = Unit
    override fun clear() = Unit
}

actual fun createIntegrationDescriptorWriter(): IntegrationDescriptorWriter = NoopIntegrationDescriptorWriter
