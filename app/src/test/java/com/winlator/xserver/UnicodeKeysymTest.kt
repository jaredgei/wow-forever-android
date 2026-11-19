package com.winlator.xserver

import org.junit.Assert.assertEquals
import org.junit.Test

class UnicodeKeysymTest {
    @Test
    fun latin1CharactersKeepTheirCodePoint() {
        assertEquals('a'.code, Keyboard.unicodeKeysym('a'.code))
        assertEquals(0xe9, Keyboard.unicodeKeysym(0xe9)) // é
    }

    @Test
    fun otherCharactersUseUnicodeKeysyms() {
        assertEquals(0x01000444, Keyboard.unicodeKeysym('ф'.code))
        assertEquals(0x0100041f, Keyboard.unicodeKeysym('П'.code))
        assertEquals(0x0101f600, Keyboard.unicodeKeysym(0x1f600))
    }
}
