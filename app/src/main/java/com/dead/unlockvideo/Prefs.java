package com.dead.unlockvideo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Настройки обоев. По умолчанию: видео проигрывается один раз при каждой разблокировке,
 * замирает на последнем кадре, после блокировки возвращается к началу.
 */
final class Prefs {
    // Когда запускать
    static final String KEY_START_ON_UNLOCK = "start_on_unlock";
    static final String KEY_START_ON_SCREEN_ON = "start_on_screen_on";
    static final String KEY_UNLOCK_ON_LOCKSCREEN = "unlock_on_lockscreen";
    static final String KEY_START_ON_HOME = "start_on_home";
    static final String KEY_START_IN_RECENTS = "start_in_recents";
    static final String KEY_ONCE = "once";
    // Как проигрывать
    static final String KEY_PLAYS = "plays";
    static final String KEY_END_ON_LAST_FRAME = "end_on_last_frame";
    // При блокировке экрана
    static final String KEY_LOCK_ACTION = "lock_action";
    // Эффекты
    static final String KEY_UNLOCK_ZOOM = "unlock_zoom";
    static final String KEY_UNLOCK_ZOOM_AMOUNT = "unlock_zoom_amount";
    static final String KEY_PARALLAX = "parallax";
    static final String KEY_ZOOM_SYNC = "zoom_sync";
    static final String KEY_ZOOM_ON_DISMISS = "zoom_on_dismiss";
    /** Устаревший переключатель; читается только для переноса значения. */
    static final String KEY_RESET_ON_SCREEN_OFF = "reset_on_screen_off";
    static final String KEY_SCREEN_OFF_POSE = "screen_off_pose";
    static final String KEY_ZOOM_DURATION = "zoom_duration";
    // Дополнительно
    static final String KEY_SOUND = "sound";
    // Источник: видео или сцена «Стекло»
    static final String KEY_SOURCE = "source";
    static final String KEY_GLASS_PALETTE = "glass_palette";
    /** Какая сцена в режиме «Сцена»: ScenePresets.*. */
    static final String KEY_SCENE = "scene";
    /** Свои цвета стекла (ARGB) по частям сцены — GlassScene.COLOR_*. */
    static final String[] KEY_GLASS_COLORS = {
            "glass_color_background", "glass_color_glow", "glass_color_light",
            "glass_color_corners", "glass_color_glass_top", "glass_color_glass_bottom",
    };
    /** Тонировка стёкол, %. */
    static final String KEY_GLASS_TINT_TOP = "glass_tint_top";
    static final String KEY_GLASS_TINT_BOTTOM = "glass_tint_bottom";
    static final String KEY_GLASS_BRIGHTNESS = "glass_brightness";
    /** Устаревшие (оттенки и насыщенность); читаются только для переноса в свои цвета. */
    static final String KEY_GLASS_GLOW_HUE = "glass_glow_hue";
    static final String KEY_GLASS_HALO_HUE = "glass_halo_hue";
    static final String KEY_GLASS_SATURATION = "glass_saturation";
    static final int MIN_GLASS_BRIGHTNESS = 50;
    static final int MAX_GLASS_BRIGHTNESS = 150;
    static final String KEY_GLASS_DURATION = "glass_duration";
    /** Плавный старт и плавная остановка анимации сцены, % (Easing). */
    static final String KEY_EASE_START = "ease_start";
    static final String KEY_EASE_END = "ease_end";
    /** Прежняя общая плавность — по ней старт и остановка по умолчанию. */
    private static final String KEY_SCENE_SOFTNESS = "scene_softness";
    /** Цвет сцен «Ленты» и «Квадраты»: LightColors.PALETTE_*. */
    static final String KEY_LIGHT_PALETTE = "light_palette";
    /** Свои цвета «Лент» и «Квадратов» (ARGB): вместо светло-бирюзового и синего. */
    static final String[] KEY_LIGHT_COLORS = {"light_color_1", "light_color_2"};
    static final String KEY_LIGHT_BRIGHTNESS = "light_brightness";
    /** Квадраты: наложение, % стороны; у каждого (сверху вниз) — прозрачность, вид стекла,
     * его сила и глубина, %. */
    static final String KEY_SQUARES_OVERLAP = "squares_overlap";
    static final String[] KEY_SQUARE_ALPHA = {"square_alpha_1", "square_alpha_2", "square_alpha_3"};
    static final String[] KEY_SQUARE_GLASS = {"square_glass_1", "square_glass_2", "square_glass_3"};
    static final String[] KEY_SQUARE_STRENGTH = {
            "square_strength_1", "square_strength_2", "square_strength_3"};
    static final String[] KEY_SQUARE_DEPTH = {"square_depth_1", "square_depth_2", "square_depth_3"};
    /** Свой цвет у квадрата (иначе — цвет сцены) и два его цвета. */
    static final String[] KEY_SQUARE_OWN_COLOR = {
            "square_own_color_1", "square_own_color_2", "square_own_color_3"};
    static final String[][] KEY_SQUARE_COLORS = {
            {"square_color_1_1", "square_color_1_2"}, {"square_color_2_1", "square_color_2_2"},
            {"square_color_3_1", "square_color_3_2"}};

