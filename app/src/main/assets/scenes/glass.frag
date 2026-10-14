precision highp float;
// Сцена из светящихся форм и стёкол над ними. Формы — скруглённые прямоугольники (капсула и
// круг — частные случаи): центр, поворот, полуразмеры, радиус скругления. Их положение на
// каждый кадр считает приложение (ход при разблокировке), шейдер только рисует.
uniform vec2 uRes;
uniform vec4 uCrop;
// 0 — фон, 1 — свечение внутри форм, 2 — свет вокруг, 3 — подсветка углов.
uniform vec3 uPal[4];
// Стёкла двух цветов (канты, свет в стекле) и их тонировка.
uniform vec3 uGlass[2];
uniform vec2 uTint;
// Сдвиг точки зрения (параллакс), в единицах сцены.
uniform vec2 uView;
// Светящиеся формы: A — центр, угол (рад), яркость; B — полуразмеры, скругление; C — блик:
// сила и угол (свет сильнее в двух противоположных концах, как в оригинале).
uniform int uLightCount;
uniform vec4 uLightA[4];
uniform vec4 uLightB[4];
uniform vec4 uLightC[4];
// Стёкла: A — центр, угол; B — полуразмеры, скругление, ширина скоса кромки; C — цвет (0/1),
// высота купола относительно скоса, матовость (размытие вида сквозь стекло) и сколько света
// под стеклом (считает приложение: от него светится кант).
uniform int uGlassCount;
uniform vec4 uGlassA[6];
uniform vec4 uGlassB[6];
uniform vec4 uGlassC[6];

const float ASPECT = 2.175;
const vec3 LUMA = vec3(0.3, 0.59, 0.11);
// Оптика стекла — как у сцены «Оригинал» (подобрана по ролику): показатель преломления,
// зазор до светящегося слоя, камера; купол — эллипс высотой 0.53 ширины скоса.
const float GLASS_IOR = 1.6732;
const float GLASS_GAP = 0.03;
const float CAMERA_DIST = 1.5457;
const float GLASS_PARALLAX = 0.062;
const float BG_GAIN = 0.85;
const float OUT_K = 0.65;
const float OUT_W = 0.0014;
const float OUT_WI = 0.0003;
const float RAMP0 = -0.0004;
const float RAMP1 = 0.0026;
// Кант: яркость над тёмным и насколько он гаснет над светлым; свечение от него внутрь.
const float RIM_BASE = 2.2;
const float RIM_OVER_LIGHT = 0.6;
const float GLOW_GAIN = 1.25;

vec3 tint(vec3 base, vec3 toward, float w) {
  vec3 c = mix(base, toward, w);
  float byGreen = base.g / max(c.g, 1e-4);
  float byLuma = dot(base, LUMA) / max(dot(c, LUMA), 1e-4);
  return c * mix(byLuma, byGreen, smoothstep(0.05, 0.15, min(base.g, c.g)));
}

vec2 rotate(vec2 v, float a) {
  float c = cos(a);
  float s = sin(a);
  return vec2(c * v.x + s * v.y, -s * v.x + c * v.y);
}

// Расстояние до скруглённого прямоугольника (полуразмеры h, скругление r) в его координатах.
float boxSdf(vec2 q, vec2 h, float r) {
  vec2 d = abs(q) - (h - r);
  return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
}

// Направление наружу от ближайшей точки края (градиент расстояния) в координатах формы.
vec2 boxNormal(vec2 q, vec2 h, float r) {
  vec2 d = abs(q) - (h - r);
  vec2 n;
  if (d.x > 0.0 && d.y > 0.0) n = normalize(d);
  else if (d.x > d.y) n = vec2(1.0, 0.0);
  else n = vec2(0.0, 1.0);
  return n * sign(q + 1e-6);
}

vec3 background(vec2 p) {
  float corner = exp(-length(p - vec2(-0.55, 1.15)) / 0.45)
      + 0.9 * exp(-length(p - vec2(0.55, -1.15)) / 0.45);
  return uPal[0] * 0.11 + uPal[3] * 0.3 * corner;
}

