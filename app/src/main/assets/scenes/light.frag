// Сцена «Свет»: снятая с ролика. Свет = Σ по 10 картинкам-базисам × вес кадра (униформы).
// Каждая картинка — гладкая текстура 1/4 + таблицы вдоль кривых (кантов): по длине кривой ×
// по расстоянию до неё (узлы гуще у самой кромки), так что кромки чёткие при любом разрешении.
precision highp float;
uniform vec2 uRes;
uniform vec4 uCrop;
uniform sampler2D uField;   // 2×2 куска по 280×609: RG — расстояние (16 бит), BA — длина (16 бит)
uniform sampler2D uMask;    // 280×609: канал на кусок — рядом ли кривая
uniform sampler2D uSmooth;  // 3×3 слоя по 280×609
uniform sampler2D uAtlas;   // 1024 × (4 куска × 9 слоёв × 33 узла)
uniform vec4 uSm[27];       // вклад слоя гладкой части в цвет: [3·слой + канал]
uniform vec4 uAt[27];       // то же для таблиц
uniform vec3 uBias;         // постоянная гладкой части
uniform vec3 uABias;        // постоянная таблиц (на сумму весов кусков)

const float D = 40.0;
const float DCL = 52.0;
const float AW = 2.0;
const vec2 GRID = vec2(280.0, 609.0);
const vec2 HI_LO = vec2(65280.0, 255.0) / 65535.0;

vec2 cellUv;
vec4 acc[9];
float wsum;

void addPiece(vec2 cell, float base, float lo, float hi, float f0, float f1, float dir) {
  vec4 t = texture2D(uField, (cellUv + cell * GRID) / (2.0 * GRID));
  float d = dot(t.rg, HI_LO) * (2.0 * DCL) - DCL;
  if (abs(d) >= D) return;
  float s = dot(t.ba, HI_LO) * (hi - lo) + lo;
  float w = 1.0;
  if (dir != 0.0) {
    float f = smoothstep(f0, f1, s);
    w = dir > 0.0 ? f : 1.0 - f;
    if (w <= 0.0) return;
  }
  float u = sign(d) * log(1.0 + abs(d) / AW) / log(1.0 + D / AW);
  float x = ((s - lo) / (hi - lo) * 1023.0 + 0.5) / 1024.0;
  float r = base + (u + 1.0) * 16.0 + 0.5;
  for (int l = 0; l < 9; l++) {
    acc[l] += w * texture2D(uAtlas, vec2(x, (r + float(l) * 33.0) / 1188.0));
  }
  wsum += w;
}

float hash(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  vec2 f = uCrop.zw + uv * uCrop.xy;
  // Данные лежат строками сверху вниз.
  vec2 p = vec2(f.x, 1.0 - f.y);
  cellUv = clamp(p * GRID, vec2(0.5), GRID - 0.5);
  for (int l = 0; l < 9; l++) acc[l] = vec4(0.0);
  wsum = 0.0;
  vec4 m = texture2D(uMask, cellUv / GRID);
  if (m.r > 0.5) addPiece(vec2(0.0, 0.0), 0.0, -40.0, 2876.998615558602, 0.0, 0.0, 0.0);
  if (m.g > 0.5) addPiece(vec2(1.0, 0.0), 297.0, -40.0, 2448.9998876003624, 2280.0, 2380.0, -1.0);
  if (m.b > 0.5) addPiece(vec2(0.0, 1.0), 594.0, 2210.0001037765924, 3556.999640743317, 2280.0, 2380.0, 1.0);
  if (m.a > 0.5) addPiece(vec2(1.0, 1.0), 891.0, -40.0, 1941.0006667562423, 0.0, 0.0, 0.0);
  vec3 col = uBias + wsum * uABias;
  for (int l = 0; l < 9; l++) {
    vec2 cell = vec2(mod(float(l), 3.0), floor(float(l) / 3.0));
    vec4 sm = texture2D(uSmooth, (cellUv + cell * GRID) / (3.0 * GRID));
    col += vec3(dot(uSm[3 * l], sm), dot(uSm[3 * l + 1], sm), dot(uSm[3 * l + 2], sm));
    col += vec3(dot(uAt[3 * l], acc[l]), dot(uAt[3 * l + 1], acc[l]), dot(uAt[3 * l + 2], acc[l]));
  }
  gl_FragColor = vec4(col + (hash(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
}
