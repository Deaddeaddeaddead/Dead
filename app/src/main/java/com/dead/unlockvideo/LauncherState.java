package com.dead.unlockvideo;

import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Что сейчас на экране: приложение, рабочий стол или список недавних приложений.
 * Заполняет служба специальных возможностей; обои подписываются на изменения.
 * Всё в одном процессе, вызовы — на главном потоке.
 */
final class LauncherState {
    static final int UNKNOWN = 0;
    static final int APP = 1;
    static final int HOME = 2;
    static final int RECENTS = 3;

    interface Listener {
        void onLauncherState(int state);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean serviceRunning;
    private static volatile int state = UNKNOWN;
    private static volatile long changedAt;

    private LauncherState() {
    }

    static boolean serviceRunning() {
        return serviceRunning;
    }

    static void setServiceRunning(boolean running) {
        serviceRunning = running;
        if (!running) state = UNKNOWN;
    }

    static int state() {
        return state;
    }

    static long changedAt() {
        return changedAt;
    }

    static void set(int newState) {
        if (newState == state) return;
        state = newState;
        changedAt = SystemClock.uptimeMillis();
        for (Listener listener : LISTENERS) listener.onLauncherState(newState);
    }

    static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static String name(int state) {
        switch (state) {
            case APP: return "приложение";
            case HOME: return "рабочий стол";
            case RECENTS: return "список недавних";
            default: return "неизвестно";
        }
    }
}
