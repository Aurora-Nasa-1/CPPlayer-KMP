package cp.player.app.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「跳转前自动保存」的去重账本（[QrAutoSaveTracker]）。
 *
 * 只测纯逻辑：它是**按图片内容**判重、且**只认保存成功之后**记账这两条，
 * 编译和界面都量不到 —— 写错的表现是「相册里堆十几张一样的二维码」或
 * 「一次失败之后再也不存了」，两种都要到真机上点几次才发现。
 */
class QrAutoSaveTrackerTest {

    private val qrA = "data:image/png;base64,AAAA"
    private val qrB = "data:image/png;base64,BBBB"

    @Test
    fun `a brand new code needs saving once and only once`() {
        val tracker = QrAutoSaveTracker()

        assertTrue(tracker.needsSave(qrA), "从没存过的图当然要存")
        tracker.markSaved(qrA)
        assertFalse(tracker.needsSave(qrA), "同一张图（同一段 base64）不该重复写进相册")
    }

    @Test
    fun `a refreshed code needs saving again`() {
        val tracker = QrAutoSaveTracker()
        tracker.markSaved(qrA)

        assertTrue(tracker.needsSave(qrB), "刷新后是新二维码，旧的那张不能当作已存")
    }

    @Test
    fun `a failed save is not recorded so the next jump retries`() {
        // 调用方只在 saveQrCodeToGallery 返回 true 时才 markSaved；失败路径根本不记账。
        val tracker = QrAutoSaveTracker()

        assertTrue(tracker.needsSave(qrA))
        assertTrue(tracker.needsSave(qrA), "没记账 ⇒ 还是「没存过」⇒ 下次跳转继续重试")
    }

    @Test
    fun `blank or missing images are never saved`() {
        val tracker = QrAutoSaveTracker()

        assertFalse(tracker.needsSave(null), "没有图（key 拿到了但 qrimg 缺失）就没有可存的")
        assertFalse(tracker.needsSave(""), "空串同理")
        assertFalse(tracker.needsSave("   "), "纯空白同理")
        // 空值也不该污染账本：之后真拿到图仍然要存。
        tracker.markSaved(null)
        assertTrue(tracker.needsSave(qrA))
    }

    @Test
    fun `reset makes the current code saveable again`() {
        val tracker = QrAutoSaveTracker()
        tracker.markSaved(qrA)

        tracker.reset()
        assertTrue(tracker.needsSave(qrA), "换音源 / 清空现场后账本作废，新现场要重新存")
    }
}
