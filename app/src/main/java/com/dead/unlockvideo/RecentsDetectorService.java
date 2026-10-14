package com.dead.unlockvideo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Служба специальных возможностей: определяет, что на экране — приложение, рабочий стол
 * или список недавних приложений. Живые обои сами этого узнать не могут: One UI не
 * сообщает им ничего, кроме «обои видны». Служба читает только экран лаунчера.
 */
public class RecentsDetectorService extends AccessibilityService {
    /** Признаки списка недавних приложений в интерфейсе лаунчера. */
    private static final String[] RECENTS_TEXTS = {
            "закрыть все", "закрыть всё", "очистить все", "очистить всё",
            "нет недавних", "close all", "clear all", "no recent"
    };
    private static final int MAX_NODES = 600;
    private static final java.util.Set<String> RECENTS_IDS = new java.util.HashSet<>(
            java.util.Arrays.asList("recentContainer", "recents_activity_root_view",
                    "taskListContainer", "taskView", "clear_all", "clearAllContainer"));
    private static final java.util.Set<String> HOME_IDS = new java.util.HashSet<>(
            java.util.Arrays.asList("home_screen", "hotseat_container", "workspace_pot_view"));
    String lastMarker;
    private static final long CONTENT_THROTTLE_MS = 120;
    /**
     * Сколько ждать, что признаки списка недавних не появятся, прежде чем решить «рабочий
     * стол». По журналу кнопка «Закрыть все» появляется через 0,36–0,47 с после начала.
     */
    private static final long HOME_CONFIRM_MS = 500;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private long homeCandidateAt;
    private boolean checkPosted;


    private String launcherPackage;
    private long lastContentCheck;

    /** Работающая служба — обои опрашивают её напрямую (один процесс, главный поток). */
    static RecentsDetectorService instance;

    /**
     * Что сейчас на экране лаунчера — без ожидания событий: просматривает все видимые окна.
     * Лаунчер виден с самого начала жеста возврата, его события приходят позже.
     */
    int probe() {
        if (launcherPackage == null) return LauncherState.UNKNOWN;
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException e) {
            return LauncherState.UNKNOWN;
        }
        for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || root.getPackageName() == null
                    || !launcherPackage.contentEquals(root.getPackageName())) {
                continue;
            }
            return classify(root);
        }
        return LauncherState.UNKNOWN;
    }


    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        launcherPackage = findLauncherPackage();
        // События — только от лаунчера: в остальных приложениях служба не просыпается.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.packageNames = new String[] {launcherPackage};
            setServiceInfo(info);
        }
        LauncherState.setServiceRunning(true);
        EventLog.add(this, "спец. возможности: служба включена, лаунчер " + launcherPackage);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        LauncherState.setServiceRunning(false);
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        LauncherState.setServiceRunning(false);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null || launcherPackage == null) return;
        if (!launcherPackage.contentEquals(pkg)) return;
        scheduleCheck();
    }

    /**
     * Проверка экрана лаунчера — не чаще раза в CONTENT_THROTTLE_MS, но последнее изменение
     * никогда не теряется: после паузы экран проверяется ещё раз.
     */
    private void scheduleCheck() {
        long now = SystemClock.uptimeMillis();
        long wait = lastContentCheck + CONTENT_THROTTLE_MS - now;
        if (wait <= 0) {
            handler.removeCallbacks(check);
            checkPosted = false;
            check.run();
        } else if (!checkPosted) {
            checkPosted = true;
            handler.postDelayed(check, wait);
        }
    }

    private final Runnable confirmHome = () -> {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        long candidate = homeCandidateAt;
        homeCandidateAt = 0;
        if (root == null || root.getPackageName() == null
                || !launcherPackage.contentEquals(root.getPackageName())) {
            return;
        }
        int seen = classify(root);
        if (seen == LauncherState.RECENTS || seen == LauncherState.OTHER) {
            set(seen, "признак «" + lastMarker + "»");
            return;
        }
        set(LauncherState.HOME, seen == LauncherState.HOME ? "признак «" + lastMarker + "»"
                : "признаков списка нет " + (SystemClock.uptimeMillis() - candidate) + "мс");
    };

    private final Runnable check = () -> {
        checkPosted = false;
        lastContentCheck = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !launcherPackage.contentEquals(root.getPackageName())) {
            return;
        }
        int seen = classify(root);
        if (seen != LauncherState.UNKNOWN) {
            // Явный признак — сразу; запасная проверка не нужна.
            homeCandidateAt = 0;
            handler.removeCallbacks(confirmHome);
            set(seen, "признак «" + lastMarker + "»");
        } else if (homeCandidateAt == 0) {
            // Ни одного признака (неизвестный экран лаунчера) — считаем рабочим столом,
            // только если признаки списка недавних так и не появятся.
            homeCandidateAt = lastContentCheck;
            handler.postDelayed(confirmHome, HOME_CONFIRM_MS);
        }
    };

    @Override
    public void onInterrupt() {
    }

    private void set(int state, String why) {
        if (state != LauncherState.state()) {
            EventLog.add(this, "спец. возможности: " + LauncherState.name(state)
                    + " (" + why + ")");
        }
        LauncherState.set(state);
    }

    /**
     * Что на экране лаунчера — по видимым элементам (One UI 9, проверено по журналу):
     * список недавних — recentContainer / clear_all / taskView; рабочий стол — home_screen /
     * hotseat_container (в начале открытия списка недавних они есть, но скрыты).
     * UNKNOWN — лаунчер виден, но признаков ещё нет. Найденный признак — в lastMarker.
     */
    private int classify(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        String hiddenHome = null;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            boolean visible = node.isVisibleToUser();
            String id = node.getViewIdResourceName();
            if (id != null) {
                String name = id.substring(id.indexOf('/') + 1);
                if (visible && RECENTS_IDS.contains(name)) {
                    lastMarker = name;
                    return LauncherState.RECENTS;
                }
                if (HOME_IDS.contains(name)) {
                    // Элементы рабочего стола стоят в начале дерева: виден — это рабочий стол
                    // (в списке недавних он скрыт), дальше обходить незачем.
                    if (visible) {
                        lastMarker = name;
                        return LauncherState.HOME;
                    }
                    if (hiddenHome == null) hiddenHome = name;
                }
            }
            if (visible && isRecentsText(node.getText())) {
                lastMarker = String.valueOf(node.getText());
                return LauncherState.RECENTS;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        lastMarker = hiddenHome != null ? hiddenHome + " скрыт" : null;
        return hiddenHome != null ? LauncherState.OTHER : LauncherState.UNKNOWN;
    }

    /** Текстовый признак списка недавних — только для коротких надписей, без лишних строк. */
    private static boolean isRecentsText(CharSequence text) {
        if (text == null || text.length() == 0 || text.length() > 40) return false;
        String lower = text.toString().toLowerCase(Locale.ROOT);
        for (String marker : RECENTS_TEXTS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }


    private String findLauncherPackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null && info.activityInfo != null) return info.activityInfo.packageName;
        return "com.sec.android.app.launcher";
    }

}