    static final String KEY_VIDEO_NAME = "video_name";
    /** Меняется при каждом новом видео — чтобы не принять реверс от старого. */
    static final String KEY_VIDEO_VERSION = "video_version";
    static final String KEY_REVERSE_READY = "reverse_ready";
    static final String KEY_REVERSE_LAST_MS = "reverse_last_ms";
    /** Версия способа подготовки реверса; при смене старый ролик готовится заново. */
    static final String KEY_REVERSE_FORMAT = "reverse_format";
    static final int REVERSE_FORMAT = 5;
    /** Видео уже отыграло в режиме «Только один раз» — больше не запускать. */
    static final String KEY_FINISHED = "finished";
    /** Меняется при изменении настроек; обои перечитывают их, не сбрасывая состояние. */
    static final String KEY_REVISION = "revision";
    /** Меняется по «Проиграть сейчас» и при новом видео: обои запускают видео с начала. */
    static final String KEY_PLAY_NOW = "play_now";

    /** plays == INFINITE — бесконечный повтор. */
    static final int INFINITE = 0;
    static final int MAX_PLAYS = 20;

    static final int LOCK_KEEP = 0;
    static final int LOCK_REWIND = 1;
    static final int LOCK_REVERSE = 2;

    static final int ZOOM_NONE = 0;
    /** При разблокировке видео плавно увеличивается. */
    static final int ZOOM_IN = 1;
    /** На экране блокировки видео увеличено, при разблокировке возвращается к обычному. */
    static final int ZOOM_OUT = 2;
    static final int MAX_ZOOM_AMOUNT = 25;

    /** Какой масштаб ставить при выключении экрана (виден на AOD). */
    static final int POSE_KEEP = 0;
    static final int POSE_LOCK = 1;
    static final int POSE_HOME = 2;
    static final int MAX_PARALLAX = 100;
    /** Длительность масштаба — в десятых долях секунды. */
    static final int MIN_ZOOM_DURATION = 1;
    static final int MAX_ZOOM_DURATION = 40;

    static final int SOURCE_VIDEO = 0;
    /** Сцена «Стекло», нарисованная кодом. */
    static final int SOURCE_GLASS = 1;
    /** Длительность анимации стекла — в десятых долях секунды. */
    static final int MIN_GLASS_DURATION = 5;
    static final int MAX_GLASS_DURATION = 60;

    final boolean startOnUnlock;
    final boolean startOnScreenOn;
    /** Считать разблокировкой подтверждение личности прямо на экране блокировки (лицом). */
    final boolean unlockOnLockscreen;
    final boolean startOnHome;
    final boolean startInRecents;
    final boolean once;
    final int plays;
    final boolean endOnLastFrame;
    final int lockAction;
    final int unlockZoom;
    /** Сила масштаба при разблокировке, в процентах. */
    final int unlockZoomAmount;
    /** Масштаб длится столько же, сколько играет видео. */
    final boolean zoomSync;
    /** Масштаб — только когда убирается экран блокировки, а не при распознавании лица. */
    final boolean zoomOnDismiss;
    /** Какой масштаб ставить при выключении экрана: POSE_*. */
    final int screenOffPose;
    /** Длительность масштаба, мс (когда не «как у видео» или видео не играет). */
    final int zoomDurationMs;
    /** Сила параллакса, 0 — выключен. */
    final int parallax;
    final boolean sound;
    /** SOURCE_*. */
    final int source;
    /** Сцена: ScenePresets.*. */
    final int scene;
    /** Палитра стекла: GlassScene.PALETTE_*. */
    final int glassPalette;
    /** Свои цвета стекла (GlassScene.PALETTE_CUSTOM): ARGB по GlassScene.COLOR_*. */
    final int[] glassColors;
    /** Тонировка верхнего и нижнего стекла, %. */
    final int glassTintTop;
    final int glassTintBottom;
    /** Общая яркость своих цветов, %. */
    final int glassBrightness;
    /** Длительность анимации стекла, мс. */
    final int glassDurationMs;
    /** Плавный старт и плавная остановка анимации сцены, 0..100 %. */
    final int easeStart;
    final int easeEnd;
    /** «Ленты» и «Квадраты»: цвет (LightColors.PALETTE_*), свои цвета, их яркость, %. */
    final int lightPalette;
    final int[] lightColors;
    final int lightBrightness;
    /** Квадраты: наложение и у каждого — прозрачность, стекло, сила, глубина (%). */
    final int squaresOverlap;
    final int[] squareAlpha;
    final int[] squareGlass;
    final int[] squareStrength;
    final int[] squareDepth;
    final boolean[] squareOwnColor;
    final int[][] squareColors;
    final boolean reverseReady;
    final int reverseLastMs;
    final boolean finished;

