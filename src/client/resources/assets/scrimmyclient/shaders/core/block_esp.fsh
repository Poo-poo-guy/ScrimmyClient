#version 330
in vec4 vertexColor;
noperspective in float edge;
flat in int ribbon;
out vec4 fragColor;
void main() {
    float coverage = ribbon == 1 ? 1.0 - smoothstep(1.0 - max(fwidth(edge), 0.001), 1.0, abs(edge)) : 1.0;
    fragColor = vec4(vertexColor.rgb, vertexColor.a * coverage);
}
