package com.dobedub.kiosk.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainWhitelistTest {

    private val allowed = listOf("splib.dobedub.com")

    @Test
    fun `exact allowed host is permitted`() {
        assertTrue(DomainWhitelist.isAllowed("https://splib.dobedub.com/home", allowed))
    }

    @Test
    fun `subdomain of allowed host is permitted`() {
        assertTrue(DomainWhitelist.isAllowed("https://cdn.splib.dobedub.com/asset.js", allowed))
    }

    @Test
    fun `unrelated host is blocked`() {
        assertFalse(DomainWhitelist.isAllowed("https://evil.com/", allowed))
    }

    @Test
    fun `host that merely contains the allowed domain as a suffix trick is blocked`() {
        assertFalse(DomainWhitelist.isAllowed("https://notsplib.dobedub.com.evil.com/", allowed))
    }

    @Test
    fun `non-http scheme like tel or intent is blocked`() {
        assertFalse(DomainWhitelist.isAllowed("tel:01012345678", allowed))
        assertFalse(DomainWhitelist.isAllowed("intent://splib.dobedub.com/#Intent;end", allowed))
        assertFalse(DomainWhitelist.isAllowed("market://details?id=com.evil.app", allowed))
    }

    @Test
    fun `malformed url is blocked`() {
        assertFalse(DomainWhitelist.isAllowed("not a url", allowed))
    }

    @Test
    fun `www prefix on allowed domain still matches`() {
        assertTrue(DomainWhitelist.isAllowed("https://www.splib.dobedub.com/home", allowed))
    }
}
