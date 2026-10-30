package cp.player.app.auth

/**
 * 「跳转到目标 App 之前先把当前二维码存进相册」的**去重账本**。
 *
 * ### 为什么需要一个账本
 *
 * 登录页的按钮是「打开 网易云音乐」这类跳转，用户可能点不止一次（跳过去没扫成、
 * 回来再点一次；或者先点「保存二维码」再点跳转）。每次跳转都往相册写一张**同一张图**，
 * 相册里就会堆出十几张一样的二维码 —— 这不是「保险」，是噪音。
 *
 * 判据按**图片内容**（服务端返回的那段 base64）比较，而不是按文件名或音源：
 * 同一张二维码在一次登录里只写一次；换了新码（刷新二维码 / 过期后自动重取）自然不同，
 * 新的一轮又会存一张。
 *
 * ### 边界
 *
 * - **只认「真的写成功了」**：调用方在 `saveQrCodeToGallery` 返回 true 时才 [markSaved]。
 *   写失败（权限 / 系统拒绝）不记账，下一次跳转会再试一次 —— 这正是那个 Boolean
 *   返回值存在的理由。
 * - 账本**只在内存里**：进程被系统回收后从相册恢复不了「存过哪些」，重新打开应用第一次
 *   跳转会再存一张。可以接受 —— 那种场景下相册里那张图本来就还在，多一张不算误伤。
 * - 线程模型与 `AccountScreenModel` 一致（全部在 ScreenModel 的主线程作用域里读写），
 *   因此不加同步。
 */
class QrAutoSaveTracker {

    /** 已经落过相的那张图（base64 原文）。`null` = 这一轮还没存过。 */
    private var savedImage: String? = null

    /** 这张图要不要（再）存一次。只看内容，空图一律不存。 */
    fun needsSave(imageBase64: String?): Boolean =
        !imageBase64.isNullOrBlank() && imageBase64 != savedImage

    /** 记下「这张图已经在相册里了」（**保存成功之后**才调）。 */
    fun markSaved(imageBase64: String?) {
        if (!imageBase64.isNullOrBlank()) savedImage = imageBase64
    }

    /** 换音源 / 清空登录现场时把账本一起清掉，别让跨音源的状态残留。 */
    fun reset() {
        savedImage = null
    }
}
