package cp.player.app.platform

import cp.player.app.notify.MessageNotification

/**
 * 私信新消息的**系统通知**能力（Android 通知栏 / 桌面托盘气泡）。
 *
 * 与播放通知**刻意分开**：
 * - Android 侧播放通知由 `app-android` 的 media3 `PlaybackMediaSessionService` 承担，
 *   走的是它自建的渠道。私信通知必须用**自己的渠道**，否则用户在系统设置里关掉
 *   「播放控制」会连带把私信通知一起关掉。
 * - 桌面侧此前**没有任何**系统通知实现（全仓 `SystemTray` 出现次数为 0），这里是第一处。
 *
 * 点击通知的**回跳链路**：各平台拿到 `(providerId, peerUid)` 后调用
 * [setOnMessageNotificationClick] 注册的处理器；App 层据此 push 到对应会话。
 * ⚠️ Android 上进程可能已被杀，点击时处理器**还没注册** —— 各 actual 必须把
 * 「先到的那一次点击」缓存下来，等处理器注册时补投（见 Android 实现）。
 */

/** 本平台能不能发系统通知。桌面无托盘环境（部分 Linux / headless）为 false。 */
expect fun messageNotificationsSupported(): Boolean

/**
 * 系统层面当前是否**允许**发通知。
 *
 * Android 13+ 看 `POST_NOTIFICATIONS` 运行时权限（`MainActivity` 启动时申请过，
 * 但用户可能拒绝或事后在系统设置里关掉）；桌面看托盘是否装得上。
 * ⚠️ 与「用户在我们应用里开了某个联系人的推送」是两件事 —— 开关开着但这里为 false 时，
 * 应用必须**明确提示去授权**，否则用户会以为推送坏了。
 */
expect fun canPostMessageNotifications(): Boolean

/**
 * 主动申请通知权限（Android 13+ 弹系统框；其余平台空操作）。
 *
 * 由 `app-android` 的 `MainActivity` 注册实现 —— `app` 模块不依赖 `app-android`，
 * 沿用 `setMediaPermissionRequester` 那套回调桥。
 */
expect fun requestMessageNotificationPermission()

/**
 * 发一条私信通知。同一 [MessageNotification.key] 再次调用**覆盖**前一条，
 * 不会在通知栏里堆一排。
 *
 * 静默失败：权限缺失 / 平台不支持时直接返回，不抛异常 —— 通知是旁路能力，
 * 不该让聊天页的交互因此崩掉。
 */
expect fun postMessageNotification(notification: MessageNotification)

/**
 * 撤掉某会话的通知（用户进会话读过之后收走）。
 *
 * ⚠️ 桌面 AWT 的托盘气泡**没有**「按 id 撤销」的能力，那里是空操作（见 Desktop 实现）。
 */
expect fun cancelMessageNotification(key: String)

/**
 * 注册「通知被点击」的处理器。`null` = 注销。
 *
 * @param handler `(providerId, peerUid, title)` —— providerId 不能省：私信按音源隔离，
 *   只带 uid 在用户切过音源之后会跳到错误的人。title 是联系人昵称，
 *   带上它聊天页标题才不会先显示成「私信」再跳一下。
 */
expect fun setOnMessageNotificationClick(handler: ((providerId: String, peerUid: Long, title: String) -> Unit)?)
