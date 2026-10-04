package cp.player.core.sync

import kotlinx.serialization.Serializable

/**
 * 本机在设备间的身份。
 *
 * ### 为什么身份里**没有**账号 uid
 * 广播是**未认证**的：任何同网段的人都能收到信标。账号 uid 一旦进信标，
 * 就等于把「谁在用这台机器」公开出去，而且它还是跨设备关联用户的稳定标识。
 * 因此身份只回答「这是哪台机器」，不回答「这是谁」—— 后者属于配对之后的私有通道。
 *
 * @property deviceId 稳定且唯一，首次启动生成后**永不改**（换名字、升级版本都不改）。
 *   设备表按它去重、未来的同步游标也按它存 —— 改一次等于把历史关联全部切断。
 * @property name 给人看的名字（默认取主机名），用户可改。
 * @property platform `windows` / `macos` / `linux` / `android`。用于在设备列表里
 *   给出正确的图标与「谁推给谁」的方向提示，不参与任何逻辑判定。
 * @property appVersion 仅供排查（「对方是旧版本」这类问题一眼可见）。
 */
@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val name: String,
    val platform: String,
    val appVersion: String = "",
) {
    /** 名字为空时退回平台名 —— 列表里宁可显示「windows」也不要显示空白行。 */
    val displayName: String get() = name.ifBlank { platform }
}

/**
 * 本机设备 id。**必须持久化**：`deviceId` 每启动一次就变的话，
 * 对端会把同一台机器当成不断出现的新设备，设备表会被刷爆。
 */
expect fun newDeviceId(): String

/** 设备默认名（取主机名；拿不到时给一个可辨认的兜底，不要空串）。 */
expect fun defaultDeviceName(): String

/** 当前平台标识（`windows` / `macos` / `linux` / `android`）。 */
expect fun currentPlatformLabel(): String
