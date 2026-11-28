#version 330
#moj_import <minecraft:dynamictransforms.glsl>
// This projection is captured before Minecraft applies walking bob and hurt shake.
layout(std140) uniform BlockEspProjection { mat4 ProjMat; };
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Animation;
in vec3 Normal;
out vec4 vertexColor;
noperspective out float edge;
flat out int ribbon;

// Projection, not selection: every submitted block gets a finite on-screen anchor.
// In-view boxes retain normal perspective. Others keep the same geometry/color at the edge.
vec4 projectBlock(vec3 point, vec3 center) {
    vec3 eyeCenter = (ModelViewMat * vec4(center, 1.0)).xyz;
    vec3 offset = (ModelViewMat * vec4(point - center, 0.0)).xyz;
    float depth = max(1.0, max(abs(eyeCenter.z), max(abs(ProjMat[0][0] * eyeCenter.x), abs(ProjMat[1][1] * eyeCenter.y))));
    vec2 viewport = max(ColorModulator.xy, vec2(1.0));
    vec2 limit = vec2(1.0) - max(vec2(12.0) / viewport, min(vec2(0.25), vec2(0.6) * vec2(ProjMat[0][0], ProjMat[1][1]) / depth));
    vec4 centerClip = ProjMat * vec4(eyeCenter.xy, -depth, 1.0);
    vec2 anchor = centerClip.xy / centerClip.w;
    vec2 direction = vec2(ProjMat[0][0], ProjMat[1][1]) * eyeCenter.xy - vec2(0.0, max(0.0, eyeCenter.z));
    // Directly behind the camera has no unique left/right bearing; use the bottom edge.
    direction = dot(direction, direction) < 0.000001 ? vec2(0.0, -1.0) : direction;
    vec2 edgeAnchor = direction / max(abs(direction.x) / limit.x, abs(direction.y) / limit.y);
    vec2 bounded = anchor / max(1.0, max(abs(anchor.x) / limit.x, abs(anchor.y) / limit.y));
    bounded = mix(bounded, edgeAnchor, step(0.0, eyeCenter.z));
    vec4 clip = ProjMat * vec4(vec3(eyeCenter.xy, -depth) + offset, 1.0);
    clip.xy += (bounded - anchor) * clip.w;
    // The player's draw distance, rather than Minecraft's terrain far plane, sets lifetime.
    clip.z = clamp(clip.z, -0.999 * clip.w, 0.999 * clip.w);
    return clip;
}

void main() {
    bool tracer = abs(UV2.x) == 2000;
    vec3 center = tracer ? Position : Position - Normal * 0.502;
    int bits = (UV1.x & 65535) | ((UV1.y & 65535) << 16);
    vec3 otherPoint = vec3(UV0, intBitsToFloat(bits));
    vec4 clip = projectBlock(Position, center);
    vec4 other = projectBlock(otherPoint, center);
    if (tracer) {
        // Feet uses the same camera-relative world transform as the block centers.
        // Crosshair is exactly 0.15 blocks along the current render camera's forward axis.
        vec4 start = TextureMat[3].w > 0.5 ? projectBlock(TextureMat[2].xyz, TextureMat[2].xyz)
            : ProjMat * vec4(0.0, 0.0, -0.15, 1.0);
        if (UV2.y > 0) clip = start;
        else other = start;
    }
    ribbon = UV2.x == 0 ? 0 : 1;
    float side = float(UV2.x) / (tracer ? 2000.0 : 1000.0);
    edge = side * (UV2.y < 0 ? -1.0 : 1.0);
    if (ribbon == 1) {
        vec2 viewport = max(ColorModulator.xy, vec2(1.0));
        vec2 direction = (other.xy / other.w - clip.xy / clip.w) * viewport;
        float lengthSquared = dot(direction, direction);
        vec2 normal = lengthSquared > 0.000001 ? vec2(-direction.y, direction.x) * inversesqrt(lengthSquared) : vec2(1.0, 0.0);
        float halfWidth = float(abs(UV2.y)) / 200.0 + 0.75;
        clip.xy += normal * side * halfWidth * 2.0 / viewport * clip.w;
    }
    gl_Position = clip;
    float seconds = TextureMat[1].w;
    float opacity = Animation.z + (Animation.x - Animation.z) * exp(-max(0.0, seconds - Animation.y) * TextureMat[3].x);
    opacity *= 1.0 - TextureMat[2].w * min(1.0, length(center - TextureMat[0].xyz) / TextureMat[0].w);
    opacity *= 1.0 - TextureMat[3].y * (0.5 + 0.5 * sin(seconds * TextureMat[3].z * 6.2831853));
    vertexColor = vec4(Color.rgb, Color.a * ColorModulator.z * opacity);
}
