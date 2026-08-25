// Adapted from front/e-Root/src/shaders/
//   fragment-base.glsl       (hash helpers)
//   effects/mode-36-matrix-rain.glsl
// Self-contained for Android OpenGL ES 2.0 — no #include, no uMode
// dispatcher (single-effect background). Aspect-corrected so the
// columns stay vertical on portrait phones.

precision highp float;

uniform float uTime;
uniform vec2  uResolution;

varying vec2 vUV;

float hash(float n) { return fract(sin(n) * 43758.5453); }

vec3 matrixRain(vec2 uv, float time) {
  vec3 col = vec3(0.0);
  float columns = 30.0;
  vec2 p = uv;
  p.x = floor(p.x * columns) / columns;
  float speed = hash(p.x * 100.0) * 0.5 + 0.5;
  float offset = hash(p.x * 200.0);
  float y = fract(p.y * 0.5 - time * speed + offset);
  float brightness = smoothstep(0.0, 0.3, y) * smoothstep(1.0, 0.5, y);
  float charFlicker = step(0.5, hash(floor(time * 10.0 + p.x * 50.0 + p.y * 100.0)));
  col = vec3(0.0, brightness * (0.5 + charFlicker * 0.5), 0.0);
  col *= 1.0 - length(uv) * 0.3;
  return col;
}

void main() {
  // Aspect-correct UV so columns stay vertical regardless of phone aspect.
  float aspect = uResolution.x / max(uResolution.y, 1.0);
  vec2 uv = vec2(vUV.x * aspect, vUV.y);
  vec3 col = matrixRain(uv, uTime);
  // Blend with the app's deep purple so it reads with the gradient backdrop.
  col = mix(vec3(0.176, 0.106, 0.412) * 0.15, col + col * 0.3, 0.85);
  gl_FragColor = vec4(col, 1.0);
}
