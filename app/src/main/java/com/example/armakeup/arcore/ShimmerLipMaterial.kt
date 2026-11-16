package com.example.armakeup.arcore

/** Camera-lit pearl coat with anchored, oriented glitter flakes on the accepted lip annulus. */
internal object ShimmerLipMaterial {
    const val LOOKUP_WIDTH = 193
    const val LOOKUP_HEIGHT = 97

    /** Exact UInt32 hash from the iOS reference, stored as four little-endian bytes. */
    fun lookupBytes(): ByteArray = ByteArray(LOOKUP_WIDTH * LOOKUP_HEIGHT * 4).also { bytes ->
        for (y in 0 until LOOKUP_HEIGHT) {
            for (x in 0 until LOOKUP_WIDTH) {
                var value = (x * 0x8da6b343.toInt()) xor (y * 0xd8163841.toInt())
                value = value xor (value ushr 16)
                value *= 0x7feb352d
                value = value xor (value ushr 15)
                value *= 0x846ca68b.toInt()
                value = value xor (value ushr 16)
                val offset = (y * LOOKUP_WIDTH + x) * 4
                for (channel in 0..3) bytes[offset + channel] = (value ushr (channel * 8)).toByte()
            }
        }
    }

    const val FRAGMENT_FUNCTION = """
        uniform highp sampler2D uShimmerLookup;
        varying highp vec2 vShimmerUv;
        uniform float uShimmerIntensity;
        uniform float uShimmerParticleOpacity;
        uniform highp float uShimmerIdlePhase;
        uniform vec2 uShimmerViewAngles;
        uniform vec2 uShimmerLipThickness;
        uniform float uShimmerInsetScale;

        highp vec4 shimmerCell(highp vec2 cell) {
            return floor(texture2D(uShimmerLookup,(cell+0.5)/vec2(193.0,97.0))*255.0+0.5);
        }

        highp float shimmerNoiseValue(highp vec4 bytes) {
            return mod(bytes.r+bytes.g*256.0,1024.0)/1023.0;
        }

        // A broad, continuous interference band moves across the fixed flakes
        // with the relative face/camera angle. It never recolours the pigment base.
        vec3 shimmerIridescence(highp vec2 uv) {
            highp float phase=(uv.x-0.5)*3.8+(uv.y-0.5)*1.2+
                uShimmerViewAngles.x*4.2+uShimmerViewAngles.y*3.0;
            highp float band=(0.5+0.5*sin(phase))*3.0;
            vec3 color=mix(vec3(1.0,0.72,0.40),vec3(1.0,0.54,0.72),
                smoothstep(0.0,1.0,band));
            color=mix(color,vec3(0.68,0.60,1.0),smoothstep(1.0,2.0,band));
            return mix(color,vec3(0.52,0.83,1.0),smoothstep(2.0,3.0,band));
        }

        void shimmerNearestFine(highp vec2 uv,out highp vec4 seed,out highp vec2 offset) {
            highp vec2 q=uv*vec2(192.0,96.0);
            highp vec2 cell=floor(q);
            highp vec2 fraction=fract(q);
            highp float nearest=100.0;
            seed=vec4(0.0); offset=vec2(2.0);
            // Jitter over the whole cell; neighbouring sites avoid clipped flakes
            // and eliminate the visible rows from centre-constrained particles.
            for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
                highp vec2 delta=vec2(float(x),float(y));
                highp vec2 wrapped=cell+delta;
                // Avoid mod at exact texture-width multiples: mobile reciprocal
                // rounding can wrap zero to the final column instead.
                if(wrapped.x<0.0) wrapped.x+=193.0;
                if(wrapped.x>=193.0) wrapped.x-=193.0;
                if(wrapped.y<0.0) wrapped.y+=97.0;
                if(wrapped.y>=97.0) wrapped.y-=97.0;
                highp vec4 candidate=shimmerCell(wrapped);
                highp vec2 center=0.12+0.76*candidate.ra/255.0;
                highp vec2 displacement=fraction-delta-center;
                highp float distanceSquared=dot(displacement,displacement);
                if(distanceSquared<nearest) {
                    nearest=distanceSquared; seed=candidate; offset=displacement;
                }
            }
        }

        highp float shimmerFacetVisibility(highp float distanceSquared,highp float angularWidth) {
            highp float widthSquared=angularWidth*angularWidth;
            if(distanceSquared>=widthSquared) return 0.0;
            highp float fade=clamp((distanceSquared-0.18*widthSquared)/(0.82*widthSquared),0.0,1.0);
            // Compact C2 lobe: a facet is completely absent outside its reflection
            // window, with zero slope/curvature at both ends of the fade.
            return clamp(1.0-fade*fade*fade*(10.0+fade*(6.0*fade-15.0)),0.0,1.0);
        }

        highp float shimmerPulse(highp vec4 hash) {
            // Slow independent glints: each flake repeats roughly once per 6-10 seconds.
            // Integer cycles keep the bounded renderer clock seamless at 120s.
            highp float cycles=12.0+floor(hash.g/32.0);
            highp float phase=fract(uShimmerIdlePhase*(cycles/6.28318531)+hash.a/255.0);
            highp float duration=mix(0.28,0.46,hash.b/255.0);
            if(phase>=duration) return 0.0;
            highp float peak=duration*mix(0.40,0.50,hash.r/255.0);
            highp float t=phase<peak ? phase/peak : (duration-phase)/(duration-peak);
            t=clamp(t,0.0,1.0);
            return clamp(t*t*t*(10.0+t*(6.0*t-15.0)),0.0,1.0);
        }

        // Premultiplied linear coat. Fine particles form the material; the sparse
        // larger tier supplies occasional glints. Every anchored flake can pulse
        // fully on/off at rest, with additional angle-dependent reflected energy.
        vec4 shimmerFacets(highp vec2 uv, highp vec4 hash, float coarse,
            float illumination, float strength, vec3 interference) {
            if(uShimmerParticleOpacity<=0.0) return vec4(0.0);
            highp vec4 seedHash=hash;
            highp vec2 offset=vec2(0.0);
            if(coarse<0.5) shimmerNearestFine(uv,seedHash,offset);
            // Fine sites all carry glitter; the accent tier stays sparse.
            float chosen=coarse>0.5 ? 1.0-step(0.5,mod(seedHash.r,13.0)) : 1.0;
            if(chosen<0.5 || strength<0.0001) return vec4(0.0);
            highp vec2 seed=seedHash.gb/255.0;
            highp vec2 gridSize=mix(vec2(192.0,96.0),vec2(48.0,24.0),coarse);
            // Keep the dense site spacing, but shrink visible platelets about 45%.
            highp vec2 halfSize=mix(vec2(mix(0.13,0.21,seed.x),mix(0.10,0.18,seed.y)),
                vec2(mix(0.09,0.16,seed.x),mix(0.07,0.14,seed.y)),coarse);
            float cellPixelsX=256.0*max(uShimmerInsetScale,0.25)/(0.84*gridSize.x);
            float cellPixelsY=mix(uShimmerLipThickness.x,uShimmerLipThickness.y,vLowerLip)/
                (0.366*gridSize.y);
            float cellPixels=max(0.1,min(cellPixelsX,cellPixelsY));
            float resolved=smoothstep(0.65,1.8,cellPixels);
            float aa=clamp(0.4/cellPixels,0.035,0.16);
            highp float rotation=seedHash.a*(6.28318531/255.0);
            highp float cs=cos(rotation), sn=sin(rotation);
            if(coarse>0.5) {
                highp float margin=max(0.32,length(halfSize)*(1.0+aa)+0.01);
                highp vec2 center=margin+(1.0-2.0*margin)*
                    vec2(mod(seedHash.g,32.0),mod(seedHash.b,32.0))/31.0;
                offset=fract(uv*gridSize)-center;
            }
            highp vec2 rotated=vec2(cs*offset.x-sn*offset.y,sn*offset.x+cs*offset.y);
            highp vec2 facet=rotated/halfSize;
            highp float bevel=mix(0.54,0.88,seedHash.r/255.0);
            highp float polygon=max(max(abs(facet.x),abs(facet.y)),
                abs(facet.x)*bevel+abs(facet.y)*(1.25-bevel));
            float flake=1.0-smoothstep(1.0-aa,1.0+aa,polygon);
            vec2 halfDirection=vec2(-uShimmerViewAngles.x*0.70,0.12-uShimmerViewAngles.y*0.70)+
                vec2((uv.x-0.5)*0.45,(vLipUv.x-0.5)*mix(-0.32,0.32,vLowerLip));
            vec2 mismatch=(seed-0.5)*0.95-halfDirection;
            if(mod(seedHash.r,7.0)<0.5) {
                // Very gentle micro-flutter, slower and weaker than the main glint cycle.
                // Integer frequencies close seamlessly at the 120-second clock wrap.
                highp float cycles=8.0+floor(seedHash.g/32.0);
                highp float phaseA=uShimmerIdlePhase*cycles+seedHash.a*(6.28318531/255.0);
                highp float phaseB=uShimmerIdlePhase*(cycles+3.0)+seedHash.b*(6.28318531/255.0);
                vec2 flutter=vec2(sin(phaseA),sin(phaseB));
                mismatch+=flutter*mix(0.015,0.030,seedHash.b/255.0)*mix(1.0,0.45,coarse);
            }
            float distanceSquared=dot(mismatch,mismatch);
            float angularWidth=mix(0.23,0.34,seedHash.a/255.0)*mix(1.0,0.78,coarse);
            float visibility=shimmerFacetVisibility(distanceSquared,angularWidth);
            float pulse=shimmerPulse(seedHash);
            if(pulse<=0.0) return vec4(0.0);
            float peak=smoothstep(0.65,1.0,pulse);
            float flash=exp2(-distanceSquared*mix(240.0,360.0,coarse))*visibility;
            float angularLight=0.35+0.65*visibility;
            float faceShade=0.65+0.35*smoothstep(-0.7,0.8,facet.x*0.65+facet.y*0.35);
            float response=(0.20+1.75*peak+1.10*flash)*angularLight*pulse;
            float particle=flake*faceShade*response;
            // Preserve mean energy as microflakes become smaller than a pixel,
            // rather than leaving hard grid points or unstable aliased flecks.
            float ensemble=mix(0.09,0.055,coarse)*response;
            particle=mix(ensemble,particle,resolved);
            float rays=exp2(-abs(rotated.x)*110.0-abs(rotated.y)*16.0)+
                exp2(-abs(rotated.y)*110.0-abs(rotated.x)*16.0);
            rays*=1.0-smoothstep(0.10,0.19,max(abs(rotated.x),abs(rotated.y)));
            particle+=rays*peak*peak*0.20*coarse*resolved*angularLight*pulse;
            // Pulse gates the resolved shape, filtered energy and short glint alike.
            // The dark interval leaves no permanent dot or constant grain bed.
            // Independent transparency control affects only glitter, not the pigment or coat.
            float alpha=min(particle*illumination*strength,0.88)*clamp(uShimmerParticleOpacity,0.0,1.0);
            float innerDistance=(1.0-clamp(vLipUv.x,0.0,1.0))*
                mix(uShimmerLipThickness.x,uShimmerLipThickness.y,vLowerLip);
            float innerFade=smoothstep(mix(0.4,1.2,coarse)*uShimmerInsetScale,
                mix(1.6,3.0,coarse)*uShimmerInsetScale,innerDistance);
            alpha*=mix(1.0,innerFade,smoothstep(0.02,0.25,uSatinApertureVisibility));
            alpha*=smoothstep(0.02,0.15,vLipUv.x);
            float colorIndex=mod(seedHash.b,4.0);
            vec3 color=vec3(0.80,0.89,1.0);
            if(colorIndex<0.5) color=vec3(1.0,0.86,0.64);
            else if(colorIndex<1.5) color=vec3(1.0,0.73,0.82);
            else if(colorIndex<2.5) color=vec3(0.88,0.80,1.0);
            color=mix(color,interference,0.85);
            color=mix(color,vec3(1.0),max(flash,peak*0.8)*0.50);
            return vec4(color*alpha,alpha);
        }

        vec4 renderShimmerLip(float coverage) {
            // The particle layer is shared; only the underlying finish optics differ.
            float baseFinish = uFinishMode > 6.5 ? 2.0 : (uFinishMode > 5.5 ? 1.0 : uFinishMode);
            vec4 baseMaterial=renderIosLipForFinish(coverage,baseFinish);
            vec3 pigment=iosSrgbToLinear(baseMaterial.rgb);
            float coatMask=clamp(coverage*uTuningOpacity,0.0,1.0);
            float strength=clamp(uShimmerIntensity/3.0,0.0,1.333333);
            if(coatMask<0.0001 || strength<0.0001) return baseMaterial;
            vec3 camera=iosSrgbToLinear(cameraSrgbAt(vDisplayUv));
            vec2 dx=uSatinSourceTexelX*12.0*uTuningCameraSampleScale;
            vec2 dy=uSatinSourceTexelY*8.0*uTuningCameraSampleScale;
            vec3 surround=(iosSrgbToLinear(cameraSrgbAt(vDisplayUv+dx))+
                iosSrgbToLinear(cameraSrgbAt(vDisplayUv-dx))+
                iosSrgbToLinear(cameraSrgbAt(vDisplayUv+dy))+
                iosSrgbToLinear(cameraSrgbAt(vDisplayUv-dy)))*0.25;
            float cameraLight=iosDevLuminance(camera);
            float surroundLight=iosDevLuminance(surround);
            // Incident light follows the captured lips, not the darkness of the shade.
            float illumination=smoothstep(0.004,0.10,max(cameraLight,surroundLight*0.65));
            float foldLight=clamp(cameraLight/max(surroundLight,0.008),0.50,1.15);
            illumination*=foldLight;
            highp vec2 uv=clamp(vShimmerUv,vec2(0.0),vec2(0.999999));
            highp vec2 grid=uv*vec2(96.0,48.0);
            highp vec2 cell=floor(grid);
            highp vec2 fraction=fract(grid);
            highp vec2 weight=fraction*fraction*(3.0-2.0*fraction);
            highp vec4 hash=shimmerCell(cell);
            float grain=mix(mix(shimmerNoiseValue(hash),
                shimmerNoiseValue(shimmerCell(cell+vec2(1.0,0.0))),weight.x),
                mix(shimmerNoiseValue(shimmerCell(cell+vec2(0.0,1.0))),
                shimmerNoiseValue(shimmerCell(cell+vec2(1.0,1.0))),weight.x),weight.y);
            float shift=clamp(0.45+(grain-0.5)*0.4+
                0.20*sin(uShimmerViewAngles.x*2.2+uShimmerViewAngles.y*1.3),0.0,1.0);
            vec3 interference=shimmerIridescence(uv);
            vec3 pearl=mix(interference,
                mix(vec3(1.0,0.80,0.68),vec3(0.82,0.81,1.0),shift),0.20);
            // A weak continuous coat: avoid thresholded white islands on large
            // camera highlights. Most reflected energy comes from microfacets.
            float residual=max(cameraLight-surroundLight,0.0)/(surroundLight+0.06);
            float reflection=0.035*(residual/(1.0+residual))*
                clamp(uTuningHighlightStrength*uTuningSpecular,0.0,2.0);
            float bodyAlpha=(0.012*(0.75+0.25*grain)+reflection)*illumination*strength;
            vec4 fine=shimmerFacets(uv,hash,0.0,illumination,strength,interference);
            vec4 coarse=shimmerFacets(uv,shimmerCell(floor(uv*vec2(48.0,24.0))),
                1.0,illumination,strength,interference);
            vec3 coat=pearl*bodyAlpha;
            float coatAlpha=bodyAlpha;
            coat=fine.rgb+coat*(1.0-fine.a);
            coatAlpha=fine.a+coatAlpha*(1.0-fine.a);
            coat=coarse.rgb+coat*(1.0-coarse.a);
            coatAlpha=coarse.a+coatAlpha*(1.0-coarse.a);
            coat*=coatMask;
            coatAlpha*=coatMask;
            float alpha=coatAlpha+baseMaterial.a*(1.0-coatAlpha);
            vec3 color=coat+pigment*baseMaterial.a*(1.0-coatAlpha);
            return vec4(iosLinearToSrgb(color/max(alpha,0.0001)),alpha);
        }
    """
}
