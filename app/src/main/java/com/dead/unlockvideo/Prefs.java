package com.dead.unlockvideo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** Настройки обоев. По умолчанию: один раз, только после разблокировки, после блокировки — к началу. */
final class Prefs {
    static final String KEY_LOOP = "loop";
    static final String KEY_PLAY_COUNT = "play_count";
    static final String KEY_END_ON_LAST_FRAME = "end_on_last_frame";
    static final String KEY_SOUND = "sound";
    static final String KEY_AFTER_UNLOCK = "after_unlock";
    static final String KEY_REPLAY = "replay";
    static final String KEY_REPLAY_AFTER_LOCK = "replay_after_lock";
    static final String KEY_REPLAY_ON_HOME = "replay_on_home";
    static final String KEY_REWIND_ON_LOCK = "rewind_on_lock";
    static final String KEY_REWIND_REVERSE = "rewind_reverse";
    static final String KEY_ZOOM_EFFECT = "zoom_effect";
    static final String KEY_VIDEO_NAME = "video_name";
    /** Меняется при каждом новом видео — чтобы не принять реверс от старого. */
    static final String KEY_VIDEO_VERSION = "video_version";
    static final String KEY_REVERSE_READY = "reverse_ready";
    static final String KEY_REVERSE_LAST_MS = "reverse_last_ms";
    static final String KEY_REVERSE_SPLIT_MS = "reverse_split_ms";
    /** Версия способа подготовки реверса; при смене старый ролик готовится заново. */
    static final String KEY_REVERSE_FORMAT = "reverse_format";
    static final int REVERSE_FORMAT = 2;
    /** Видео уже отыграло и повтор выключен — больше не запускать. */
    static final String KEY_FINISHED = "finished";
    /** Меняется при любом изменении настроек; обои перезагружаются по нему. */
    static final String KEY_REVISION = "revision";

    static final int MAX_PLAY_COUNT = 100;

    final boolean loop;
    final int playCount;
    final boolean endOnLastFrame;
    final boolean sound;
    final boolean afterUnlock;
    final boolean replay;
    final boolean replayAfterLock;
    final boolean replayOnHome;
    final boolean rewindOnLock;
    final boolean rewindReverse;
    final boolean zoomEffect;
    final boolean reverseReady;
    final int reverseLastMs;
    final int reverseSplitMs;
    final boolean finished;

    private Prefs(SharedPreferences sp) {
        loop = sp.getBoolean(KEY_LOOP, false);
        playCount = Math.max(1, Math.min(MAX_PLAY_COUNT, sp.getInt(KEY_PLAY_COUNT, 1)));
        endOnLastFrame = sp.getBoolean(KEY_END_ON_LAST_FRAME, true);
        sound = sp.getBoolean(KEY_SOUND, false);
        afterUnlock = sp.getBoolean(KEY_AFTER_UNLOCK, true);
        replay = sp.getBoolean(KEY_REPLAY, false);
        replayAfterLock = sp.getBoolean(KEY_REPLAY_AFTER_LOCK, true);
        replayOnHome = sp.getBoolean(KEY_REPLAY_ON_HOME, false);
        rewindOnLock = sp.getBoolean(KEY_REWIND_ON_LOCK, true);
        rewindReverse = sp.getBoolean(KEY_REWIND_REVERSE, false);
        zoomEffect = sp.getBoolean(KEY_ZOOM_EFFECT, false);
        reverseReady = sp.getBoolean(KEY_REVERSE_READY, false)
                && sp.getInt(KEY_REVERSE_FORMAT, 1) == REVERSE_FORMAT;
        reverseLastMs = sp.getInt(KEY_REVERSE_LAST_MS, 0);
        reverseSplitMs = sp.getInt(KEY_REVERSE_SPLIT_MS, 0);
        finished = sp.getBoolean(KEY_FINISHED, false);
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

    /** Видео «вперёд + назад» для плавного возврата к началу. */
    static File reversibleFile(Context context) {
        return new File(context.getFilesDir(), "wallpaper_video_pingpong.mp4");
    }

    /** Сбрасывает «уже проиграно» и заставляет обои перечитать настройки. */
    static void bumpRevision(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_FINISHED, false)
                .putLong(KEY_REVISION, System.nanoTime())
                .apply();
    }
}
