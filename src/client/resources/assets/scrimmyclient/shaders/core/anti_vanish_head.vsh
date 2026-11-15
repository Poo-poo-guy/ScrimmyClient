#version 330
#moj_import <minecraft:dynamictransforms.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
out vec2 texCoord;
out vec4 vertexColor;

void main() {
    // The preview supplies a full perspective matrix; world projection and FOV are irrelevant.
    gl_Position = ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
    vertexColor = Color * ColorModulator;
}
