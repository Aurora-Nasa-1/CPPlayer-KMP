package cp.player.core.lyricpush

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], manifest = Config.NONE)
class XiaomiSuperIslandLyricServiceTest {
    private lateinit var app: Application
    private var controller: ServiceController<XiaomiSuperIslandLyricService>? = null

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        setStaticField("pendingNotification", null)
        setStaticField("running", false)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        setStaticField("pendingNotification", null)
        setStaticField("running", false)
    }

    @Test
    fun creationPromotesBeforeAnyCommandEvenWithoutPendingPayload() {
        val service = createService()
        val shadow = shadowOf(service)
        assertEquals(XiaomiSuperIslandLyricService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertNotNull(shadow.lastForegroundNotification)
        assertFalse(shadow.isStoppedBySelf)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        }
    }

    @Test
    fun existingPayloadIsUsedForInitialForegroundPromotion() {
        val notification = notification()
        XiaomiSuperIslandLyricService.publish(app, notification)
        val service = createService()
        assertEquals(notification, shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun recreatedPublishWithoutPayloadStopsOnlyAfterForegroundPromotion() {
        val service = createService()
        val result = service.onStartCommand(publishIntent(), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun nullOrStopCommandCannotBypassForegroundPromotion() {
        val service = createService()
        service.onStartCommand(null, 0, 1)
        service.onStartCommand(Intent(app, service.javaClass).setAction(XiaomiSuperIslandLyricService.ACTION_STOP), 0, 2)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun stopDoesNotStartAnInactiveService() {
        XiaomiSuperIslandLyricService.stop(app)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(XiaomiSuperIslandLyricService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
    }

    @Test
    fun publishDoesNotMarkAnEnqueuedServiceAsAlreadyRunning() {
        XiaomiSuperIslandLyricService.publish(app, notification())
        val running = XiaomiSuperIslandLyricService::class.java.getDeclaredField("running").apply {
            isAccessible = true
        }.getBoolean(null)
        assertFalse(running)
        assertEquals(XiaomiSuperIslandLyricService.ACTION_PUBLISH, shadowOf(app).nextStartedService.action)
    }

    @Test
    fun publishUpdatesForegroundNotificationAndDestroyRemovesIt() {
        val service = createService()
        val notification = notification()
        XiaomiSuperIslandLyricService.publish(app, notification)
        service.onStartCommand(publishIntent(), 0, 1)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(notification, shadowOf(manager).getNotification(XiaomiSuperIslandLyricService.NOTIFICATION_ID))
        controller!!.destroy()
        controller = null
        assertNull(shadowOf(manager).getNotification(XiaomiSuperIslandLyricService.NOTIFICATION_ID))
    }

    private fun createService(): XiaomiSuperIslandLyricService {
        val created = Robolectric.buildService(XiaomiSuperIslandLyricService::class.java).create()
        controller = created
        return created.get()
    }

    private fun publishIntent() = Intent(app, XiaomiSuperIslandLyricService::class.java)
        .setAction(XiaomiSuperIslandLyricService.ACTION_PUBLISH)

    private fun notification(): Notification {
        XiaomiSuperIslandLyricService.ensureChannel(app)
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(app, XiaomiSuperIslandLyricService.CHANNEL_ID)
        } else {
            Notification.Builder(app)
        }
        return builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("lyric").build()
    }

    private fun setStaticField(name: String, value: Any?) {
        XiaomiSuperIslandLyricService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(null, value)
    }
}
