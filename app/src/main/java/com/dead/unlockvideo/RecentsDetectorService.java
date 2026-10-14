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
    private static final int MAX_NODES = 400;
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
    private String lastHomeSignature = "";

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
            lastProbeMarker = findRecentsMarker(root);
            lastProbeSignature = signature(root);
            return lastProbeMarker != null ? LauncherState.RECENTS : LauncherState.HOME;
        }
        return LauncherState.UNKNOWN;
    }

    String lastProbeMarker;
    String lastProbeSignature = "";

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
        String marker = findRecentsMarker(root);
        if (marker != null) {
            EventLog.add(this, "спец. возможности: был не рабочий стол, а начало списка "
                    + "недавних; элементы в начале: " + lastHomeSignature);
            set(LauncherState.RECENTS, "признак «" + marker + "»");
            return;
        }
        set(LauncherState.HOME, "признаков списка нет "
                + (SystemClock.uptimeMillis() - candidate) + "мс");
    };

    private final Runnable check = () -> {
        checkPosted = false;
        lastContentCheck = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !launcherPackage.contentEquals(root.getPackageName())) {
            return;
        }
        String marker = findRecentsMarker(root);
        if (marker != null) {
            // Список недавних — сразу; кандидат в «рабочий стол» отменяется.
            homeCandidateAt = 0;
            handler.removeCallbacks(confirmHome);
            set(LauncherState.RECENTS, "признак «" + marker + "»");
        } else if (homeCandidateAt == 0) {
            // Похоже на рабочий стол, но в начале открытия списка недавних его признаков
            // ещё нет — подтверждаем, только если они так и не появятся.
            homeCandidateAt = lastContentCheck;
            lastHomeSignature = signature(root);
            handler.postDelayed(confirmHome, HOME_CONFIRM_MS);
        }
    };

    /** Несколько идентификаторов элементов — чтобы найти ранние признаки списка недавних. */
    private static String signature(AccessibilityNodeInfo root) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES && ids.size() < 40) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            String id = node.getViewIdResourceName();
            // «+» — элемент виден на экране, «-» — есть в дереве, но не виден.
            if (id != null) {
                ids.add(id.substring(id.indexOf('/') + 1) + (node.isVisibleToUser() ? "+" : "-"));
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return ids.toString();
    }

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

    /** Ищет на экране лаунчера кнопку «Закрыть все» и т.п.; null — не нашлось. */
    private String findRecentsMarker(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            String id = node.getViewIdResourceName();
            if (id != null && id.toLowerCase(Locale.ROOT).contains("clear_all")) {
                return id;
            }
            String text = textOf(node);
            if (text != null) {
                for (String marker : RECENTS_TEXTS) {
                    if (text.contains(marker)) return marker;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private static String textOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text == null) text = node.getContentDescription();
        return text == null ? null : text.toString().toLowerCase(Locale.forLanguageTag("ru"));
    }

    private String findLauncherPackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null && info.activityInfo != null) return info.activityInfo.packageName;
        return "com.sec.android.app.launcher";
    }

}
