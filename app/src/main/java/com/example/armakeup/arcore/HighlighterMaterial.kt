package com.example.armakeup.arcore

/** A translucent reflective coat. Finish changes the lobe, tint, and surface-anchored flakes. */
internal object HighlighterMaterial {
    const val VERTEX = """
        attribute vec4 aPosition;
        attribute vec3 aLocal;
        attribute vec3 aNormal;
        attribute vec3 aView;
        varying vec4 vSkin;
        varying vec3 vNormal;
        varying vec3 vView;
        void main() {
            gl_Position=aPosition;
            vSkin=vec4(aLocal,0.0); vNormal=aNormal; vView=aView;
        }
    """

    fun fragment(): String {
        // The accepted continuous face mask protects eye openings, brows, lips,
        // nostrils and the face rim. Only the new coat uses this generated variant.
        val prefix = FoundationMaterial.FRAGMENT.substringBefore("            vec2 uv = gl_FragCoord.xy / uViewport;")
            .replace("        void main() {", DECLARATIONS + "\n        void main() {")
            .replace("            vec2 p =", "            float zone=highlightCoverage(vSkin.xyz);\n            if(zone<0.0001 || uIntensity<=0.0) discard;\n            vec2 p =")
        return prefix + """
            mask*=zone;
            if(mask<0.0001) discard;
            vec2 uv=gl_FragCoord.xy/uViewport;
            vec3 raw=texture2D(uCamera,uv).rgb;
            vec3 camera=linearColor(raw);
            float cameraY=luminance(camera);
            float meanY=0.0; float weights=0.0; vec2 gradient=vec2(0.0);
            for(int i=0;i<4;i++) {
                float y=luminance(linearColor(texture2D(uCamera,uReferences[i].xy).rgb));
                float w=uReferences[i].z*smoothstep(0.008,0.04,y)*(1.0-smoothstep(0.7,0.98,y));
                meanY+=y*w; weights+=w;
            }
            meanY=weights>0.01?meanY/weights:max(cameraY,0.04);
            for(int i=0;i<4;i++) {
                float y=luminance(linearColor(texture2D(uCamera,uReferences[i].xy).rgb));
                float w=uReferences[i].z*smoothstep(0.008,0.04,y)*(1.0-smoothstep(0.7,0.98,y));
                gradient+=uReferenceDirections[i].xy*clamp((y-meanY)/max(meanY,0.02),-0.6,0.6)*w/max(weights,0.01);
            }
            // Bounded camera-derived light gradient, with a soft frontal/top key
            // when the captured skin is evenly lit. This is not an HDR environment map.
            vec3 light=normalize(vec3(clamp(gradient.x,-0.4,0.4),0.35+clamp(gradient.y,-0.3,0.3),1.0));
            vec3 view=normalize(-vView);
            vec3 normal=normalize(vNormal);
            float noV=clamp(dot(normal,view),0.0,1.0);
            float visibility=smoothstep(0.12,0.38,noV);
            if(visibility<0.0001) discard;
            vec3 halfVector=normalize(light+view);
            float noH=clamp(dot(normal,halfVector),0.0,1.0);
            float noL=clamp(dot(normal,light),0.0,1.0);
            float a=uFinish.x*uFinish.x;
            float a2=a*a;
            float denominator=noH*noH*(a2-1.0)+1.0;
            float distribution=a2/max(3.14159265*denominator*denominator,0.00001);
            float lobe=distribution/(distribution+4.0);
            float fresnel=0.25+0.75*pow(1.0-noV,5.0);
            // Pearl platelets also scatter a broad area of ambient light. This
            // keeps satin/duochrome readable away from the narrow specular peak.
            float body=uFinish.y*0.14*(0.25+0.75*pow(noH,3.0))*noL;
            float reflection=uFinish.z*0.28*(lobe+0.12*pow(noH,20.0))*noL*(0.8+fresnel);
            float shift=smoothstep(0.02,0.40,1.0-noV)*uFlakes.w;
            vec3 pigment=mix(linearColor(uHighlightColor),linearColor(uShiftColor),shift);
            pigment/=max(max(pigment.r,max(pigment.g,pigment.b)),0.01);
            vec3 coat=mix(vec3(1.0),pigment,uFinish.w);
            float flakes=surfaceFlakes(vSkin.xyz,noH);
            float illumination=clamp(sqrt(max(meanY,0.0)/0.25),0.45,1.25);
            float guard=smoothstep(0.005,0.025,cameraY)*(1.0-smoothstep(0.80,0.99,cameraY));
            vec3 energy=(coat*(body+reflection)+mix(coat,vec3(1.0),0.4)*flakes*0.45)*
                clamp(uIntensity,0.0,1.5)*mask*visibility*guard*illumination;
            // Convert the linear-light increment to a screen-blend coat. Existing
            // foundation/concealer/blush RGB stays underneath, including its texture.
            vec3 desired=srgbColor(clamp(camera+energy,0.0,1.0));
            vec3 screen=clamp((desired-raw)/max(vec3(1.0)-raw,vec3(0.02)),0.0,0.9);
            gl_FragColor=vec4(screen,0.0);
        }
        """
    }

