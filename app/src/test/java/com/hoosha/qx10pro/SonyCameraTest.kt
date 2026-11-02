package com.hoosha.qx10pro

import org.junit.Assert.assertEquals
import org.junit.Test

class SonyCameraTest {

    @Test
    fun testExposureCompensationFormatting() {
        val ecZero = ExposureCompensationInfo(current = 0, max = 6, min = -6, stepIndex = 1)
        assertEquals("±0.0 EV", ecZero.toEvString())

        val ecPlusOne = ExposureCompensationInfo(current = 3, max = 6, min = -6, stepIndex = 1)
        assertEquals("+1.0 EV", ecPlusOne.toEvString())

        val ecMinusTwo = ExposureCompensationInfo(current = -6, max = 6, min = -6, stepIndex = 1)
        assertEquals("-2.0 EV", ecMinusTwo.toEvString())

        val ecHalfStep = ExposureCompensationInfo(current = 2, max = 4, min = -4, stepIndex = 2)
        assertEquals("+1.0 EV", ecHalfStep.toEvString())
    }
}
