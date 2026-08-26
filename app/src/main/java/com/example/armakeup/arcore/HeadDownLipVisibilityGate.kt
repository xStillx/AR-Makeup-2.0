package com.example.armakeup.arcore

/** Hides lip rendering at a sustained strong downward head pitch without threshold flicker. */
internal class HeadDownLipVisibilityGate(
    private val hidePitchRadians: Float = DEFAULT_HIDE_PITCH_RADIANS,
    private val restorePitchRadians: Float = DEFAULT_RESTORE_PITCH_RADIANS,
    private val confirmationFrames: Int = DEFAULT_CONFIRMATION_FRAMES,
) {
    private var visible = true
    private var pendingFrames = 0

    init {
        require(hidePitchRadians > restorePitchRadians)
        require(confirmationFrames > 0)
    }

    fun update(pitchRadians: Float): Boolean {
        if (!pitchRadians.isFinite()) {
            pendingFrames = 0
            return visible
        }
        val thresholdCrossed = if (visible) {
            pitchRadians >= hidePitchRadians
        } else {
            pitchRadians <= restorePitchRadians
        }
        if (!thresholdCrossed) {
            pendingFrames = 0
            return visible
        }
        pendingFrames++
        if (pendingFrames >= confirmationFrames) {
            visible = !visible
            pendingFrames = 0
        }
        return visible
    }

    fun reset() {
        visible = true
        pendingFrames = 0
    }

    private companion object {
        val DEFAULT_HIDE_PITCH_RADIANS = Math.toRadians(24.0).toFloat()
        val DEFAULT_RESTORE_PITCH_RADIANS = Math.toRadians(18.0).toFloat()
        const val DEFAULT_CONFIRMATION_FRAMES = 3
    }
}
