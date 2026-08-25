package com.example.armakeup.tracking.face

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibratedMouthCornerResidualFilterTest {
    @Test
    fun `calibration and quiet noise keep ARCore-only output`() {
        val filter = filter()
        val neutralMismatch = residual(firstX = 0.05f, secondX = 0.04f)

        assertZero(filter.update(10L, neutralMismatch))
        assertZero(filter.update(20L, neutralMismatch))
        assertZero(filter.update(30L, neutralMismatch))
        assertZero(
            filter.update(
                40L,
                residual(firstX = 0.059f, firstY = -0.005f, secondX = 0.047f),
            ),
        )
    }

    @Test
    fun `new common corner expression responds quickly and is held for repeated timestamp`() {
        val filter = filter()
        val neutralMismatch = residual(firstX = 0.05f, secondX = 0.04f)
        filter.update(10L, neutralMismatch)
        filter.update(20L, neutralMismatch)
        filter.update(30L, neutralMismatch)

        val expression = filter.update(
            40L,
            residual(firstX = 0.11f, secondX = 0.10f),
        )
        assertEquals(0.045f, expression.firstX, EPSILON)
        assertEquals(0.045f, expression.secondX, EPSILON)
        assertEquals(0f, expression.firstY, EPSILON)
        assertEquals(0f, expression.secondY, EPSILON)

        val repeatedTimestamp = filter.update(
            40L,
            residual(firstX = -0.2f, secondX = -0.2f),
        )
        assertEquals(expression, repeatedTimestamp)
    }

    @Test
    fun `differential corner expression preserves asymmetric mouth shape`() {
        val filter = filter()
        repeat(3) { sample -> filter.update(sample.toLong() + 1L, residual()) }

        val expression = filter.update(
            10L,
            residual(firstX = -0.05f, secondX = 0.05f),
        )

        assertEquals(-0.035f, expression.firstX, EPSILON)
        assertEquals(0.035f, expression.secondX, EPSILON)
        assertEquals(0f, expression.firstY, EPSILON)
        assertEquals(0f, expression.secondY, EPSILON)
    }

    @Test
    fun `long local observation gap restarts neutral calibration`() {
        val filter = filter(resetGapNs = 100L)
        repeat(3) { sample -> filter.update(sample.toLong() * 10L + 10L, residual()) }
        val active = filter.update(
            40L,
            residual(firstX = 0.06f, secondX = 0.06f),
        )
        assertEquals(0.045f, active.firstX, EPSILON)

        assertZero(
            filter.update(
                200L,
                residual(firstX = 0.06f, secondX = 0.06f),
            ),
        )
    }

    private fun filter(resetGapNs: Long = 1_000L) = CalibratedMouthCornerResidualFilter(
        calibrationSampleCount = 3,
        activationRadius = 0.03f,
        releaseRadius = 0.015f,
        neutralAdaptation = 0.1f,
        activeResponse = 1f,
        maximumCornerMagnitude = 0.12f,
        resetGapNs = resetGapNs,
    )

    private fun residual(
        firstX: Float = 0f,
        firstY: Float = 0f,
        secondX: Float = 0f,
        secondY: Float = 0f,
    ) = MouthCornerResidualPair(
        firstX = firstX,
        firstY = firstY,
        secondX = secondX,
        secondY = secondY,
    )

    private fun assertZero(value: MouthCornerResidualPair) {
        assertEquals(0f, value.firstX, EPSILON)
        assertEquals(0f, value.firstY, EPSILON)
        assertEquals(0f, value.secondX, EPSILON)
        assertEquals(0f, value.secondY, EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-5f
    }
}
