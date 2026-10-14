// Размытие для матового стекла: уменьшение картинки (уDown: шаг источника) и гаусс по
// направлению (uDir), 9 выборок с линейной фильтрацией (≈ 17 текселей).
precision mediump float;
uniform sampler2D uSrc;
uniform vec2 uSrcSize;      // размер источника, текселей
uniform vec2 uDir;          // (1,0) или (0,1); (0,0) — только уменьшить
uniform vec2 uRes;          // размер цели
void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  vec2 t = 1.0 / uSrcSize;
  if (uDir.x == 0.0 && uDir.y == 0.0) {
    // Уменьшение: четыре билинейные выборки по углам блока.
    vec2 k = uSrcSize / uRes * 0.25;
    gl_FragColor = 0.25 * (texture2D(uSrc, uv + vec2(-k.x, -k.y) * t) + texture2D(uSrc, uv + vec2(k.x, -k.y) * t)
        + texture2D(uSrc, uv + vec2(-k.x, k.y) * t) + texture2D(uSrc, uv + vec2(k.x, k.y) * t));
    return;
  }
  vec2 s = uDir * t;
  vec4 c = 0.1964825501511404 * texture2D(uSrc, uv);
  c += 0.2969069646728344 * (texture2D(uSrc, uv + s * 1.411764705882353) + texture2D(uSrc, uv - s * 1.411764705882353));
  c += 0.09447039785044732 * (texture2D(uSrc, uv + s * 3.2941176470588234) + texture2D(uSrc, uv - s * 3.2941176470588234));
  c += 0.010381362401148057 * (texture2D(uSrc, uv + s * 5.176470588235294) + texture2D(uSrc, uv - s * 5.176470588235294));
  gl_FragColor = c;
}