// Ореол на расстоянии od от края и свечение на глубине dep внутри формы с полутолщиной inr —
// формулы подобраны по средним профилям ролика.
float haloProfile(float od) {
  float t = od / 0.085;
  return 0.795 * exp(-t * t) + 0.734 * exp(-od / 0.15);
}

float insideProfile(float dep, float inr) {
  return 0.636 + 6.013 * (exp(-dep / 0.0375) + exp(-max(2.0 * inr - dep, 0.0) / 0.0375))
      - 2.292 * exp(-dep / 0.009);
}

// Светящийся слой: фон, ореолы и сами формы. glass = 1 — вид сквозь стекло.
vec3 lightLayer(vec2 p, float pxw, float glass) {
  vec3 haloColor = tint(uPal[2], uPal[1], mix(0.28, 0.6, glass));
  vec3 insideColor = tint(uPal[1], uPal[2], 0.14 * (1.0 - glass));
  float halo = 0.0;
  float inside = 0.0;
  float cover = 0.0;
  for (int i = 0; i < 4; i++) {
    if (i >= uLightCount) break;
    vec4 a = uLightA[i];
    vec4 b = uLightB[i];
    vec4 c = uLightC[i];
    vec2 q = rotate(p - a.xy, a.z);
    float d = boxSdf(q, b.xy, b.z);
    // Блик: cos 2(θ − угол) по направлению от центра (в долях размера формы); к центру
    // формы плавно сходит на нет — без точки-излома.
    vec2 u = q / b.xy;
    float sheen = 1.0 + c.x * (cos(2.0 * c.y) * (u.x * u.x - u.y * u.y)
        + sin(2.0 * c.y) * 2.0 * u.x * u.y) / max(dot(u, u), 1.0);
    if (d > -pxw) halo += a.w * sheen * haloProfile(max(d, 0.0));
    if (d < pxw) {
      float k = smoothstep(pxw, -pxw, d);
      inside = mix(inside, a.w * sheen * insideProfile(max(-d, 0.0), min(b.x, b.y)), k);
      cover = max(cover, k);
    }
  }
  vec3 outside = background(p) * mix(1.0, BG_GAIN, glass) + haloColor * halo * mix(1.0, 0.88, glass);
  return mix(outside, insideColor * inside, cover);
}

// Преломление в куполе: x — доля от края скоса к вершине (0 — вершина, 1 — край), ar — наклон
// луча камеры вдоль нормали, h — высота купола. Сдвиг вдоль нормали и синус к предельному.
vec3 domeOptics(float x, float ar, float bevel, float h) {
  x = min(x, 0.9995);
  float sq = sqrt(1.0 - x * x);
  float zs = h * sq;
  vec2 n = normalize(vec2(h * x / (bevel * sq), 1.0));
  vec2 dir = normalize(vec2(ar, -1.0));
  float eta = 1.0 / GLASS_IOR;
  float cosi = -dot(dir, n);
  float a = eta * cosi - sqrt(max(1.0 - eta * eta * (1.0 - cosi * cosi), 0.0));
  vec2 t = eta * dir + a * n;
  float sinIn = t.x / length(t);
  float sinOut = clamp(sinIn * GLASS_IOR, -0.9999, 0.9999);
  float shift = zs * t.x / -t.y
      + clamp(GLASS_GAP * sinOut / sqrt(1.0 - sinOut * sinOut), -0.08, 0.08)
      - (zs + GLASS_GAP) * ar;
  return vec3(shift, abs(sinIn) / eta, zs);
}

float slabShift(float zs, float x) {
  float st = x / sqrt(1.0 + x * x) / GLASS_IOR;
  return zs * (st / sqrt(1.0 - st * st) - x);
}

