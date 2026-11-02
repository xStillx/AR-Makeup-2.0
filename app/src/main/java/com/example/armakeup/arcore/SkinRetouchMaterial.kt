package com.example.armakeup.arcore

/** Uniform luminance smoothing shared by the two skin materials. No tone equalization. */
internal object SkinRetouchMaterial {
    const val SMOOTHING = """
        float skinSmoothingGain(vec3 raw, vec4 filtered) {
            float sourceY=max(luminance(raw),0.005);
            float targetY=max(luminance(linearColor(filtered.rgb)),0.005);
            return targetY/sourceY;
        }
    """
    const val FRAGMENT = """
        precision highp float;
        uniform sampler2D uCamera;
        uniform sampler2D uGuide;
        uniform vec2 uRadius;
        varying vec2 vUv;
    """ + FoundationMaterial.TRANSFER + """
        void main() {
            vec3 guide=linearColor(texture2D(uGuide,vUv).rgb);
            vec3 sum=vec3(0.0); float total=0.0;
            for(int i=-6;i<=6;i++) {
                vec2 uv=clamp(vUv+float(i)*uRadius,0.0,1.0);
                vec3 value=linearColor(texture2D(uCamera,uv).rgb);
                vec3 delta=linearColor(texture2D(uGuide,uv).rgb)-guide;
                float distance=float(i)/3.0;
                float weight=exp(-0.5*distance*distance-dot(delta,delta)/0.065);
                sum+=value*weight; total+=weight;
            }
            float light=luminance(guide);
            // Protect deep features and direct reflections, not ordinary skin texture.
            float guard=smoothstep(0.005,0.025,light)*(1.0-smoothstep(0.65,0.95,light));
            gl_FragColor=vec4(srgbColor(sum/max(total,0.0001)),guard);
        }
    """
}
