#version 150

// CS-style weapon shine: vanilla entity-cutout diffuse plus GGX specular from a sun (Light0) and fill (Light1),
// a sky/sun/ground environment reflection with Schlick Fresnel, and the paint's pearlescent hue shift by view
// angle. Per-texel bare-weapon roughness/metalness and paint-wear exposure come from the body's shine mask.
// Every term is multiplied by the Minecraft lightmap, so finishes never glow in the dark.

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
// R bare roughness, G bare metalness, B paint-wear exposure (linear cavity).
uniform sampler2D Sampler3;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform mat4 ProjMat;
// x paint roughness, y paint metalness, z pearlescence (0..1), w wear (0..1), or -1 for the unskinned weapon.
uniform vec4 ShineParams;

in float vertexDistance;
in vec4 vertexColor;
in vec4 rawColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 viewPos;
in vec3 viewNormal;
in vec3 viewLight0;
in vec3 viewLight1;

out vec4 fragColor;

const vec3 SKY = vec3(0.45, 0.62, 0.92);
const vec3 HORIZON = vec3(1.0, 0.97, 0.9);
const vec3 GROUND = vec3(0.22, 0.19, 0.15);
const vec3 SUN = vec3(1.0, 0.95, 0.85);

vec3 hueRotate(vec3 color, float angle) {
    const mat3 toYiq = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);
    const mat3 toRgb = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);
    vec3 yiq = toYiq * color;
    float c = cos(angle), s = sin(angle);
    yiq.yz = vec2(yiq.y * c - yiq.z * s, yiq.y * s + yiq.z * c);
    return clamp(toRgb * yiq, 0.0, 1.0);
}

float ggx(float nDotH, float alpha) {
    float a2 = alpha * alpha;
    float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
    return a2 / (3.14159265 * d * d);
}

vec3 specular(vec3 n, vec3 v, vec3 l, vec3 f0, float alpha) {
    float nDotL = dot(n, l);
    if (nDotL <= 0.0) return vec3(0.0);
    vec3 h = normalize(l + v);
    float nDotV = max(dot(n, v), 0.05);
    float k = alpha * 0.5;
    float visibility = 0.25 / ((nDotL * (1.0 - k) + k) * (nDotV * (1.0 - k) + k));
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    return min(ggx(max(dot(n, h), 0.0), alpha) * visibility * nDotL, 16.0) * fresnel;
}

vec3 environment(vec3 r, vec3 sun, float roughness) {
    vec3 sky = r.y >= 0.0 ? mix(HORIZON, SKY, sqrt(r.y)) : mix(HORIZON, GROUND, sqrt(min(-r.y * 3.0, 1.0)));
    // Rough surfaces see a blurred dome: pull toward its mean and widen the sun lobe.
    sky = mix(sky, vec3(0.62, 0.64, 0.66), roughness * 0.7);
    float lobe = mix(900.0, 12.0, roughness);
    float disc = pow(max(dot(r, sun), 0.0), lobe) * mix(6.0, 0.6, roughness);
    return sky + SUN * disc;
}

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    if (texel.a * rawColor.a < 0.1) discard;
    vec4 color = texel * vertexColor * ColorModulator;

    vec3 mask = texture(Sampler3, texCoord0).rgb;
    float roughness = mask.r;
    float metalness = mask.g;
    float pearl = 0.0;
    float paint = 0.0;
    if (ShineParams.w >= 0.0) {
        // Skinned: paint covers the weapon except where wear bites through on exposed edges (CS2 wear shape).
        float bare = smoothstep(0.58, 0.68, mask.b * (1.0 + 6.0 * ShineParams.w) * 0.7);
        roughness = mix(ShineParams.x * (0.75 + 0.5 * mask.r), mask.r, bare);
        metalness = mix(ShineParams.y, mask.g, bare);
        pearl = ShineParams.z * (1.0 - bare);
        paint = 1.0 - bare;
    }
    roughness = clamp(roughness, 0.06, 1.0);

    // Orthographic projections (GUI) look straight down -Z; perspective ones from the eye.
    vec3 v = ProjMat[3][3] == 1.0 ? vec3(0.0, 0.0, 1.0) : normalize(-viewPos);
    vec3 n = normalize(viewNormal);
    if (dot(n, v) < 0.0) n = -n;
    float nDotV = max(dot(n, v), 0.0);
    vec3 sun = normalize(viewLight0);

    vec3 albedo = texel.rgb * rawColor.rgb;
    if (pearl > 0.0) {
        // Doppler/Fade candy coats: hue swings with the view angle, so it moves as the weapon turns.
        float angle = pearl * ((1.0 - nDotV) * 3.0 + dot(n, sun) * 1.0);
        albedo = hueRotate(albedo, angle);
        color.rgb = hueRotate(color.rgb, angle);
    }
    vec3 f0 = mix(vec3(0.04), albedo, metalness);
    float alpha = max(roughness * roughness, 0.003);

    vec3 light = 3.0 * specular(n, v, sun, f0, alpha) + 0.8 * specular(n, v, normalize(viewLight1), f0, alpha);
    vec3 r = reflect(-v, n);
    vec3 fresnel = f0 + (max(vec3(1.0 - roughness), f0) - f0) * pow(1.0 - nDotV, 5.0);
    vec3 reflection = environment(r, sun, roughness) * fresnel * mix(1.0, 0.35, roughness * roughness);
    // Painted finishes carry a thin lacquer over non-metal paint: a sharper dielectric lobe and sky glint.
    float lacquer = paint * (1.0 - metalness) * 0.6;
    if (lacquer > 0.0) {
        float coatRoughness = max(roughness * 0.45, 0.08);
        float coatAlpha = coatRoughness * coatRoughness;
        light += lacquer * (3.0 * specular(n, v, sun, vec3(0.04), coatAlpha)
                + 0.8 * specular(n, v, normalize(viewLight1), vec3(0.04), coatAlpha));
        reflection += lacquer * environment(r, sun, coatRoughness) * (0.04 + 0.96 * pow(1.0 - nDotV, 5.0));
    }

    // Metals have almost no diffuse body colour; keep some so vanilla shading still reads.
    color.rgb *= 1.0 - metalness * 0.7;
    color.rgb += (light + reflection) * ColorModulator.rgb;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    // Never brighter than a white matte surface under the same light: highlights cannot glow in the dark.
    color.rgb = min(color.rgb, vec3(1.0));
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
