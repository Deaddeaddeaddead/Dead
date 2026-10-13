package com.dead.unlockvideo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** Настройки обоев. Значения по умолчанию: один раз, без возврата, только после разблокировки. */
final class Prefs {
    static final String KEY_LOOP = "loop";
    static final String KEY_PLAY_COUNT = "play_count";
    static final String KEY_END_ON_LAST_FRAME = "end_on_last_frame";
    static final String KEY_SOUND = "sound";
    static final String KEY_AFTER_UNLOCK = "after_unlock";
    static final String KEY_REPLAY = "replay";
    static final String KEY_REPLAY_AFTER_LOCK = "replay_after_lock";
    static final String KEY_REPLAY_ON_HOME = "replay_on_home";
    static final String KEY_VIDEO_NAME = "video_name";
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

    /** Сбрасывает «уже проиграно» и заставляет обои перечитать настройки. */
    static void bumpRevision(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_FINISHED, false)
                .putLong(KEY_REVISION, System.nanoTime())
                .apply();
    }
}
