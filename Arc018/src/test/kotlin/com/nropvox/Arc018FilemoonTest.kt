package com.nropvox

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Arc018FilemoonTest {
    private fun fixture(name: String) = javaClass.getResource("/arc018/$name")!!.readText()

    @Test fun matchesShippedJavascriptHashAndAcceptedProof() {
        val words = Arc018Filemoon.hash("f77e6c8256fe3eeeda3ea667b44f04d4:32949")
        assertArrayEquals(longArrayOf(19679, 3757650858, 305285574, 3435280515, 1663271601, 3607810280, 1381765476, 4002510910), words.map { it.toLong() and 0xffffffffL }.toLongArray())
        assertEquals(17, Arc018Filemoon.leadingZeroBits(words))
    }

    @Test fun decryptsCapturedPlaybackWithVersionSelectedKeyParts() {
        assertEquals(fixture("filemoon-playback-decoded.json"), Arc018Filemoon.decrypt(fixture("filemoon-playback-unlocked.json")))
    }

    @Test fun solverFindsServerAcceptedSolution() = runBlocking {
        assertEquals("32949", Arc018Filemoon.solve("f77e6c8256fe3eeeda3ea667b44f04d4", 16))
    }

    @Test fun solverRejectsUnboundedChallenges() = runBlocking {
        assertNull(Arc018Filemoon.solve("x", 256))
        assertNull(Arc018Filemoon.solve("", 16))
        assertNull(Arc018Filemoon.solve("x", 16, 0))
    }

    @Test fun solverHonorsCancellation() = runBlocking {
        val worker = launch { Arc018Filemoon.solve("cancelled", 20) }
        worker.cancel()
        worker.join()
        assertTrue(worker.isCancelled)
    }
}