void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  vec2 f = uCrop.zw + uv * uCrop.xy;
  vec2 p = (f - 0.5) * vec2(1.0, ASPECT);
  float pxw = uCrop.x / uRes.x;

  // Верхнее стекло, под которым точка (последнее в списке — сверху), и ближайшая кромка.
  int hit = -1;
  float hitD = 1.0;
  float edge = 1.0;
  for (int i = 0; i < 6; i++) {
    if (i >= uGlassCount) break;
    vec2 q = rotate(p - uGlassA[i].xy + uView * GLASS_PARALLAX, uGlassA[i].z);
    float d = boxSdf(q, uGlassB[i].xy, uGlassB[i].z);
    if (abs(d) < abs(edge)) edge = d;
    if (d < pxw) {
      hit = i;
      hitD = d;
    }
  }

  vec3 c = lightLayer(p, pxw, 0.0);
  if (hit >= 0) {
    vec4 ga = vec4(0.0);
    vec4 gb = vec4(0.0);
    vec4 gc = vec4(0.0);
    for (int i = 0; i < 6; i++) {
      if (i == hit) {
        ga = uGlassA[i];
        gb = uGlassB[i];
        gc = uGlassC[i];
      }
    }
    vec2 center = ga.xy - uView * GLASS_PARALLAX;
    vec2 q = rotate(p - center, ga.z);
    float s = max(-hitD, 0.0);
    float bevel = gb.w;
    float h = gc.y * bevel;
    // Нормаль кромки — в координаты экрана.
    vec2 radial = rotate(boxNormal(q, gb.xy, gb.z), -ga.z);
    vec2 ray = (p - uView) / CAMERA_DIST;
    float ar = dot(ray, radial);
    float x = 1.0 - min(s, bevel) / bevel;
    vec3 o = domeOptics(x, ar, bevel, h);
    float x1 = 1.0 - min(s + 0.001, bevel) / bevel;
    float v1 = domeOptics(x1, ar, bevel, h).y;
    // Изгиб купола — вдоль нормали, плоская часть — по всему наклону луча.
    float am = max(length(ray), 1e-6);
    vec2 qp = p + radial * (o.x - slabShift(o.z, ar)) + ray * (slabShift(o.z, am) / am);
    float tirB = (1.0 - o.y) / max((o.y - v1) / 0.001, 1e-3);
    float tir = 1.0 - smoothstep(0.0015 - 0.004, 0.0015 + 0.004, tirB);
    if (s > bevel) tir = 0.0;

    vec3 glassColor = gc.x > 0.5 ? uGlass[1] : uGlass[0];
    float tintK = gc.x > 0.5 ? uTint.y : uTint.x;
    // Матовое стекло — края светящихся форм сквозь него мягче.
    vec3 content = lightLayer(qp, max(pxw, gc.z), 1.0);
    vec3 tinted = glassColor * (dot(content, LUMA) / max(dot(glassColor, LUMA), 1e-4));
    content = mix(content, tinted, tintK);
    // Кант светит светом, идущим по стеклу: ярче над тёмным, тусклее над светлым.
    float under = gc.w;
    float local = dot(content, LUMA) / max(dot(uPal[1], LUMA), 1e-4);
    float rimV = RIM_BASE * (0.35 + 0.25 * min(under, 3.0)) / (1.0 + RIM_OVER_LIGHT * local);
    float rimK = smoothstep(RAMP0, RAMP1, s);
    float sT = max(s - 0.004, 0.0);
    vec3 glass = mix(content, glassColor * rimV * rimK, tir)
        + glassColor * (1.0 - tir) * GLOW_GAIN * rimK * rimV * 0.35
        * (0.51 * exp(-sT / 0.004) + 0.25 * exp(-sT / 0.016));
    c = mix(c, glass, smoothstep(pxw, -pxw, hitD));
  }
  float ed = edge / (edge > 0.0 ? OUT_W : OUT_WI);
  c *= 1.0 - OUT_K * exp(-ed * ed);
  gl_FragColor = vec4(1.0 - exp(-c), 1.0);
}
