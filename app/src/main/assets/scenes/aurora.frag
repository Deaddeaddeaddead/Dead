precision highp float;
// «Аврора»: полотнища северного сияния с вертикальными лучами. Нижний край ярче, к верху
// цвет переходит в свет сцены и гаснет. При разблокировке сияние разгорается и колышется.
uniform vec2 uRes;
uniform vec4 uCrop;
uniform float uProgress;
uniform vec3 uPal[4];
uniform vec3 uGlass[2];
uniform vec2 uTint;
uniform vec2 uView;

const float ASPECT = 2.175;

float hash(float n) {
  return fract(sin(n) * 43758.5453);
}

float noise(float x) {
  float i = floor(x);
  float f = fract(x);
  float u = f * f * (3.0 - 2.0 * f);
  return mix(hash(i), hash(i + 1.0), u);
}

vec3 background(vec2 p) {
  float corner = exp(-length(p - vec2(-0.55, 1.15)) / 0.45)
      + 0.9 * exp(-length(p - vec2(0.55, -1.15)) / 0.45);
  return uPal[0] * 0.11 + uPal[3] * 0.3 * corner;
}

// Одно полотнище: линия низа y0(x), высота h, цвета снизу и сверху. Яркий нижний край,
// над ним — лучи, которые тянутся вверх и гаснут.
vec3 curtain(vec2 p, float seed, float y0, float amp, float h, vec3 low, vec3 high, float e) {
  float x = p.x;
  float wave = y0 + amp * sin(3.1 * x + seed + 1.8 * e) + 0.5 * amp * sin(7.3 * x - seed * 1.7 - e);
  float t = (p.y - wave) / h;
  if (t < -0.2) return vec3(0.0);
  // Лучи: шум вдоль кривой, с наклоном вверх; несколько частот — тонкие и широкие складки.
  float rx = x * 1.0 + 0.04 * t;
  float rays = noise(rx * 70.0 + seed * 13.0) * 0.6 + noise(rx * 23.0 - seed * 5.0) * 0.4;
  rays = 0.15 + 1.6 * rays * rays;
  float lowEdge = smoothstep(-0.2, 0.0, t);
  float fall = exp(-max(t, 0.0) * 1.6) * (1.0 - smoothstep(0.6, 1.4, t));
  float glow = lowEdge * fall * rays + 0.5 * exp(-t * t / 0.003) * lowEdge;
  return mix(low, high, clamp(t * 0.9, 0.0, 1.0)) * glow;
}

void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  vec2 f = uCrop.zw + uv * uCrop.xy;
  vec2 p = (f - 0.5) * vec2(1.0, ASPECT);
  float e = uProgress;
  vec3 c = background(p);
  float rise = mix(0.35, 1.0, e);
  vec2 q1 = p - uView * 0.03;
  vec2 q2 = p - uView * 0.06;
  c += curtain(q1, 1.3, -0.15, 0.13, 0.55, uPal[1], uPal[2], e) * 1.4 * rise;
  c += curtain(q2, 4.7, 0.25 + 0.1 * (1.0 - e), 0.1, 0.45, uGlass[0], uGlass[1], e) * 1.0 * rise;
  c += curtain(p, 8.1, -0.55, 0.09, 0.4, uPal[2], uGlass[0], e) * 0.7 * rise;
  // Над землёй — тёмный силуэт холмов внизу кадра.
  float hill = -0.85 + 0.06 * sin(4.0 * p.x + 0.5) + 0.03 * sin(11.0 * p.x);
  c *= mix(0.12, 1.0, smoothstep(hill - 0.004, hill + 0.004, p.y));
  gl_FragColor = vec4(1.0 - exp(-c), 1.0);
}