    private Prefs(SharedPreferences sp) {
        startOnUnlock = sp.getBoolean(KEY_START_ON_UNLOCK, true);
        startOnScreenOn = sp.getBoolean(KEY_START_ON_SCREEN_ON, false);
        unlockOnLockscreen = sp.getBoolean(KEY_UNLOCK_ON_LOCKSCREEN, true);
        startOnHome = sp.getBoolean(KEY_START_ON_HOME, false);
        startInRecents = sp.getBoolean(KEY_START_IN_RECENTS, false);
        once = sp.getBoolean(KEY_ONCE, false);
        int p = sp.getInt(KEY_PLAYS, 1);
        plays = p == INFINITE ? INFINITE : Math.max(1, Math.min(MAX_PLAYS, p));
        endOnLastFrame = sp.getBoolean(KEY_END_ON_LAST_FRAME, true);
        int lock = sp.getInt(KEY_LOCK_ACTION, LOCK_REWIND);
        lockAction = lock >= LOCK_KEEP && lock <= LOCK_REVERSE ? lock : LOCK_REWIND;
        int zoom = sp.getInt(KEY_UNLOCK_ZOOM, ZOOM_NONE);
        unlockZoom = zoom >= ZOOM_NONE && zoom <= ZOOM_OUT ? zoom : ZOOM_NONE;
        unlockZoomAmount = Math.max(1, Math.min(MAX_ZOOM_AMOUNT,
                sp.getInt(KEY_UNLOCK_ZOOM_AMOUNT, 8)));
        parallax = Math.max(0, Math.min(MAX_PARALLAX, sp.getInt(KEY_PARALLAX, 0)));
        zoomSync = sp.getBoolean(KEY_ZOOM_SYNC, true);
        zoomOnDismiss = sp.getBoolean(KEY_ZOOM_ON_DISMISS, false);
        int pose = sp.getInt(KEY_SCREEN_OFF_POSE,
                sp.getBoolean(KEY_RESET_ON_SCREEN_OFF, true) ? POSE_LOCK : POSE_KEEP);
        screenOffPose = pose >= POSE_KEEP && pose <= POSE_HOME ? pose : POSE_LOCK;
        zoomDurationMs = 100 * Math.max(MIN_ZOOM_DURATION, Math.min(MAX_ZOOM_DURATION,
                sp.getInt(KEY_ZOOM_DURATION, 7)));
        sound = sp.getBoolean(KEY_SOUND, false);
        source = sp.getInt(KEY_SOURCE, SOURCE_VIDEO) == SOURCE_GLASS ? SOURCE_GLASS : SOURCE_VIDEO;
        int sceneId = sp.getInt(KEY_SCENE, ScenePresets.ORIGINAL);
        // Были «Ленты» в фиолетовом отдельной сценой — теперь это «Ленты» с фиолетовым цветом.
        boolean oldViolet = sceneId == ScenePresets.RIBBONS_VIOLET_OLD;
        if (oldViolet) sceneId = ScenePresets.RIBBONS;
        scene = ScenePresets.exists(sceneId) ? sceneId : ScenePresets.ORIGINAL;
        glassPalette = Math.max(GlassScene.PALETTE_AMBER, Math.min(GlassScene.PALETTE_CUSTOM,
                sp.getInt(KEY_GLASS_PALETTE, GlassScene.PALETTE_AMBER)));
        // Не заданные цвета — из прежних настроек оттенков (по умолчанию — «Янтарь»).
        int[] fallback = GlassScene.defaultColors(
                Math.floorMod(sp.getInt(KEY_GLASS_GLOW_HUE, GlassScene.DEFAULT_GLOW_HUE), 360),
                Math.floorMod(sp.getInt(KEY_GLASS_HALO_HUE, GlassScene.DEFAULT_HALO_HUE), 360),
                Math.max(0, Math.min(100, sp.getInt(KEY_GLASS_SATURATION, 100))));
        glassColors = new int[GlassScene.COLOR_COUNT];
        for (int i = 0; i < glassColors.length; i++) {
            glassColors[i] = 0xff000000 | sp.getInt(KEY_GLASS_COLORS[i], fallback[i]);
        }
        glassTintTop = Math.max(0, Math.min(100, sp.getInt(KEY_GLASS_TINT_TOP, 0)));
        glassTintBottom = Math.max(0, Math.min(100, sp.getInt(KEY_GLASS_TINT_BOTTOM, 0)));
        glassBrightness = Math.max(MIN_GLASS_BRIGHTNESS, Math.min(MAX_GLASS_BRIGHTNESS,
                sp.getInt(KEY_GLASS_BRIGHTNESS, 100)));
        glassDurationMs = 100 * Math.max(MIN_GLASS_DURATION, Math.min(MAX_GLASS_DURATION,
                sp.getInt(KEY_GLASS_DURATION, 20)));
        int softness = sp.getInt(KEY_SCENE_SOFTNESS, Easing.DEFAULT_SOFTNESS);
        easeStart = clamp100(sp.getInt(KEY_EASE_START, softness));
        easeEnd = clamp100(sp.getInt(KEY_EASE_END, softness));
        lightPalette = Math.max(0, Math.min(LightColors.PALETTE_CUSTOM, sp.getInt(KEY_LIGHT_PALETTE,
                oldViolet ? LightColors.PALETTE_VIOLET : LightColors.PALETTE_TEAL)));
        lightColors = new int[KEY_LIGHT_COLORS.length];
        for (int i = 0; i < lightColors.length; i++) {
            lightColors[i] = 0xff000000
                    | sp.getInt(KEY_LIGHT_COLORS[i], LightColors.DEFAULT_COLORS[i]);
        }
        lightBrightness = Math.max(MIN_GLASS_BRIGHTNESS, Math.min(MAX_GLASS_BRIGHTNESS,
                sp.getInt(KEY_LIGHT_BRIGHTNESS, 100)));
        squaresOverlap = Math.max(0, Math.min(LightColors.MAX_OVERLAP,
                sp.getInt(KEY_SQUARES_OVERLAP, LightColors.DEFAULT_OVERLAP)));
        squareAlpha = new int[LightColors.SQUARES];
        squareGlass = new int[LightColors.SQUARES];
        squareStrength = new int[LightColors.SQUARES];
        squareDepth = new int[LightColors.SQUARES];
        squareOwnColor = new boolean[LightColors.SQUARES];
        squareColors = new int[LightColors.SQUARES][2];
        for (int j = 0; j < LightColors.SQUARES; j++) {
            squareAlpha[j] = clamp100(sp.getInt(KEY_SQUARE_ALPHA[j], LightColors.DEFAULT_ALPHA[j]));
            squareGlass[j] = Math.max(0, Math.min(LightColors.GLASS_REEDED,
                    sp.getInt(KEY_SQUARE_GLASS[j], LightColors.DEFAULT_GLASS[j])));
            squareStrength[j] = clamp100(sp.getInt(KEY_SQUARE_STRENGTH[j],
                    LightColors.DEFAULT_STRENGTH[j]));
            squareDepth[j] = clamp100(sp.getInt(KEY_SQUARE_DEPTH[j], LightColors.DEFAULT_DEPTH[j]));
            squareOwnColor[j] = sp.getBoolean(KEY_SQUARE_OWN_COLOR[j], false);
            for (int i = 0; i < 2; i++) {
                squareColors[j][i] = 0xff000000
                        | sp.getInt(KEY_SQUARE_COLORS[j][i], LightColors.DEFAULT_COLORS[i]);
            }
        }
        reverseReady = sp.getBoolean(KEY_REVERSE_READY, false)
                && sp.getInt(KEY_REVERSE_FORMAT, 1) == REVERSE_FORMAT;
        reverseLastMs = sp.getInt(KEY_REVERSE_LAST_MS, 0);
        finished = sp.getBoolean(KEY_FINISHED, false);
    }

