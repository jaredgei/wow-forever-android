package app.gamenative.wow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FrameGenerationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        FrameGeneration.remove(context)
    }

    /** A minimal 64-bit PE file whose resource section holds RCDATA entries with [shaders] as their ids. */
    private fun dll(shaders: List<Int> = listOf(255, 256) + (280..302), size: Int = 300_000): ByteArray {
        val b = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte()).putInt(0x3C, 0x80)
        b.putInt(0x80, 0x00004550).putShort(0x86, 1).putShort(0x94, 240)
        val optional = 0x98
        b.putShort(optional, 0x20B).putInt(optional + 112 + 16, 0x1000)
        val section = optional + 240
        b.putInt(section + 8, 0x10000).putInt(section + 12, 0x1000).putInt(section + 16, 0x10000).putInt(section + 20, 0x400)
        val base = 0x400
        val flag = 0x80000000.toInt()
        b.putShort(base + 14, 1).putInt(base + 16, 10).putInt(base + 20, flag or 24)
        val types = base + 24
        b.putShort(types + 14, shaders.size.toShort())
        val languages = 24 + 16 + shaders.size * 8
        shaders.forEachIndexed { i, id ->
            val language = languages + i * 24
            b.putInt(types + 16 + i * 8, id).putInt(types + 20 + i * 8, flag or language)
            b.putShort(base + language + 14, 1).putInt(base + language + 16, 0x409).putInt(base + language + 20, 0)
        }
        return b.array()
    }

    @Test
    fun importsAWindowsDllAndArmsFrameGeneration() {
        assertFalse(FrameGeneration.isArmed(context))
        assertEquals(FrameGeneration.ImportResult.OK, FrameGeneration.import(context, dll().inputStream()))
        assertTrue(FrameGeneration.dllFile(context).isFile)
        assertTrue(FrameGeneration.isArmed(context))
        FrameGeneration.setEnabled(context, false)
        assertFalse(FrameGeneration.isArmed(context))
    }

    @Test
    fun rejectsFilesThatAreNotTheDll() {
        assertEquals(FrameGeneration.ImportResult.NOT_A_DLL, FrameGeneration.import(context, "hello".toByteArray().inputStream()))
        assertEquals(FrameGeneration.ImportResult.NOT_A_DLL, FrameGeneration.import(context, dll(size = 2_000).inputStream()))
        assertFalse(FrameGeneration.dllFile(context).exists())
    }

    @Test
    fun turnsDownADllWithoutTheShaders() {
        val partial = dll(shaders = listOf(255) + (280..302))
        assertEquals(FrameGeneration.ImportResult.NO_SHADERS, FrameGeneration.import(context, partial.inputStream()))
        assertEquals(FrameGeneration.ImportResult.NO_SHADERS, FrameGeneration.import(context, dll(shaders = emptyList()).inputStream()))
        assertFalse(FrameGeneration.dllFile(context).exists())
    }

    @Test
    fun aGoodDllHasItsShaders() {
        FrameGeneration.import(context, dll().inputStream())
        assertTrue(FrameGeneration.hasShaders(context))
        // A DLL saved before this check existed is still looked at when the game starts.
        FrameGeneration.dllFile(context).writeBytes(dll(shaders = listOf(255, 256)))
        assertFalse(FrameGeneration.hasShaders(context))
    }

    @Test
    fun removeForgetsTheDll() {
        FrameGeneration.import(context, dll().inputStream())
        FrameGeneration.remove(context)
        assertFalse(FrameGeneration.dllFile(context).exists())
        assertFalse(FrameGeneration.isArmed(context))
    }
}