    private const val DECLARATIONS = """
        uniform float uIntensity;
        uniform vec4 uFinish;
        uniform vec4 uFlakes;
        uniform vec4 uZones;
        uniform vec3 uHighlightColor;
        uniform vec3 uShiftColor;
        uniform float uPixelMetres;
        uniform vec4 uCheeks[16];
        uniform vec4 uNose[3];
        uniform vec4 uBrowCoat[6];
        uniform vec4 uCupid[3];
        uniform vec3 uReferenceDirections[4];
        varying vec3 vNormal;
        varying vec3 vView;
        float quintic(float x) { x=clamp(x,0.0,1.0); return x*x*x*(10.0+x*(6.0*x-15.0)); }
        float ribbon(vec3 p,vec4 a,vec4 b) {
            vec3 edge=b.xyz-a.xyz;
            float t=clamp(dot(p-a.xyz,edge)/max(dot(edge,edge),1e-12),0.0,1.0);
            float width=max(mix(a.w,b.w,t),0.0005);
            float distance=length(p-mix(a.xyz,b.xyz,t));
            return 1.0-quintic((distance/width-0.25)/0.75);
        }
        float highlightCoverage(vec3 p) {
            float c=0.0;
            if(uZones.x>0.5) for(int i=0;i<7;i++) {
                c=max(c,ribbon(p,uCheeks[i],uCheeks[i+1]));
                c=max(c,ribbon(p,uCheeks[i+8],uCheeks[i+9]));
            }
            if(uZones.y>0.5) for(int i=0;i<2;i++) c=max(c,ribbon(p,uNose[i],uNose[i+1]));
            if(uZones.z>0.5) for(int i=0;i<2;i++) {
                c=max(c,ribbon(p,uBrowCoat[i],uBrowCoat[i+1]));
                c=max(c,ribbon(p,uBrowCoat[i+3],uBrowCoat[i+4]));
            }
            if(uZones.w>0.5) for(int i=0;i<2;i++) c=max(c,ribbon(p,uCupid[i],uCupid[i+1]));
            return c;
        }
        float hash21(vec2 p) {
            vec3 p3=fract(vec3(p.xyx)*0.1031);
            p3+=dot(p3,p3.yzx+33.33);
            return fract((p3.x+p3.y)*p3.z);
        }
        float surfaceFlakes(vec3 p,float noH) {
            if(uFlakes.x<=0.0) return 0.0;
            // Fixed metric cells on the face; no frame number, screen UV or time seed.
            float cellSize=max(uFlakes.y,0.0001);
            vec2 q=p.xy/cellSize;
            vec2 cell=floor(q);
            float seed=hash21(cell);
            float chosen=step(1.0-uFlakes.z,seed);
            vec2 center=vec2(0.25)+vec2(hash21(cell+7.2),hash21(cell+29.3))*0.5;
            float aa=clamp(uPixelMetres/cellSize*0.65,0.025,0.4);
            float dotShape=1.0-smoothstep(max(0.0,0.17-aa),0.17+aa,length(fract(q)-center));
            float alignment=0.78+0.215*hash21(cell+51.7);
            float glint=pow(clamp(1.0-abs(noH-alignment)/0.045,0.0,1.0),2.0);
            float resolved=smoothstep(0.8,1.8,cellSize/max(uPixelMetres,0.000001));
            float ensemble=uFlakes.z*0.035*pow(noH,12.0);
            return uFlakes.x*mix(ensemble,chosen*dotShape*glint,resolved);
        }
    """
}