    private static int clamp100(int value) {
        return Math.max(0, Math.min(100, value));
    }

    boolean infinite() {
        return plays == INFINITE;
    }

    boolean glass() {
        return source == SOURCE_GLASS;
    }

    /** Нужно ли обратное видео: реверс выбран и повтор не бесконечный. */
    boolean wantsReverse() {
        return lockAction == LOCK_REVERSE && !infinite();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static Prefs load(Context context) {
        return new Prefs(prefs(context));
    }

    static File videoFile(Context context) {
        return new File(context.getFilesDir(), "wallpaper_video");
    }

    /** Видео задом наперёд для плавного возврата к началу. */
    static File reverseFile(Context context) {
        return new File(context.getFilesDir(), "wallpaper_video_reverse.mp4");
    }

    /** Файл из старых версий («вперёд + назад»), больше не нужен. */
    static File legacyReverseFile(Context context) {
        return new File(context.getFilesDir(), "wallpaper_video_pingpong.mp4");
    }

    /** Обои перечитают настройки, не трогая текущее воспроизведение. */
    static void bumpRevision(Context context) {
        prefs(context).edit().putLong(KEY_REVISION, System.nanoTime()).apply();
    }

    /** Сбросить «уже проиграно» и запустить видео с начала. */
    static void playNow(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_FINISHED, false)
                .putLong(KEY_PLAY_NOW, System.nanoTime())
                .apply();
    }
}
