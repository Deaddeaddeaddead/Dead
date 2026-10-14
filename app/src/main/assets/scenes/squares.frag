// Сцена «Квадраты»: один стеклянный квадрат поверх того, что позади (проход на квадрат, от
// дальнего к ближнему). Свет квадрата — модель, перенесённая со света нижней фигуры роликов
// Light02/03: гладкая текстура холста квадрата + таблица канта по замкнутому контуру (длина по
// периметру × расстояние до кромки), сумма 10 базисов с весами кадра. Внутри квадрата видно,
// что позади: ослабленное прозрачностью, через матовое (размытое) или рифлёное стекло.
precision highp float;
uniform vec2 uRes;
uniform vec4 uCrop;
uniform sampler2D uSmooth;  // 3×3 слоя по 410.0×410.0 — холст 1640.0×1640.0 пикселей эталона, 1/4
uniform sampler2D uAtlas;   // кант: 1025 × (9 слоёв × 33 узла), последний столбец = первому
uniform vec4 uSm[27];
uniform vec4 uAt[27];
uniform vec3 uBias;
uniform vec3 uABias;
uniform vec3 uBg;           // фон модели в этот момент
uniform mat3 uColor;        // перекраска
uniform sampler2D uPrev;    // то, что позади, — на весь экран
uniform sampler2D uBlur1;   // то же, размыто слабо
uniform sampler2D uBlur2;   // то же, размыто сильно
uniform float uHasPrev;
uniform vec3 uSquare;       // центр в пикселях кадра (y вниз) и масштаб к эталону
uniform vec4 uGlass;        // прозрачность, вид (0 чистое, 1 матовое, 2 рифлёное), сила, яркость
uniform float uDither;

const float FW = 1120.0;
const float FH = 2436.0;
const float CW = 1640.0;
const float CENTER = 820.5;
const float A = 360.0;          // полусторона без скругления
const float RAD = 100.0;        // радиус скругления
const float D = 40.0;
const float AW = 2.0;
const float NS = 1024.0;
const vec2 GRID = vec2(410.0, 410.0);
const vec2 HI_LO = vec2(65280.0, 255.0) / 65535.0;
const float HALF_PI = 1.5707963;
// Свет позади заслоняется у внешнего края квадрата (чтобы свечение не удваивалось).
const float OCC = 0.4;
const float OCC_L = 50.0;
// Рифлёное стекло: ширина ребра (пиксели эталона) и насколько ребро стягивает картинку к
// своей середине (1 — в точку).
const float RIB = 34.0;
const float RIB_PULL = 0.85;

// Расстояние со знаком до скруглённого квадрата (снаружи +) и доля периметра ближайшей точки
// (0 — середина верхней стороны, по часовой; y вниз).
vec2 squareSdf(vec2 q) {
  vec2 a = abs(q);
  vec2 e = a - vec2(A);
  float d = length(max(e, 0.0)) + min(max(e.x, e.y), 0.0) - RAD;
  float arc = HALF_PI * RAD;
  float l;
  if (e.x > 0.0 && e.y > 0.0) l = A + clamp(atan(e.x, e.y), 0.0, HALF_PI) * RAD;
  else if (e.y >= e.x) l = clamp(a.x, 0.0, A);
  else l = A + arc + (A - clamp(a.y, 0.0, A));
  float quarter = 2.0 * A + arc;
  if (q.x >= 0.0) l = q.y < 0.0 ? l : 2.0 * quarter - l;
  else l = q.y < 0.0 ? 4.0 * quarter - l : 2.0 * quarter + l;
  return vec2(d, fract(l / (4.0 * quarter)));
}

float hash(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
  vec2 suv = gl_FragCoord.xy / uRes;
  vec2 f = uCrop.zw + suv * uCrop.xy;
  vec2 P = vec2(f.x * FW, (1.0 - f.y) * FH);
  // Пиксели холста эталона (непрерывные), центр квадрата — CENTER.
  vec2 qc = (P - uSquare.xy) / uSquare.z + vec2(CENTER);
  vec2 sd = squareSdf(qc - vec2(CENTER));
  float d = sd.x;
  vec3 col = uBias;
  if (all(greaterThanEqual(qc, vec2(0.0))) && all(lessThanEqual(qc, vec2(CW)))) {
    vec2 cell = clamp(qc / 4.0, vec2(0.5), GRID - 0.5);
    for (int l = 0; l < 9; l++) {
      vec2 c = vec2(mod(float(l), 3.0), floor(float(l) / 3.0));
      vec4 sm = texture2D(uSmooth, (cell + c * GRID) / (3.0 * GRID));
      col += vec3(dot(uSm[3 * l], sm), dot(uSm[3 * l + 1], sm), dot(uSm[3 * l + 2], sm));
    }
    if (abs(d) < D) {
      float u = sign(d) * log(1.0 + abs(d) / AW) / log(1.0 + D / AW);
      float x = (sd.y * NS + 0.5) / (NS + 1.0);
      float r = (u + 1.0) * 16.0 + 0.5;
      col += uABias;
      for (int l = 0; l < 9; l++) {
        vec4 at = texture2D(uAtlas, vec2(x, (r + float(l) * 33.0) / 297.0));
        col += vec3(dot(uAt[3 * l], at), dot(uAt[3 * l + 1], at), dot(uAt[3 * l + 2], at));
      }
    }
  } else {
    col = uBg;
  }
  vec3 bg = uColor * uBg;
  vec3 v = bg + (uColor * col - bg) * uGlass.w;
  vec3 prev = uHasPrev > 0.5 ? texture2D(uPrev, suv).rgb : bg;
  // Сглаживание кромки: ширина пикселя экрана в пикселях эталона.
  float px = uCrop.x * FW / uRes.x / uSquare.z;
  float alpha = clamp(0.5 - d / px, 0.0, 1.0);
  vec3 outside = bg + (prev - bg) * (1.0 - OCC * exp(-max(d, 0.0) / OCC_L)) + (v - bg);
  vec3 result = outside;
  if (alpha > 0.0) {
    vec3 behind = prev;
    if (uHasPrev > 0.5 && uGlass.z > 0.0) {
      if (uGlass.y > 1.5) {
        // Рифлёное: каждое вертикальное ребро — как цилиндрическая линза: показывает
        // сжатую полоску того, что позади (сдвиг к середине ребра), посередине ребра светлее.
        float local = fract((qc.x - CENTER) / RIB + 0.5) - 0.5;
        float shift = -local * RIB * RIB_PULL * uGlass.z * uSquare.z * uRes.x / (uCrop.x * FW);
        vec2 ruv = suv + vec2(shift / uRes.x, 0.0);
        behind = mix(texture2D(uPrev, ruv).rgb, texture2D(uBlur1, ruv).rgb, 0.25 * uGlass.z);
        behind = bg + (behind - bg) * (1.0 + 0.2 * uGlass.z * (0.5 - 2.0 * abs(local)));
      } else if (uGlass.y > 0.5) {
        // Матовое: позади размыто — тем сильнее, чем больше сила.
        float s = uGlass.z;
        vec3 b1 = texture2D(uBlur1, suv).rgb;
        vec3 b2 = texture2D(uBlur2, suv).rgb;
        behind = s < 0.5 ? mix(prev, b1, 2.0 * s) : mix(b1, b2, 2.0 * s - 1.0);
      }
    }
    vec3 inside = v + uGlass.x * (behind - bg);
    result = mix(outside, inside, alpha);
  }
  gl_FragColor = vec4(result + uDither * (hash(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
}
