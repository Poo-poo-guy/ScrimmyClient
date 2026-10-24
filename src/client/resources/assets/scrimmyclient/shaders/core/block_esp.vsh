#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
out vec4 vertexColor;
noperspective out float edge;
flat out int ribbon;
void main() {
    vec4 clip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    ribbon = UV2.x == 0 ? 0 : 1;
    float side = float(UV2.x) / 1000.0;
    edge = side * (UV2.y < 0 ? -1.0 : 1.0);
    if (ribbon == 1) {
        int bits = (UV1.x & 65535) | ((UV1.y & 65535) << 16);
        vec4 other = ProjMat * ModelViewMat * vec4(UV0, intBitsToFloat(bits), 1.0);
        vec2 viewport = max(ColorModulator.xy, vec2(1.0));
        vec2 direction = (other.xy / other.w - clip.xy / clip.w) * viewport;
        float lengthSquared = dot(direction, direction);
        vec2 normal = lengthSquared > 0.000001 ? vec2(-direction.y, direction.x) * inversesqrt(lengthSquared) : vec2(1.0, 0.0);
        float halfWidth = float(abs(UV2.y)) / 200.0 + 0.75;
        clip.xy += normal * side * halfWidth * 2.0 / viewport * clip.w;
    }
    gl_Position = clip;
    vertexColor = vec4(Color.rgb, Color.a * ColorModulator.z);
}
