#version 330
in vec4 vertexColor;
in vec2 point;
flat in int shape;
flat in int points;
flat in int kind;
flat in float innerRatio;
flat in float parameter;
out vec4 fragColor;
const float PI = 3.14159265359;
float segment(vec2 p, vec2 a, vec2 b) {
    vec2 v = p - a, e = b - a;
    return length(v - e * clamp(dot(v, e) / max(dot(e, e), 0.00001), 0.0, 1.0));
}
float star(vec2 p) {
    // Exact signed distance to a simple alternating-radius polygon, up to 16 edges.
    float d = 100.0;
    bool inside = false;
    vec2 a = vec2(0.0, 1.0);
    for (int i = 1; i <= 16; i++) {
        if (i > points * 2) break;
        float angle = PI * 0.5 + float(i) * PI / float(points);
        vec2 b = vec2(cos(angle), sin(angle)) * ((i % 2 == 0) ? 1.0 : innerRatio);
        d = min(d, segment(p, a, b));
        if ((a.y > p.y) != (b.y > p.y)) {
            float edgeX = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x;
            if (p.x < edgeX) inside = !inside;
        }
        a = b;
    }
    return inside ? -d : d;
}
float snowflake(vec2 p) {
    float angle = atan(p.y, p.x);
    float folded = mod(angle + PI / 6.0, PI / 3.0) - PI / 6.0;
    vec2 q = vec2(cos(folded), abs(sin(folded))) * length(p);
    float d = segment(q, vec2(0.0), vec2(0.95, 0.0));
    d = min(d, segment(q, vec2(0.5, 0.0), vec2(0.75, 0.25)));
    d = min(d, segment(q, vec2(0.72, 0.0), vec2(0.88, 0.16)));
    return d - 0.045;
}
float distanceToShape(vec2 p) {
    if (shape == 0) return star(p);
    if (shape == 1) return (sqrt(abs(p.x)) + sqrt(abs(p.y)) - 1.0) * 0.4;
    if (shape == 2) return length(p) - 0.8;
    if (shape == 3) {
        // An implicit heart with derivative-based edge coverage remains sharp at any size.
        p.y = p.y * 1.1 + 0.15;
        float a = dot(p, p) - 0.65;
        return (a * a * a - p.x * p.x * p.y * p.y * p.y) / max(0.3, 3.0 * dot(p, p));
    }
    if (shape == 4) return (abs(p.x) + abs(p.y) - 0.95) * 0.7071;
    if (shape == 5) {
        vec2 q = abs(p);
        return min(max(q.x - 0.24, q.y - 0.9), max(q.x - 0.9, q.y - 0.24));
    }
    if (shape == 6) return snowflake(p);
    if (shape == 7) {
        vec2 a = vec2(0.0, 1.0), b = vec2(-0.866, -0.5), c = vec2(0.866, -0.5);
        float d = min(segment(p, a, b), min(segment(p, b, c), segment(p, c, a)));
        bool inside = p.y >= -0.5 && abs(p.x) <= (1.0 - p.y) * 0.57735;
        return inside ? -d : d;
    }
    return abs(length(p) - 0.7) - 0.1;
}
void main() {
    float d = distanceToShape(point);
    float pixel = max(fwidth(d), 0.0001);
    float coverage = 1.0 - smoothstep(-pixel * 0.5, pixel * 0.5, d);
    if (kind == 1) {
        coverage = (1.0 - smoothstep(parameter - pixel * 0.5, parameter + pixel * 0.5, d)) - coverage;
    } else if (kind == 2) {
        float outside = max(0.0, d);
        float sigma = max(0.02, parameter * 0.45);
        coverage = exp(-outside * outside / (2.0 * sigma * sigma)) * (1.0 - coverage) * 0.45;
    } else if (shape == 2) {
        coverage *= 0.4 + 0.6 * (1.0 - smoothstep(0.0, 0.8, length(point)));
    }
    fragColor = vec4(vertexColor.rgb, vertexColor.a * max(0.0, coverage));
    if (fragColor.a < 0.002) discard;
}
