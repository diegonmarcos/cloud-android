// Mirrored from front/e-Root/src/shaders/vertex.glsl — pass-through.
precision highp float;
attribute vec2 aPosition;
varying vec2 vUV;

void main() {
  vUV = aPosition;
  gl_Position = vec4(aPosition, 0.0, 1.0);
}
