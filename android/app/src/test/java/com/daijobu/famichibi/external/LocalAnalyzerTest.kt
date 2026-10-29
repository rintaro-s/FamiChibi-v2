package com.daijobu.famichibi.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LINE / SMS messages are analysed on the phone (never sent to the server): same rules as server/app/analyzer.py. */
class LocalAnalyzerTest {
    @Test fun pickupRequestIsUrgentAndOffersQuickAnswers() {
        val a = LocalAnalyzer.analyze("駅に着いた！迎えに来て")
        assertTrue(a.urgent)
        assertEquals("knock", a.motion)
        assertTrue("今行く！" in a.replies)
        assertTrue(a.reasons.any { "迎えに来て" in it })
    }

    @Test fun thanksBowsAndLoveWaves() {
        assertEquals("bow", LocalAnalyzer.analyze("ありがとう🙏").motion)
        assertEquals("bothWave", LocalAnalyzer.analyze("大好きだよ❤").motion)
    }

    @Test fun plainMessageJustTalks() {
        val a = LocalAnalyzer.analyze("今日は18時に帰ります")
        assertEquals("neutral", a.emotion)
        assertEquals("talk", a.motion)
        assertFalse(a.urgent)
    }
}
