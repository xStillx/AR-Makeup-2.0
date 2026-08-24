package com.example.armakeup.tracking.face

/**
 * Combines a current global face observation with the latest local-expression observation.
 *
 * The global display landmarks are never delayed to the local model timestamp. Only the local
 * semantic shape is mapped into the current global projection.
 */
class HybridFullFaceStateComposer(
    stableAnchorIndices: IntArray,
    outerLipIndices: IntArray,
    innerLipIndices: IntArray,
    private val upperInnerLipIndex: Int = 13,
    private val lowerInnerLipIndex: Int = 14,
    private val maximumGlobalAffineResidual: Float = 0.08f,
) {
    private val stableAnchorIndices = stableAnchorIndices.copyOf()
    private val outerLipIndices = outerLipIndices.copyOf()
    private val innerLipIndices = innerLipIndices.copyOf()
    private val maximumRequiredIndex = sequenceOf(
        this.stableAnchorIndices.maxOrNull(),
        this.outerLipIndices.maxOrNull(),
        this.innerLipIndices.maxOrNull(),
        upperInnerLipIndex,
        lowerInnerLipIndex,
    ).filterNotNull().maxOrNull() ?: 0

    init {
        require(this.stableAnchorIndices.size >= MINIMUM_AFFINE_POINT_COUNT)
        require(this.outerLipIndices.isNotEmpty())
        require(this.innerLipIndices.isNotEmpty())
        require(this.outerLipIndices.size == this.innerLipIndices.size)
        require(this.stableAnchorIndices.all { it >= 0 })
        require(this.outerLipIndices.all { it >= 0 })
        require(this.innerLipIndices.all { it >= 0 })
        require(upperInnerLipIndex >= 0)
        require(lowerInnerLipIndex >= 0)
        require(maximumGlobalAffineResidual.isFinite() && maximumGlobalAffineResidual >= 0f)
    }

    fun compose(
        globalBackend: FaceTrackingBackend,
        localBackend: FaceTrackingBackend?,
        renderTimestampNs: Long,
    ): FullFaceRenderState? {
        if (globalBackend.role == FaceObservationRole.LOCAL_DEFORMATION) return null
        if (localBackend?.role == FaceObservationRole.GLOBAL_POSE) return null
        val globalObservation = globalBackend.latestObservation()
            ?.takeIf { globalBackend.matches(it) }
            ?: return null
        val localObservation = if (localBackend == null) {
            null
        } else {
            localBackend.latestObservation()?.takeIf { localBackend.matches(it) }
        }
        return compose(
            globalObservation = globalObservation,
            localObservation = localObservation,
            renderTimestampNs = renderTimestampNs,
        )
    }

    fun compose(
        globalObservation: FaceObservation,
        localObservation: FaceObservation?,
        renderTimestampNs: Long,
    ): FullFaceRenderState? {
        if (!globalObservation.quality.tracking) return null
        if (globalObservation.role == FaceObservationRole.LOCAL_DEFORMATION) return null
        val canonical = globalObservation.canonicalLandmarks ?: return null
        val display = globalObservation.displayLandmarks ?: return null
        val pose = globalObservation.pose ?: return null
        val cameraFrame = globalObservation.cameraFrame ?: return null
        if (canonical.coordinateSpace != FaceCoordinateSpace.FACE_LOCAL_METERS) return null
        if (display.coordinateSpace != FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT) return null
        if (display.pointCount <= maxOf(upperInnerLipIndex, lowerInnerLipIndex)) return null

        val lipAnchor = NormalizedFacePoint(
            x = (display.x(upperInnerLipIndex) + display.x(lowerInnerLipIndex)) * 0.5f,
            y = (display.y(upperInnerLipIndex) + display.y(lowerInnerLipIndex)) * 0.5f,
        )

        val local = localObservation?.takeIf { observation ->
            observation.quality.tracking &&
                observation.role != FaceObservationRole.GLOBAL_POSE &&
                observation.imageLandmarks != null &&
                observation.imageLandmarks.coordinateSpace ==
                FaceCoordinateSpace.NORMALIZED_IMAGE_TOP_LEFT &&
                observation.imageLandmarks.pointCount > maximumRequiredIndex &&
                display.pointCount > maximumRequiredIndex
        }
        val localLandmarks = local?.imageLandmarks
        val globalAffine = localLandmarks?.let {
            estimateAffine(it, display, stableAnchorIndices)
        }
        val fitAccepted = globalAffine != null &&
            globalAffine.normalizedRmsResidual <= maximumGlobalAffineResidual
        val regions = if (fitAccepted) {
            val acceptedGlobalAffine = checkNotNull(globalAffine)
            val acceptedLocalLandmarks = checkNotNull(localLandmarks)
            val sourceLipAnchorX = (
                acceptedLocalLandmarks.x(upperInnerLipIndex) +
                    acceptedLocalLandmarks.x(lowerInnerLipIndex)
                ) * 0.5f
            val sourceLipAnchorY = (
                acceptedLocalLandmarks.y(upperInnerLipIndex) +
                    acceptedLocalLandmarks.y(lowerInnerLipIndex)
                ) * 0.5f
            val anchoredTransform = acceptedGlobalAffine.reanchored(
                sourceX = sourceLipAnchorX,
                sourceY = sourceLipAnchorY,
                targetX = lipAnchor.x,
                targetY = lipAnchor.y,
            )
            mapOf(
                FaceRegion.LIPS_OUTER to mapRegion(
                    FaceRegion.LIPS_OUTER,
                    outerLipIndices,
                    acceptedLocalLandmarks,
                    anchoredTransform,
                ),
                FaceRegion.LIPS_INNER to mapRegion(
                    FaceRegion.LIPS_INNER,
                    innerLipIndices,
                    acceptedLocalLandmarks,
                    anchoredTransform,
                ),
            )
        } else {
            mapOf(
                FaceRegion.LIPS_OUTER to copyGlobalRegion(
                    FaceRegion.LIPS_OUTER,
                    outerLipIndices,
                    display,
                ),
                FaceRegion.LIPS_INNER to copyGlobalRegion(
                    FaceRegion.LIPS_INNER,
                    innerLipIndices,
                    display,
                ),
            )
        }
        val localAgeNs = local?.let {
            (globalObservation.sensorTimestampNs - it.sensorTimestampNs).coerceAtLeast(0L)
        }

        return FullFaceRenderState(
            renderTimestampNs = renderTimestampNs,
            cameraSensorTimestampNs = globalObservation.sensorTimestampNs,
            localObservationTimestampNs = local?.sensorTimestampNs,
            globalTopology = globalObservation.topology,
            globalPose = pose,
            cameraFrame = cameraFrame,
            canonicalLandmarks = canonical,
            displayLandmarks = display,
            lipAnchor = lipAnchor,
            regions = regions,
            attachmentQuality = FullFaceAttachmentQuality(
                localDeformationApplied = fitAccepted,
                localObservationAgeNs = localAgeNs,
                affineFitResidualNormalized = globalAffine?.normalizedRmsResidual,
            ),
            localDiagnostics = local?.diagnostics,
        )
    }

    private fun estimateAffine(
        source: FaceLandmarkSet,
        target: FaceLandmarkSet,
        anchorIndices: IntArray,
    ): FaceLocalAffineTransform? {
        val sourceAnchors = FloatArray(anchorIndices.size * XY_COMPONENT_COUNT)
        val targetAnchors = FloatArray(sourceAnchors.size)
        anchorIndices.forEachIndexed { anchorIndex, landmarkIndex ->
            val output = anchorIndex * XY_COMPONENT_COUNT
            sourceAnchors[output] = source.x(landmarkIndex)
            sourceAnchors[output + 1] = source.y(landmarkIndex)
            targetAnchors[output] = target.x(landmarkIndex)
            targetAnchors[output + 1] = target.y(landmarkIndex)
        }
        return FaceLocalAffineTransform.estimate(sourceAnchors, targetAnchors)
    }

    private fun copyGlobalRegion(
        region: FaceRegion,
        indices: IntArray,
        source: FaceLandmarkSet,
    ): FaceRegionGeometry {
        val copied = FloatArray(indices.size * XY_COMPONENT_COUNT)
        indices.forEachIndexed { pointIndex, landmarkIndex ->
            val output = pointIndex * XY_COMPONENT_COUNT
            copied[output] = source.x(landmarkIndex)
            copied[output + 1] = source.y(landmarkIndex)
        }
        return FaceRegionGeometry.takeOwnership(region, copied)
    }

    private fun mapRegion(
        region: FaceRegion,
        indices: IntArray,
        source: FaceLandmarkSet,
        transform: FaceLocalAffineTransform,
    ): FaceRegionGeometry {
        val mapped = FloatArray(indices.size * XY_COMPONENT_COUNT)
        indices.forEachIndexed { pointIndex, landmarkIndex ->
            val output = pointIndex * XY_COMPONENT_COUNT
            val sourceX = source.x(landmarkIndex)
            val sourceY = source.y(landmarkIndex)
            mapped[output] = transform.mapX(sourceX, sourceY)
            mapped[output + 1] = transform.mapY(sourceX, sourceY)
        }
        return FaceRegionGeometry.takeOwnership(region, mapped)
    }

    private fun FaceTrackingBackend.matches(observation: FaceObservation): Boolean =
        observation.backendId == backendId &&
            observation.role == role &&
            observation.topology == topology

    private companion object {
        const val MINIMUM_AFFINE_POINT_COUNT = 3
        const val XY_COMPONENT_COUNT = 2
    }
}
