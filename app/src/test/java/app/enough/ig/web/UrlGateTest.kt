package app.simpleinsta.ig.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression suite for the bug that produced
 * `net::ERR_UNKNOWN_URL_SCHEME` on a real device: a host-only check called
 * `intent://instagram.com/_n/mainfeed` "allowed" because the host was
 * instagram.com, and the WebView then failed to load the intent.
 */
class UrlGateTest {

    @Test
    fun `scheme is parsed and lowercased`() {
        assertEquals("https", UrlGate.schemeOf("https://www.instagram.com/"))
        assertEquals("https", UrlGate.schemeOf("HTTPS://www.instagram.com/"))
        assertEquals("intent", UrlGate.schemeOf("intent://instagram.com/_n/mainfeed#Intent;end"))
        assertEquals("mailto", UrlGate.schemeOf("mailto:someone@example.com"))
    }

    @Test
    fun `relative and malformed input has no scheme`() {
        assertEquals("", UrlGate.schemeOf(null))
        assertEquals("", UrlGate.schemeOf(""))
        assertEquals("", UrlGate.schemeOf("//www.instagram.com/"))
        assertEquals("", UrlGate.schemeOf("/direct/inbox/"))
        assertEquals("", UrlGate.schemeOf(":nocolon"))
    }

    @Test
    fun `only http and https count as web`() {
        assertTrue(UrlGate.isWeb("https://www.instagram.com/"))
        assertTrue(UrlGate.isWeb("http://www.instagram.com/"))
        assertFalse(UrlGate.isWeb("intent://instagram.com/_n/mainfeed#Intent;end"))
        assertFalse(UrlGate.isWeb("instagram://user?username=someone"))
        assertFalse(UrlGate.isWeb("javascript:alert(1)"))
        assertFalse(UrlGate.isWeb("data:text/html,<b>x</b>"))
        assertFalse(UrlGate.isWeb("file:///sdcard/x.html"))
    }

    @Test
    fun `instagram intent handoff is recognised despite the instagram host`() {
        val intent = "intent://instagram.com/_n/mainfeed?ig_mid=721B#Intent;package=com.instagram.android;end"
        assertTrue(UrlGate.isAppHandoff(intent))
        assertFalse(UrlGate.isWeb(intent))
    }

    @Test
    fun `app schemes are handoffs, not external apps`() {
        assertTrue(UrlGate.isAppHandoff("instagram://user?username=x"))
        assertTrue(UrlGate.isAppHandoff("fb://profile"))
        assertTrue(UrlGate.isAppHandoff("market://details?id=com.instagram.android"))
        assertFalse(UrlGate.isExternalApp("intent://instagram.com/_n/mainfeed;end"))
    }

    @Test
    fun `teardown schemes are allowed through to the webview`() {
        // Blocking these leaves an SPA stuck mid-navigation, which is what
        // "Discard does nothing" looked like.
        assertTrue(UrlGate.isTeardownScheme("about:blank"))
        assertTrue(UrlGate.isTeardownScheme("blob:https://www.instagram.com/abc"))
        assertFalse(UrlGate.isTeardownScheme("https://www.instagram.com/"))
        assertFalse(UrlGate.isTeardownScheme("javascript:void(0)"))
    }

    @Test
    fun `contact schemes belong to the system`() {
        assertTrue(UrlGate.isExternalApp("mailto:a@b.com"))
        assertTrue(UrlGate.isExternalApp("tel:+15551234"))
        assertTrue(UrlGate.isExternalApp("sms:+15551234"))
        assertFalse(UrlGate.isExternalApp("https://www.instagram.com/"))
    }
}
