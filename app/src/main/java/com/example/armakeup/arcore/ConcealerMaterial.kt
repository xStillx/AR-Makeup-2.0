package com.example.armakeup.arcore

/** Lower-lid pigment with the accepted uniform smoothing and existing soft contour. */
internal object ConcealerMaterial {
    const val DEBUG_HARD_BOUNDARY = false
    const val FRAGMENT = """
        precision highp float;
        uniform sampler2D uCamera;
        uniform sampler2D uBase;
        uniform sampler2D uSoft;
        uniform sampler2D uRetouchMap;
        uniform float uCoverage;
        uniform float uRetouch;
        uniform vec2 uViewport;
        uniform vec2 uFeatureScale;
        uniform float uBaseHasSmoothing;
        uniform vec3 uColor;
        uniform vec3 uCharts[4];
        uniform vec4 uUnderEyes[${ConcealerFaceMesh.CONTOUR_POINTS}];
        uniform vec4 uEyes[16];
        uniform vec3 uReferences[4];
        varying vec4 vSkin;
        const bool kHardBoundary = $DEBUG_HARD_BOUNDARY;
    """ + FoundationMaterial.TRANSFER + SkinRetouchMaterial.SMOOTHING + """
        float segmentDistance(vec2 p, vec2 a, vec2 b) {
            vec2 e=b-a;
            return length(p-a-e*clamp(dot(p-a,e)/max(dot(e,e),1e-12),0.0,1.0));
        }
        bool crossing(vec2 p, vec2 a, vec2 b) {
            float dy=b.y-a.y;
            if(abs(dy)<1e-9) return false;
            return ((a.y>p.y)!=(b.y>p.y)) && p.x<a.x+(p.y-a.y)*(b.x-a.x)/dy;
        }
        float contourCoverage(float signedDistance) {
            // Use the accepted blush's minimum kernel scale and soft shoulder.
            // Most of the longer transition falls INSIDE the drawn outline:
            // coverage at its rim drops from 45% to 14%, while the outer tail
            // stays below 5mm. Anchors and the deep interior are preserved.
            const float feather=0.003;
            signedDistance-=0.004;
            float rimBlurRadius=feather*1.8*1.90;
            float midpoint=feather*0.4588039;
            float innerDepth=max((signedDistance-feather*0.5)/feather,0.0);
            float softInnerDepth=innerDepth/(1.0+1.45*innerDepth);
            float materialDistance=min(signedDistance,feather*0.5)+feather*softInnerDepth;
            float rimBlend=clamp(0.5+(materialDistance-midpoint)/(2.0*rimBlurRadius),0.0,1.0);
            float side=2.0*abs(rimBlend-0.5);
            float tail=1.0-side;
            float shoulder=side*tail*tail*tail*(1.0+3.0*side);
            float blend=0.5+sign(rimBlend-0.5)*0.5*(side-0.65*shoulder);
            return blend*blend*blend*(10.0+blend*(6.0*blend-15.0));
        }
        void main() {
            if(uCoverage<=0.0) discard;
            vec2 l=vec2(dot(vSkin.xyz,uCharts[0]),dot(vSkin.xyz,uCharts[1]));
            vec2 r=vec2(dot(vSkin.xyz,uCharts[2]),dot(vSkin.xyz,uCharts[3]));
            float ld=1.0; float rd=1.0; bool il=false; bool ir=false;
            vec4 prev=uUnderEyes[${ConcealerFaceMesh.CONTOUR_POINTS - 1}];
            for(int i=0;i<${ConcealerFaceMesh.CONTOUR_POINTS};i++) {
                vec4 q=uUnderEyes[i];
                ld=min(ld,segmentDistance(l,prev.xy,q.xy));
                rd=min(rd,segmentDistance(r,prev.zw,q.zw));
                if(crossing(l,prev.xy,q.xy)) il=!il;
                if(crossing(r,prev.zw,q.zw)) ir=!ir;
                prev=q;
            }
            float leftMask=kHardBoundary?(il?1.0:0.0):contourCoverage(il?ld:-ld);
            float rightMask=kHardBoundary?(ir?1.0:0.0):contourCoverage(ir?rd:-rd);
            float mask=max(leftMask,rightMask);
            if(mask<0.0001) discard;
            // Screen-metric eye exclusions also protect a nearer eye in strong profile.
            vec2 p=(gl_FragCoord.xy/uViewport*2.0-1.0)*uFeatureScale;
            ld=1.0; rd=1.0; il=false; ir=false; prev=uEyes[15];
            for(int i=0;i<16;i++) {
                vec4 q=uEyes[i];
                ld=min(ld,segmentDistance(p,prev.xy,q.xy));
                rd=min(rd,segmentDistance(p,prev.zw,q.zw));
                if(crossing(p,prev.xy,q.xy)) il=!il;
                if(crossing(p,prev.zw,q.zw)) ir=!ir;
                prev=q;
            }
            float eyeDistance=min(ld,rd);
            // Pigment and smoothing share the same blush-style edge mask.
            if(kHardBoundary) {
                // Temporary boundary inspection, with a hard eye safety exclusion.
                mask=max(leftMask,rightMask)*((il||ir)?0.0:step(0.0007,eyeDistance));
            } else {
                // A wider C2 skin-side eye fade avoids a second, narrow visible rim.
                float eyeBlend=clamp((eyeDistance-0.0007)/0.0028,0.0,1.0);
                float eyeCoverage=eyeBlend*eyeBlend*eyeBlend*(10.0+eyeBlend*(6.0*eyeBlend-15.0));
                mask=max(leftMask,rightMask)*((il||ir)?0.0:eyeCoverage);
            }
            if(mask<0.0001) discard;
            vec2 uv=gl_FragCoord.xy/uViewport;
            vec3 raw=linearColor(texture2D(uCamera,uv).rgb);
            vec4 baseSample=texture2D(uBase,uv);
            vec3 base=linearColor(baseSample.rgb);
            vec3 camera=base;
            vec3 soft=linearColor(texture2D(uSoft,uv).rgb);
            float rawY=luminance(raw); float softY=max(luminance(soft),0.002);
            vec4 filtered=texture2D(uRetouchMap,uv);
            float desired=mask*clamp(uRetouch,0.0,1.0)*filtered.a;
            float inherited=uBaseHasSmoothing*baseSample.a;
            float combined=max(desired,inherited);
            float delta=skinSmoothingGain(raw,filtered)-1.0;
            // The foundation layer already contains inherited smoothing. Apply
            // only the remaining gain: equal strengths never smooth twice.
            camera*=max(1.0+combined*delta,0.001)/max(1.0+inherited*delta,0.001);
            float referenceY=0.0; float referenceWeight=0.0;
            for(int i=0;i<4;i++) {
                vec3 ref=uReferences[i];
                float y=luminance(linearColor(texture2D(uSoft,ref.xy).rgb));
                float w=ref.z*smoothstep(0.015,0.07,y)*(1.0-smoothstep(0.75,0.98,y));
                referenceY+=y*w; referenceWeight+=w;
            }
            referenceY=referenceWeight>0.01?referenceY/referenceWeight:softY;
            vec3 pigment=linearColor(uColor);
            float pigmentY=max(luminance(pigment),0.002);
            float gain=clamp(pigmentY/max(referenceY,0.02),0.65,1.45);
            vec3 diffuse=pigment/pigmentY*softY*gain;
            float coverage=clamp(uCoverage,0.0,1.0);
            float cameraY=luminance(camera);
            float detail=clamp(cameraY/softY,0.3,3.0);
            vec3 chroma=camera/max(soft*detail,vec3(0.002));
            vec3 material=diffuse*detail*mix(vec3(1.0),clamp(chroma,0.3,3.0),1.0-0.85*coverage);
            float highlight=smoothstep(0.5,0.9,rawY)*smoothstep(0.02,0.12,rawY-softY);
            float amount=mask*coverage*0.90*(1.0-0.85*highlight)*smoothstep(0.003,0.025,rawY);
            // Increase pigment optical thickness by 50% without changing saved
            // slider values, clipping opacity, or introducing a separate edge.
            float transmission=1.0-amount;
            amount=1.0-transmission*sqrt(transmission);
            gl_FragColor=vec4(srgbColor(clamp(mix(camera,material,amount),0.0,1.0)),1.0);
        }
    """
}
