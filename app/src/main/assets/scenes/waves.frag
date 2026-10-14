precision highp float;
// «Волны»: слои мягких волн один над другим, как шёлк. Гребни светятся светом сцены, под
// гребнем — тень от слоя выше. При разблокировке волны поднимаются и перекатываются.
uniform vec2 uRes;
uniform vec4 uCrop;
uniform float uProgress;
uniform vec3 uPal[4];
uniform vec3 uGlass[2];
uniform vec2 uTint;
uniform vec2 uView;

const float ASPECT = 2.175;
const int LAYERS = 5;

vec3 background(vec2 p) {
  float corner = exp(-length(p - vec2(-0.55, 1.15)) / 0.45)
      + 0.9 * exp(-length(p - vec2(0.55, -1.15)) / 0.45);
  return uPal[0] * 0.11 + uPal[3] * 0.3 * corner;
}

// Высота гребня слоя i в точке x: базовая линия поднимается к концу анимации.
float crest(int i, float x, float e) {
  float fi = float(i);
  float base = 0.62 - 0.36 * fi + mix(-0.55, 0.0, e) * (1.0 + 0.25 * fi);
  float ph = 1.7 * fi + 2.4 * e;
  return base + 0.085 * sin(5.2 * x + ph) + 0.045 * sin(9.1 * x - 1.3 * ph + fi)
      + 0.12 * x * (fi - 2.0) * 0.6;
}

vec3 layerColor(int i) {
  float fi = float(i) / float(LAYERS - 1);
  vec3 a = mix(uPal[2], uPal[1], fi);
  vec3 g = mix(uGlass[0], uGlass[1], fi);
  return mix(a, g, 0.25) * mix(0.55, 1.15, fi);
}

void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  vec2 f = uCrop.zw + uv * uCrop.xy;
  vec2 p = (f - 0.5) * vec2(1.0, ASPECT);
  float pxw = uCrop.x / uRes.x;
  float e = uProgress;
  vec3 c = background(p);
  // Слои снизу вверх по глубине: нижние ближе — сильнее сдвиг при параллаксе.
  for (int i = 0; i < LAYERS; i++) {
    float depth = 0.02 + 0.03 * float(i);
    vec2 q = p - uView * depth;
    float y = crest(i, q.x, e);
    float d = q.y - y;
    if (d < pxw) {
      vec3 col = layerColor(i);
      float under = max(-d, 0.0);
      // Тело слоя: свет у гребня и затухание вглубь; свет края — ярче.
      float body = 0.35 + 1.4 * exp(-under / 0.05) + 0.9 * exp(-under / 0.18);
      vec3 layer = col * body;
      layer += uPal[2] * 1.6 * exp(-under / 0.006) * (0.6 + 0.4 * sin(7.0 * q.x + float(i)));
      c = mix(c, layer, smoothstep(pxw, -pxw, d));
    } else {
      // Тень и ореол над гребнем: слой подсвечивает воздух над собой.
      c += layerColor(i) * 0.22 * exp(-d / 0.05);
    }
  }
  gl_FragColor = vec4(1.0 - exp(-c), 1.0);
}
