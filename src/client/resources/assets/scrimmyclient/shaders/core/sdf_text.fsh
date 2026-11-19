#version 330
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float distance = texture(Sampler0, texCoord0).r - 0.5;
    float pixel = max(fwidth(distance), 0.0001);
    float coverage = smoothstep(-0.5 * pixel, 0.5 * pixel, distance);
    float alpha = vertexColor.a * coverage;
    // Premultiply exactly once, paired with ONE / ONE_MINUS_SRC_ALPHA blending.
    fragColor = vec4(vertexColor.rgb * alpha, alpha);
}
