package cp.player.app.presentation

import cp.player.core.BackendState
import cp.player.sdk.ExtendedCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialNavigationTest {
    @Test
    fun `partial capabilities do not open unrelated screens`() {
        val operations = listOf(
            ExtendedCapability.GETUSEREVENTS,
            ExtendedCapability.GETUNREADCOUNT,
            ExtendedCapability.GETMESSAGEHISTORY,
        )
        assertEquals(SocialAvailability.Available, SocialFeature.UserFeed.fromOperations(operations))
        assertEquals(SocialAvailability.Unsupported, SocialFeature.Feed.fromOperations(operations))
        assertEquals(SocialAvailability.Unsupported, SocialFeature.Inbox.fromOperations(operations))
        assertEquals(
            SocialAvailability.Available,
            SocialFeature.Inbox.fromOperations(listOf(ExtendedCapability.GETRECENTCONTACTS)),
        )
    }

    @Test
    fun `search replacement resumes when the saved preference becomes applicable`() {
        val preference = SocialNavigation(
            feed = SocialFeature.Feed.availability(null, BackendState.Initializing),
            inbox = SocialAvailability.Unsupported,
            replaceSearch = true,
            hideDynamic = false,
            hideMessages = false,
        )
        assertEquals(SocialAvailability.Loading, preference.feed)
        assertEquals(SocialAvailability.NoProvider, SocialFeature.Feed.availability(null, BackendState.NoProvider))
        assertFalse(preference.dynamicAsTab)
        val ready = preference.copy(feed = SocialAvailability.Available, inbox = SocialAvailability.Available)
        assertTrue(ready.dynamicAsTab)
        assertFalse(ready.copy(hideDynamic = true).dynamicAsTab)
        assertFalse(ready.copy(feed = SocialAvailability.Unsupported).dynamicAsTab)
        assertTrue(ready.showMessages)
        assertFalse(ready.copy(hideMessages = true).showMessages)
    }
}
