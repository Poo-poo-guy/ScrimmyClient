#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
out vec4 vertexColor;
out vec2 point;
flat out int shape;
flat out int points;
flat out int kind;
flat out float innerRatio;
flat out float parameter;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    point = UV0;
    shape = UV1.x;
    innerRatio = float(UV1.y) / 1000.0;
    points = UV2.x & 255;
    kind = UV2.x >> 8;
    parameter = float(UV2.y) / 1000.0;
}
