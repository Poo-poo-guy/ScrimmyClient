#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
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
void main() {
    vec3 point = Position;
    int bits = (UV1.x & 65535) | ((UV1.y & 65535) << 16);
    vec3 otherPoint = vec3(UV0, intBitsToFloat(bits));
    bool tracer = abs(UV2.x) == 2000;
    vec3 distancePoint = tracer ? point : point - Normal * 0.502;
    if (tracer) {
        vec3 crosshair = TextureMat[1].xyz + TextureMat[2].xyz;
        if (UV2.y > 0) point = crosshair;
        else otherPoint = crosshair;
    }
    vec4 eye = ModelViewMat * vec4(point, 1.0);
    vec4 other = ModelViewMat * vec4(otherPoint, 1.0);
    ribbon = UV2.x == 0 ? 0 : 1;
    float side = float(UV2.x) / (tracer ? 2000.0 : 1000.0);
    edge = side * (UV2.y < 0 ? -1.0 : 1.0);
    bool clipped = false;
    if (ribbon == 1) {
        clipped = eye.z > -0.06 && other.z > -0.06;
        if (eye.z > -0.06 && !clipped)
            eye = mix(eye, other, (-0.06 - eye.z) / (other.z - eye.z));
        else if (other.z > -0.06 && !clipped)
            other = mix(other, eye, (-0.06 - other.z) / (eye.z - other.z));
    }
    vec4 clip = ProjMat * eye;
    if (ribbon == 1 && !clipped) {
        other = ProjMat * other;
        vec2 viewport = max(ColorModulator.xy, vec2(1.0));
        vec2 direction = (other.xy / other.w - clip.xy / clip.w) * viewport;
        float lengthSquared = dot(direction, direction);
        vec2 normal = lengthSquared > 0.000001 ? vec2(-direction.y, direction.x) * inversesqrt(lengthSquared) : vec2(1.0, 0.0);
        float halfWidth = float(abs(UV2.y)) / 200.0 + 0.75;
        clip.xy += normal * side * halfWidth * 2.0 / viewport * clip.w;
    }
    gl_Position = clipped ? vec4(0.0, 0.0, 2.0, 1.0) : clip;
    float seconds = TextureMat[1].w;
    float opacity = Animation.z + (Animation.x - Animation.z) * exp(-max(0.0, seconds - Animation.y) * TextureMat[3].x);
    opacity *= 1.0 - TextureMat[2].w * min(1.0, length(distancePoint - TextureMat[0].xyz) / TextureMat[0].w);
    opacity *= 1.0 - TextureMat[3].y * (0.5 + 0.5 * sin(seconds * TextureMat[3].z * 6.2831853));
    vertexColor = vec4(Color.rgb, Color.a * ColorModulator.z * opacity);
}
