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

    private String launcherPackage;
    private long lastContentCheck;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
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
        String packageName = pkg.toString();
        int type = event.getEventType();

        if (!packageName.equals(launcherPackage)) return;

        // Лаунчер: рабочий стол или список недавних. Изменения содержимого — не чаще,
        // чем раз в CONTENT_THROTTLE_MS.
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            long now = SystemClock.uptimeMillis();
            if (now - lastContentCheck < CONTENT_THROTTLE_MS) return;
            lastContentCheck = now;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (root.getPackageName() == null
                || !launcherPackage.contentEquals(root.getPackageName())) {
            return;
        }
        String marker = findRecentsMarker(root);
        set(marker != null ? LauncherState.RECENTS : LauncherState.HOME,
                marker != null ? "признак «" + marker + "»" : "признаков списка нет");
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
        return text == null ? null : text.toString().toLowerCase(new Locale("ru"));
    }

    private String findLauncherPackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null && info.activityInfo != null) return info.activityInfo.packageName;
        return "com.sec.android.app.launcher";
    }

}
