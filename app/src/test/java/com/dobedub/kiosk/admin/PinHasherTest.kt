package com.dobedub.kiosk.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun `same pin and salt produce identical hash`() {
        val salt = PinHasher.generateSalt()
        val hash1 = PinHasher.hash("1234", salt)
        val hash2 = PinHasher.hash("1234", salt)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `different salts produce different hashes for the same pin`() {
        val saltA = PinHasher.generateSalt()
        val saltB = PinHasher.generateSalt()
        assertNotEquals(saltA, saltB)
        assertNotEquals(PinHasher.hash("1234", saltA), PinHasher.hash("1234", saltB))
    }

    @Test
    fun `verify succeeds for the correct pin`() {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash("135790", salt)
        assertTrue(PinHasher.verify("135790", salt, hash))
    }

    @Test
    fun `verify fails for an incorrect pin`() {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash("135790", salt)
        assertFalse(PinHasher.verify("000000", salt, hash))
    }

    @Test
    fun `verify fails when salt does not match`() {
        val saltA = PinHasher.generateSalt()
        val saltB = PinHasher.generateSalt()
        val hash = PinHasher.hash("2468", saltA)
        assertFalse(PinHasher.verify("2468", saltB, hash))
    }
}
