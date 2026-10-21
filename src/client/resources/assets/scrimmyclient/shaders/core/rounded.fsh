#version 330

#ifdef GLASS
uniform sampler2D Sampler0;
#endif
in vec4 vertexColor;
in vec2 localPosition;
flat in vec2 halfSize;
flat in float radius;
flat in int kind;
out vec4 fragColor;

void main() {
    vec2 q = abs(localPosition) - halfSize + radius;
    float distanceToEdge = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    // Derivatives measure one framebuffer pixel, including Retina and both GUI scales.
    float pixel = max(fwidth(distanceToEdge), 0.0001);
    float coverage = 1.0 - smoothstep(-0.5 * pixel, 0.5 * pixel, distanceToEdge);
    if (kind == 1) {
        float inner = 1.0 - smoothstep(-1.5 * pixel, -0.5 * pixel, distanceToEdge);
        coverage = max(0.0, coverage - inner);
    } else if (kind == 2) {
        float outside = max(distanceToEdge, 0.0);
        coverage = exp(-outside * outside / 12.0) * (1.0 - coverage);
    }
    vec4 color = vertexColor;
#ifdef GLASS
    color.rgb = texture(Sampler0, gl_FragCoord.xy / vec2(textureSize(Sampler0, 0))).rgb;
#endif
    color.a *= coverage;
    if (color.a < 0.001) discard;
    fragColor = color;
}
