package com.dead.unlockvideo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Стеклянная иконка на рабочем столе — виджет 1×1: картинка GlassIcon и подпись, нажатие
 * открывает приложение. Виджет, а не ярлык: у ярлыка One UI заливает прозрачное чёрным и
 * добавляет значок нашего приложения, у виджета фон прозрачный и значков нет.
 *
 * Добавление: картинка сохраняется как «ожидающая» под своим номером, лаунчер спрашивает, куда
 * поставить виджет, и присылает сюда номер нового виджета (ACTION_PINNED) — картинка и
 * приложение переходят к нему.
 */
public final class GlassWidget extends AppWidgetProvider {
    private static final String TAG = "GlassWidget";
    private static final String ACTION_PINNED = "com.dead.unlockvideo.GLASS_WIDGET_PINNED";
    private static final String EXTRA_TOKEN = "token";
    private static final String PREFS = "glass_widgets";
    private static final String DIR = "glass_widgets";

    /** Просит лаунчер поставить стеклянную иконку приложения; false — лаунчер не умеет. */
    static boolean requestPin(Context context, ComponentName app, String label, Bitmap icon) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (!manager.isRequestPinAppWidgetSupported()) return false;
        String token = Long.toString(System.nanoTime());
        try {
            save(icon, file(context, "pending_" + token));
        } catch (IOException e) {
            Log.w(TAG, "icon save failed", e);
            return false;
        }
        prefs(context).edit()
                .putString("pending_" + token + ".app", app.flattenToString())
                .putString("pending_" + token + ".label", label)
                .apply();
        Intent callback = new Intent(context, GlassWidget.class)
                .setAction(ACTION_PINNED)
                .putExtra(EXTRA_TOKEN, token);
        // Изменяемый: лаунчер дописывает в него номер виджета.
        PendingIntent done = PendingIntent.getBroadcast(context, token.hashCode(), callback,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        return manager.requestPinAppWidget(new ComponentName(context, GlassWidget.class), null,
                done);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_PINNED.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            String token = intent.getStringExtra(EXTRA_TOKEN);
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && token != null) {
                adopt(context, id, token);
                update(context, AppWidgetManager.getInstance(context), id);
            }
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (int id : ids) {
            editor.remove(id + ".app").remove(id + ".label");
            file(context, Integer.toString(id)).delete();
        }
        editor.apply();
    }

    /** Ожидающая картинка и приложение переходят к виджету id. */
    private static void adopt(Context context, int id, String token) {
        SharedPreferences sp = prefs(context);
        String key = "pending_" + token;
        String app = sp.getString(key + ".app", null);
        if (app == null) return;
        file(context, key).renameTo(file(context, Integer.toString(id)));
        sp.edit()
                .putString(id + ".app", app)
                .putString(id + ".label", sp.getString(key + ".label", ""))
                .remove(key + ".app")
                .remove(key + ".label")
                .apply();
    }

    private static void update(Context context, AppWidgetManager manager, int id) {
        SharedPreferences sp = prefs(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.glass_widget);
        String app = sp.getString(id + ".app", null);
        Bitmap icon = BitmapFactory.decodeFile(file(context, Integer.toString(id)).getPath());
        if (app == null || icon == null) {
            // Поставлен из списка виджетов, а не из приложения, — подсказка.
            views.setTextViewText(R.id.glass_label, "Выберите в приложении");
            Intent open = new Intent(context, MainActivity.class);
            views.setOnClickPendingIntent(R.id.glass_root, PendingIntent.getActivity(context,
                    id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            manager.updateAppWidget(id, views);
            return;
        }
        views.setImageViewBitmap(R.id.glass_icon, icon);
        views.setTextViewText(R.id.glass_label, sp.getString(id + ".label", ""));
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName.unflattenFromString(app))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        views.setOnClickPendingIntent(R.id.glass_root, PendingIntent.getActivity(context, id,
                launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(id, views);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File file(Context context, String name) {
        File dir = new File(context.getFilesDir(), DIR);
        dir.mkdirs();
        return new File(dir, name + ".png");
    }

    private static void save(Bitmap bitmap, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("png");
            }
        }
    }
}
