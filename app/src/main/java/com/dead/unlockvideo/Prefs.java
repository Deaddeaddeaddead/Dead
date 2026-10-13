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
    static final String KEY_START_ON_HOME = "start_on_home";
    static final String KEY_ONCE = "once";
    // Как проигрывать
    static final String KEY_PLAYS = "plays";
    static final String KEY_END_ON_LAST_FRAME = "end_on_last_frame";
    // При блокировке экрана
    static final String KEY_LOCK_ACTION = "lock_action";
    // Дополнительно
    static final String KEY_SOUND = "sound";

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

    final boolean startOnUnlock;
    final boolean startOnScreenOn;
    final boolean startOnHome;
    final boolean once;
    final int plays;
    final boolean endOnLastFrame;
    final int lockAction;
    final boolean sound;
    final boolean reverseReady;
    final int reverseLastMs;
    final boolean finished;

    private Prefs(SharedPreferences sp) {
        startOnUnlock = sp.getBoolean(KEY_START_ON_UNLOCK, true);
        startOnScreenOn = sp.getBoolean(KEY_START_ON_SCREEN_ON, false);
        startOnHome = sp.getBoolean(KEY_START_ON_HOME, false);
        once = sp.getBoolean(KEY_ONCE, false);
        int p = sp.getInt(KEY_PLAYS, 1);
        plays = p == INFINITE ? INFINITE : Math.max(1, Math.min(MAX_PLAYS, p));
        endOnLastFrame = sp.getBoolean(KEY_END_ON_LAST_FRAME, true);
        int lock = sp.getInt(KEY_LOCK_ACTION, LOCK_REWIND);
        lockAction = lock >= LOCK_KEEP && lock <= LOCK_REVERSE ? lock : LOCK_REWIND;
        sound = sp.getBoolean(KEY_SOUND, false);
        reverseReady = sp.getBoolean(KEY_REVERSE_READY, false)
                && sp.getInt(KEY_REVERSE_FORMAT, 1) == REVERSE_FORMAT;
        reverseLastMs = sp.getInt(KEY_REVERSE_LAST_MS, 0);
        finished = sp.getBoolean(KEY_FINISHED, false);
    }

    boolean infinite() {
        return plays == INFINITE;
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
