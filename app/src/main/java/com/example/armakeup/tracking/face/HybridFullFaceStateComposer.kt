package com.example.armakeup.tracking.face

enum class MouthLocalGeometryPolicy {
    FULL_CONTOUR,
    CORNER_RESIDUAL,
    CALIBRATED_CORNER_RESIDUAL,
}

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
    leftEyeIndices: IntArray = intArrayOf(),
    rightEyeIndices: IntArray = intArrayOf(),
    private val mouthAperture: FaceApertureTopology? = null,
    private val leftEyeAperture: FaceApertureTopology? = null,
    private val rightEyeAperture: FaceApertureTopology? = null,
    private val upperInnerLipIndex: Int = 13,
    private val lowerInnerLipIndex: Int = 14,
    private val maximumGlobalAffineResidual: Float = 0.08f,
    private val fullLocalAffineResidual: Float = maximumGlobalAffineResidual,
    private val maximumLocalObservationAgeNs: Long = Long.MAX_VALUE,
    private val localObservationAgeFadeOutNs: Long = 0L,
    private val mouthLocalGeometryPolicy: MouthLocalGeometryPolicy =
        MouthLocalGeometryPolicy.FULL_CONTOUR,
    private val perspectivePolicy: FaceLocalGeometryPerspectivePolicy =
        FaceLocalGeometryPerspectivePolicy.UNRESTRICTED,
) {
    private val stableAnchorIndices = stableAnchorIndices.copyOf()
    private val outerLipIndices = outerLipIndices.copyOf()
    private val innerLipIndices = innerLipIndices.copyOf()
    private val leftEyeIndices = leftEyeIndices.copyOf()
    private val rightEyeIndices = rightEyeIndices.copyOf()
    private val calibratedMouthCornerResidualFilter = CalibratedMouthCornerResidualFilter()
    private val firstMouthCornerPosition = mouthAperture?.let { aperture ->
        this.outerLipIndices.indexOf(aperture.firstCornerIndex)
    } ?: MISSING_INDEX
    private val secondMouthCornerPosition = mouthAperture?.let { aperture ->
        this.outerLipIndices.indexOf(aperture.secondCornerIndex)
    } ?: MISSING_INDEX
    private val maximumRequiredIndex = sequenceOf(
        this.stableAnchorIndices.maxOrNull(),
        this.outerLipIndices.maxOrNull(),
        this.innerLipIndices.maxOrNull(),
        this.leftEyeIndices.maxOrNull(),
        this.rightEyeIndices.maxOrNull(),
        mouthAperture?.maximumIndex,
        leftEyeAperture?.maximumIndex,
        rightEyeAperture?.maximumIndex,
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
        require(this.leftEyeIndices.all { it >= 0 })
        require(this.rightEyeIndices.all { it >= 0 })
        require(upperInnerLipIndex >= 0)
        require(lowerInnerLipIndex >= 0)
        require(maximumGlobalAffineResidual.isFinite() && maximumGlobalAffineResidual >= 0f)
        require(fullLocalAffineResidual.isFinite() &&
            fullLocalAffineResidual in 0f..maximumGlobalAffineResidual)
        require(maximumLocalObservationAgeNs >= 0L)
        require(localObservationAgeFadeOutNs >= 0L)
        if (mouthLocalGeometryPolicy != MouthLocalGeometryPolicy.FULL_CONTOUR) {
            require(mouthAperture != null)
            require(firstMouthCornerPosition != MISSING_INDEX)
            require(secondMouthCornerPosition != MISSING_INDEX)
        }
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
            val ageNs = globalObservation.sensorTimestampNs - observation.sensorTimestampNs
            observation.quality.tracking &&
                ageNs >= 0L &&
                ageNs <= saturatedAdd(
                    maximumLocalObservationAgeNs,
                    localObservationAgeFadeOutNs,
                ) &&
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
        val regions = mutableMapOf<FaceRegion, FaceRegionGeometry>()
        val residualLocalWeight = globalAffine?.let { affine ->
            localWeightForResidual(affine.normalizedRmsResidual)
        } ?: 0f
        val localGeometryBaseWeight =
            perspectivePolicy.localWeight(globalObservation) * residualLocalWeight
        val localGeometryWeight = localGeometryBaseWeight *
            localObservationAgeWeight(globalObservation, local)
        val mouthLocalGeometryWeight = if (
            mouthLocalGeometryPolicy == MouthLocalGeometryPolicy.CALIBRATED_CORNER_RESIDUAL
        ) {
            localGeometryBaseWeight
        } else {
            localGeometryWeight
        }
        val mouthLocalWeight = if (
            fitAccepted && localFeatureTracks(local, local?.features?.mouth)
        ) {
            mouthLocalGeometryWeight
        } else {
            0f
        }
        val mouthUsesLocal = mouthLocalWeight > MINIMUM_LOCAL_GEOMETRY_WEIGHT
        val leftEyeUsesLocal = fitAccepted &&
            leftEyeIndices.isNotEmpty() &&
            leftEyeAperture != null &&
            localFeatureTracks(local, local?.features?.leftEye) &&
            localGeometryWeight > MINIMUM_LOCAL_GEOMETRY_WEIGHT
        val rightEyeUsesLocal = fitAccepted &&
            rightEyeIndices.isNotEmpty() &&
            rightEyeAperture != null &&
            localFeatureTracks(local, local?.features?.rightEye) &&
            localGeometryWeight > MINIMUM_LOCAL_GEOMETRY_WEIGHT

        val acceptedGlobalAffine = globalAffine?.takeIf { fitAccepted }
        val acceptedLocalLandmarks = localLandmarks?.takeIf { fitAccepted }
        val mouthTransform = if (mouthUsesLocal) {
            // The stable-anchor affine already moves the stale MediaPipe observation into the
            // current ARCore face projection. Re-pinning its mouth midpoint to the ARCore mesh
            // removes a real expression component: when both lips are pulled sideways, their
            // midpoint moves relative to the eyes/nose/cheeks even though the head pose does not.
            // Keep that face-local translation along with the contour deformation. Global head
            // and phone motion still comes exclusively from the current ARCore observation.
            checkNotNull(acceptedGlobalAffine)
        } else {
            null
        }
        if (mouthTransform != null) {
            regions[FaceRegion.LIPS_OUTER] = mapMouthRegion(
                FaceRegion.LIPS_OUTER,
                outerLipIndices,
                checkNotNull(acceptedLocalLandmarks),
                mouthTransform,
                display,
                mouthLocalWeight,
                checkNotNull(local).sensorTimestampNs,
            )
            regions[FaceRegion.LIPS_INNER] = mapMouthRegion(
                FaceRegion.LIPS_INNER,
                innerLipIndices,
                checkNotNull(acceptedLocalLandmarks),
                mouthTransform,
                display,
                mouthLocalWeight,
                checkNotNull(local).sensorTimestampNs,
            )
        } else {
            regions[FaceRegion.LIPS_OUTER] = copyGlobalRegion(
                FaceRegion.LIPS_OUTER,
                outerLipIndices,
                display,
            )
            regions[FaceRegion.LIPS_INNER] = copyGlobalRegion(
                FaceRegion.LIPS_INNER,
                innerLipIndices,
                display,
            )
        }

        val leftEyeTransform = mapOptionalFeatureRegion(
            region = FaceRegion.LEFT_EYE,
            indices = leftEyeIndices,
            aperture = leftEyeAperture,
            useLocal = leftEyeUsesLocal,
            local = acceptedLocalLandmarks,
            display = display,
            globalAffine = acceptedGlobalAffine,
            localWeight = localGeometryWeight,
            output = regions,
        )
        val rightEyeTransform = mapOptionalFeatureRegion(
            region = FaceRegion.RIGHT_EYE,
            indices = rightEyeIndices,
            aperture = rightEyeAperture,
            useLocal = rightEyeUsesLocal,
            local = acceptedLocalLandmarks,
            display = display,
            globalAffine = acceptedGlobalAffine,
            localWeight = localGeometryWeight,
            output = regions,
        )
        val localAgeNs = local?.let {
            (globalObservation.sensorTimestampNs - it.sensorTimestampNs).coerceAtLeast(0L)
        }

        val featureStates = FullFaceFeatureStates(
            mouth = featureRenderState(
                useLocal = mouthUsesLocal,
                geometryAvailable = true,
                localObservation = local,
                globalObservation = globalObservation,
                localFeature = local?.features?.mouth,
                globalFeature = globalObservation.features.mouth,
                apertureRatio = apertureRatio(
                    source = if (mouthUsesLocal) checkNotNull(acceptedLocalLandmarks) else display,
                    topology = mouthAperture,
                    transform = mouthTransform,
                ),
            ),
            leftEye = featureRenderState(
                useLocal = leftEyeUsesLocal,
                geometryAvailable = leftEyeIndices.isNotEmpty(),
                localObservation = local,
                globalObservation = globalObservation,
                localFeature = local?.features?.leftEye,
                globalFeature = globalObservation.features.leftEye,
                apertureRatio = apertureRatio(
                    source = if (leftEyeUsesLocal) checkNotNull(acceptedLocalLandmarks) else display,
                    topology = leftEyeAperture,
                    transform = leftEyeTransform,
                ),
            ),
            rightEye = featureRenderState(
                useLocal = rightEyeUsesLocal,
                geometryAvailable = rightEyeIndices.isNotEmpty(),
                localObservation = local,
                globalObservation = globalObservation,
                localFeature = local?.features?.rightEye,
                globalFeature = globalObservation.features.rightEye,
                apertureRatio = apertureRatio(
                    source = if (rightEyeUsesLocal) checkNotNull(acceptedLocalLandmarks) else display,
                    topology = rightEyeAperture,
                    transform = rightEyeTransform,
                ),
            ),
        )

        return FullFaceRenderState(
            renderTimestampNs = renderTimestampNs,
            cameraSensorTimestampNs = globalObservation.sensorTimestampNs,
            localObservationTimestampNs = local?.sensorTimestampNs,
            globalTopology = globalObservation.topology,
            globalPose = pose,
            cameraFrame = cameraFrame,
            canonicalLandmarks = canonical,
            displayLandmarks = display,
            surfaceTopology = globalObservation.surfaceTopology,
            surfaceTextureCoordinates = globalObservation.surfaceTextureCoordinates,
            lipAnchor = lipAnchor,
            features = featureStates,
            regions = regions,
            attachmentQuality = FullFaceAttachmentQuality(
                localDeformationApplied = mouthUsesLocal || leftEyeUsesLocal || rightEyeUsesLocal,
                localDeformationWeight = if (
                    mouthUsesLocal || leftEyeUsesLocal || rightEyeUsesLocal
                ) localGeometryWeight else 0f,
                localObservationAgeNs = localAgeNs,
                affineFitResidualNormalized = globalAffine?.normalizedRmsResidual,
            ),
            localDiagnostics = local?.diagnostics,
        )
    }

    private fun localFeatureTracks(
        observation: FaceObservation?,
        feature: FaceFeatureObservationState?,
    ): Boolean = observation != null && (feature?.tracking ?: observation.quality.tracking)

    private fun localObservationAgeWeight(
        globalObservation: FaceObservation,
        localObservation: FaceObservation?,
    ): Float {
        val local = localObservation ?: return 0f
        val ageNs = (globalObservation.sensorTimestampNs - local.sensorTimestampNs)
            .coerceAtLeast(0L)
        if (ageNs <= maximumLocalObservationAgeNs) return 1f
        if (localObservationAgeFadeOutNs <= 0L) return 0f
        return (1.0 -
            (ageNs - maximumLocalObservationAgeNs).toDouble() /
                localObservationAgeFadeOutNs.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    private fun mapOptionalFeatureRegion(
        region: FaceRegion,
        indices: IntArray,
        aperture: FaceApertureTopology?,
        useLocal: Boolean,
        local: FaceLandmarkSet?,
        display: FaceLandmarkSet,
        globalAffine: FaceLocalAffineTransform?,
        localWeight: Float,
        output: MutableMap<FaceRegion, FaceRegionGeometry>,
    ): FaceLocalAffineTransform? {
        if (indices.isEmpty()) return null
        if (!useLocal || local == null || globalAffine == null || aperture == null) {
            output[region] = copyGlobalRegion(region, indices, display)
            return null
        }
        val transform = globalAffine.reanchoredAtMidpoint(
            source = local,
            target = display,
            firstIndex = aperture.firstCornerIndex,
            secondIndex = aperture.secondCornerIndex,
        )
        output[region] = mapRegion(
            region = region,
            indices = indices,
            source = local,
            transform = transform,
            globalDisplay = display,
            localWeight = localWeight,
        )
        return transform
    }

    private fun FaceLocalAffineTransform.reanchoredAtMidpoint(
        source: FaceLandmarkSet,
        target: FaceLandmarkSet,
        firstIndex: Int,
        secondIndex: Int,
    ): FaceLocalAffineTransform = reanchored(
        sourceX = (source.x(firstIndex) + source.x(secondIndex)) * 0.5f,
        sourceY = (source.y(firstIndex) + source.y(secondIndex)) * 0.5f,
        targetX = (target.x(firstIndex) + target.x(secondIndex)) * 0.5f,
        targetY = (target.y(firstIndex) + target.y(secondIndex)) * 0.5f,
    )

    private fun featureRenderState(
        useLocal: Boolean,
        geometryAvailable: Boolean,
        localObservation: FaceObservation?,
        globalObservation: FaceObservation,
        localFeature: FaceFeatureObservationState?,
        globalFeature: FaceFeatureObservationState?,
        apertureRatio: Float?,
    ): FaceFeatureRenderState {
        if (!geometryAvailable) {
            return FaceFeatureRenderState(
                geometrySource = FaceFeatureGeometrySource.UNAVAILABLE,
                observationTimestampNs = null,
                tracking = false,
            )
        }
        val observation = if (useLocal) checkNotNull(localObservation) else globalObservation
        val feature = if (useLocal) localFeature else globalFeature
        return FaceFeatureRenderState(
            geometrySource = if (useLocal) {
                FaceFeatureGeometrySource.LOCAL_DEFORMATION
            } else {
                FaceFeatureGeometrySource.GLOBAL_FALLBACK
            },
            observationTimestampNs = observation.sensorTimestampNs,
            tracking = feature?.tracking ?: observation.quality.tracking,
            confidence = feature?.confidence ?: observation.quality.trackingConfidence,
            visibleFraction = feature?.visibleFraction ?: observation.quality.visibleFraction,
            apertureRatio = apertureRatio,
        )
    }

    private fun apertureRatio(
        source: FaceLandmarkSet,
        topology: FaceApertureTopology?,
        transform: FaceLocalAffineTransform?,
    ): Float? {
        if (topology == null || source.pointCount <= topology.maximumIndex) return null
        fun mapped(index: Int): NormalizedFacePoint {
            val x = source.x(index)
            val y = source.y(index)
            return if (transform == null) {
                NormalizedFacePoint(x, y)
            } else {
                NormalizedFacePoint(transform.mapX(x, y), transform.mapY(x, y))
            }
        }
        val firstCorner = mapped(topology.firstCornerIndex)
        val secondCorner = mapped(topology.secondCornerIndex)
        val upper = mapped(topology.upperIndex)
        val lower = mapped(topology.lowerIndex)
        val width = distance(firstCorner, secondCorner)
        if (!width.isFinite() || width <= MINIMUM_APERTURE_WIDTH) return null
        val height = distance(upper, lower)
        val ratio = height / width
        return ratio.takeIf { it.isFinite() && it >= 0f }
    }

    private fun distance(first: NormalizedFacePoint, second: NormalizedFacePoint): Float {
        val dx = second.x - first.x
        val dy = second.y - first.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun localWeightForResidual(residual: Float): Float {
        if (!residual.isFinite() || residual > maximumGlobalAffineResidual) return 0f
        if (maximumGlobalAffineResidual <= fullLocalAffineResidual) return 1f
        val normalized = ((residual - fullLocalAffineResidual) /
            (maximumGlobalAffineResidual - fullLocalAffineResidual)).coerceIn(0f, 1f)
        val smooth = normalized * normalized * (3f - 2f * normalized)
        return 1f - smooth
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

    private fun mapMouthRegion(
        region: FaceRegion,
        indices: IntArray,
        source: FaceLandmarkSet,
        transform: FaceLocalAffineTransform,
        globalDisplay: FaceLandmarkSet,
        localWeight: Float,
        localObservationTimestampNs: Long,
    ): FaceRegionGeometry = when (mouthLocalGeometryPolicy) {
        MouthLocalGeometryPolicy.FULL_CONTOUR -> mapRegion(
            region = region,
            indices = indices,
            source = source,
            transform = transform,
            globalDisplay = globalDisplay,
            localWeight = localWeight,
        )
        MouthLocalGeometryPolicy.CORNER_RESIDUAL -> mapMouthCornerResidualRegion(
            region = region,
            indices = indices,
            source = source,
            transform = transform,
            globalDisplay = globalDisplay,
            localWeight = localWeight,
        )
        MouthLocalGeometryPolicy.CALIBRATED_CORNER_RESIDUAL ->
            mapCalibratedMouthCornerResidualRegion(
                region = region,
                indices = indices,
                source = source,
                transform = transform,
                globalDisplay = globalDisplay,
                localWeight = localWeight,
                localObservationTimestampNs = localObservationTimestampNs,
            )
    }

    private fun mapCalibratedMouthCornerResidualRegion(
        region: FaceRegion,
        indices: IntArray,
        source: FaceLandmarkSet,
        transform: FaceLocalAffineTransform,
        globalDisplay: FaceLandmarkSet,
        localWeight: Float,
        localObservationTimestampNs: Long,
    ): FaceRegionGeometry {
        val aperture = checkNotNull(mouthAperture)
        val firstCorner = NormalizedFacePoint(
            globalDisplay.x(aperture.firstCornerIndex),
            globalDisplay.y(aperture.firstCornerIndex),
        )
        val secondCorner = NormalizedFacePoint(
            globalDisplay.x(aperture.secondCornerIndex),
            globalDisplay.y(aperture.secondCornerIndex),
        )
        val mouthAxisX = secondCorner.x - firstCorner.x
        val mouthAxisY = secondCorner.y - firstCorner.y
        val mouthWidth = distance(firstCorner, secondCorner)
        if (!mouthWidth.isFinite() || mouthWidth <= MINIMUM_APERTURE_WIDTH) {
            calibratedMouthCornerResidualFilter.reset()
            return copyGlobalRegion(region, indices, globalDisplay)
        }
        val axisX = mouthAxisX / mouthWidth
        val axisY = mouthAxisY / mouthWidth

        fun normalized(residual: NormalizedFacePoint) = NormalizedFacePoint(
            x = (residual.x * axisX + residual.y * axisY) / mouthWidth,
            y = (-residual.x * axisY + residual.y * axisX) / mouthWidth,
        )
        val firstMeasured = normalized(
            boundedCornerResidual(
                landmarkIndex = aperture.firstCornerIndex,
                source = source,
                transform = transform,
                globalDisplay = globalDisplay,
            ),
        )
        val secondMeasured = normalized(
            boundedCornerResidual(
                landmarkIndex = aperture.secondCornerIndex,
                source = source,
                transform = transform,
                globalDisplay = globalDisplay,
            ),
        )
        val filtered = calibratedMouthCornerResidualFilter.update(
            timestampNs = localObservationTimestampNs,
            measurement = MouthCornerResidualPair(
                firstX = firstMeasured.x,
                firstY = firstMeasured.y,
                secondX = secondMeasured.x,
                secondY = secondMeasured.y,
            ),
        )
        fun displayResidual(x: Float, y: Float) = NormalizedFacePoint(
            x = mouthWidth * (x * axisX - y * axisY),
            y = mouthWidth * (x * axisY + y * axisX),
        )
        val firstResidual = displayResidual(filtered.firstX, filtered.firstY)
        val secondResidual = displayResidual(filtered.secondX, filtered.secondY)
        val weight = localWeight.coerceIn(0f, 1f)
        val influenceRadius = (indices.size / CORNER_INFLUENCE_DIVISOR).coerceAtLeast(1)
        val mapped = FloatArray(indices.size * XY_COMPONENT_COUNT)
        indices.forEachIndexed { pointIndex, landmarkIndex ->
            val outputIndex = pointIndex * XY_COMPONENT_COUNT
            val firstInfluence = cornerInfluence(
                pointIndex,
                firstMouthCornerPosition,
                indices.size,
                influenceRadius,
            )
            val secondInfluence = cornerInfluence(
                pointIndex,
                secondMouthCornerPosition,
                indices.size,
                influenceRadius,
            )
            mapped[outputIndex] = globalDisplay.x(landmarkIndex) + weight * (
                firstResidual.x * firstInfluence + secondResidual.x * secondInfluence
                )
            mapped[outputIndex + 1] = globalDisplay.y(landmarkIndex) + weight * (
                firstResidual.y * firstInfluence + secondResidual.y * secondInfluence
                )
        }
        return FaceRegionGeometry.takeOwnership(region, mapped)
    }

    private fun mapMouthCornerResidualRegion(
        region: FaceRegion,
        indices: IntArray,
        source: FaceLandmarkSet,
        transform: FaceLocalAffineTransform,
        globalDisplay: FaceLandmarkSet,
        localWeight: Float,
    ): FaceRegionGeometry {
        val aperture = checkNotNull(mouthAperture)
        val firstResidual = boundedCornerResidual(
            landmarkIndex = aperture.firstCornerIndex,
            source = source,
            transform = transform,
            globalDisplay = globalDisplay,
        )
        val secondResidual = boundedCornerResidual(
            landmarkIndex = aperture.secondCornerIndex,
            source = source,
            transform = transform,
            globalDisplay = globalDisplay,
        )
        val weight = localWeight.coerceIn(0f, 1f)
        val influenceRadius = (indices.size / CORNER_INFLUENCE_DIVISOR).coerceAtLeast(1)
        val mapped = FloatArray(indices.size * XY_COMPONENT_COUNT)
        indices.forEachIndexed { pointIndex, landmarkIndex ->
            val output = pointIndex * XY_COMPONENT_COUNT
            val firstInfluence = cornerInfluence(
                pointIndex,
                firstMouthCornerPosition,
                indices.size,
                influenceRadius,
            )
            val secondInfluence = cornerInfluence(
                pointIndex,
                secondMouthCornerPosition,
                indices.size,
                influenceRadius,
            )
            mapped[output] = globalDisplay.x(landmarkIndex) + weight * (
                firstResidual.x * firstInfluence + secondResidual.x * secondInfluence
                )
            mapped[output + 1] = globalDisplay.y(landmarkIndex) + weight * (
                firstResidual.y * firstInfluence + secondResidual.y * secondInfluence
                )
        }
        return FaceRegionGeometry.takeOwnership(region, mapped)
    }

    private fun boundedCornerResidual(
        landmarkIndex: Int,
        source: FaceLandmarkSet,
        transform: FaceLocalAffineTransform,
        globalDisplay: FaceLandmarkSet,
    ): NormalizedFacePoint {
        val sourceX = source.x(landmarkIndex)
        val sourceY = source.y(landmarkIndex)
        val residualX = transform.mapX(sourceX, sourceY) - globalDisplay.x(landmarkIndex)
        val residualY = transform.mapY(sourceX, sourceY) - globalDisplay.y(landmarkIndex)
        val aperture = checkNotNull(mouthAperture)
        val mouthWidth = distance(
            NormalizedFacePoint(
                globalDisplay.x(aperture.firstCornerIndex),
                globalDisplay.y(aperture.firstCornerIndex),
            ),
            NormalizedFacePoint(
                globalDisplay.x(aperture.secondCornerIndex),
                globalDisplay.y(aperture.secondCornerIndex),
            ),
        )
        val maximumResidual = mouthWidth * MAXIMUM_CORNER_RESIDUAL_WIDTH_RATIO
        val magnitude = kotlin.math.sqrt(residualX * residualX + residualY * residualY)
        val scale = if (magnitude > maximumResidual && magnitude > 0f) {
            maximumResidual / magnitude
        } else {
            1f
        }
        return NormalizedFacePoint(residualX * scale, residualY * scale)
    }

    private fun cornerInfluence(
        pointIndex: Int,
        cornerIndex: Int,
        pointCount: Int,
        radius: Int,
    ): Float {
        val directDistance = kotlin.math.abs(pointIndex - cornerIndex)
        val circularDistance = minOf(directDistance, pointCount - directDistance)
        return (1f - circularDistance.toFloat() / radius).coerceIn(0f, 1f)
    }
    private fun mapRegion(
        region: FaceRegion,
        indices: IntArray,
        source: FaceLandmarkSet,
        transform: FaceLocalAffineTransform,
        globalDisplay: FaceLandmarkSet,
        localWeight: Float,
    ): FaceRegionGeometry {
        val weight = localWeight.coerceIn(0f, 1f)
        val mapped = FloatArray(indices.size * XY_COMPONENT_COUNT)
        indices.forEachIndexed { pointIndex, landmarkIndex ->
            val output = pointIndex * XY_COMPONENT_COUNT
            val sourceX = source.x(landmarkIndex)
            val sourceY = source.y(landmarkIndex)
            val localX = transform.mapX(sourceX, sourceY)
            val localY = transform.mapY(sourceX, sourceY)
            mapped[output] = globalDisplay.x(landmarkIndex) * (1f - weight) + localX * weight
            mapped[output + 1] =
                globalDisplay.y(landmarkIndex) * (1f - weight) + localY * weight
        }
        return FaceRegionGeometry.takeOwnership(region, mapped)
    }

    private fun FaceTrackingBackend.matches(observation: FaceObservation): Boolean =
        observation.backendId == backendId &&
            observation.role == role &&
            observation.topology == topology

    private companion object {
        const val MINIMUM_AFFINE_POINT_COUNT = 3
        const val MINIMUM_APERTURE_WIDTH = 1e-6f
        const val MINIMUM_LOCAL_GEOMETRY_WEIGHT = 1e-3f
        const val CORNER_INFLUENCE_DIVISOR = 4
        const val MAXIMUM_CORNER_RESIDUAL_WIDTH_RATIO = 0.18f
        const val XY_COMPONENT_COUNT = 2
        const val MISSING_INDEX = -1
    }
}
