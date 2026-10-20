package com.dead.unlockvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Стеклянные ярлыки: список приложений, у выбранного — стеклянная иконка (GlassIcon) и ярлык с
 * ней на рабочий стол. Ярлык открывает само приложение. Иконку можно сделать «своей формы»
 * (плитка со скруглением, вокруг прозрачно) или «адаптивной» — тогда форму задаёт лаунчер.
 */
final class GlassShortcuts {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private static final class App {
        final ComponentName component;
        final String label;
        final Drawable icon;

        App(ComponentName component, String label, Drawable icon) {
            this.component = component;
            this.label = label;
            this.icon = icon;
        }
    }

    private final Activity activity;
    private int style = GlassIcon.STYLE_WHITE;
    private boolean adaptive;

    private GlassShortcuts(Activity activity) {
        this.activity = activity;
    }

    static void show(Activity activity) {
        ShortcutManager sm = activity.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(activity, "Лаунчер не умеет добавлять ярлыки", Toast.LENGTH_LONG)
                    .show();
            return;
        }
        new GlassShortcuts(activity).loadApps();
    }

    private void loadApps() {
        Toast.makeText(activity, "Загружаю приложения…", Toast.LENGTH_SHORT).show();
        WORKER.execute(() -> {
            LauncherApps launcher = activity.getSystemService(LauncherApps.class);
            List<App> apps = new ArrayList<>();
            int density = activity.getResources().getDisplayMetrics().densityDpi;
            for (LauncherActivityInfo info : launcher.getActivityList(null, Process.myUserHandle())) {
                if (info.getComponentName().getPackageName().equals(activity.getPackageName())) {
                    continue;
                }
                apps.add(new App(info.getComponentName(), String.valueOf(info.getLabel()),
                        info.getIcon(density)));
            }
            Collator collator = Collator.getInstance();
            apps.sort((a, b) -> collator.compare(a.label, b.label));
            MAIN.post(() -> {
                if (!activity.isFinishing()) showList(apps);
            });
        });
    }

    private void showList(List<App> apps) {
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int position) {
                return apps.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                LinearLayout row = (LinearLayout) convert;
                if (row == null) {
                    row = new LinearLayout(activity);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(24), dp(8), dp(24), dp(8));
                    ImageView icon = new ImageView(activity);
                    row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                    TextView label = text("", 16, R.color.text);
                    label.setPadding(dp(16), 0, 0, 0);
                    row.addView(label);
                }
                App app = apps.get(position);
                ((ImageView) row.getChildAt(0)).setImageDrawable(app.icon);
                ((TextView) row.getChildAt(1)).setText(app.label);
                return row;
            }
        });
        new AlertDialog.Builder(activity)
                .setTitle("Стеклянный ярлык")
                .setView(list)
                .setNegativeButton("Закрыть", null)
                .show();
        list.setOnItemClickListener((parent, view, position, id) -> showApp(apps.get(position)));
    }

    private void showApp(App app) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), 0);

        // Предпросмотр — на цветном фоне, как на обоях: стекло видно только на чём-то.
        FrameLayout stage = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {activity.getColor(R.color.gradient_start),
                        activity.getColor(R.color.gradient_end)});
        bg.setCornerRadius(dp(24));
        stage.setBackground(bg);
        ImageView preview = new ImageView(activity);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(dp(120), dp(120),
                Gravity.CENTER);
        stage.addView(preview, previewParams);
        content.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));

        Bitmap[] current = new Bitmap[1];
        Runnable refresh = () -> {
            int st = style;
            boolean ad = adaptive;
            // Своя копия: рисование меняет границы, а оригинал показан в списке.
            Drawable.ConstantState state = app.icon.getConstantState();
            Drawable icon = state != null ? state.newDrawable().mutate() : app.icon;
            WORKER.execute(() -> {
                Bitmap bmp = GlassIcon.render(icon, st, ad);
                MAIN.post(() -> {
                    if (st != style || ad != adaptive) return;
                    current[0] = bmp;
                    // Адаптивная — как её покажет лаунчер: средняя часть, скруглённая.
                    preview.setImageBitmap(ad ? adaptivePreview(bmp) : bmp);
                });
            });
        };

        content.addView(label("Значок"));
        content.addView(segments(new String[] {"Белый", "Цветной"},
                () -> style, value -> {
                    style = value;
                    refresh.run();
                }));
        content.addView(label("Форма"));
        content.addView(segments(new String[] {"Своя", "Как у лаунчера"},
                () -> adaptive ? 1 : 0, value -> {
                    adaptive = value == 1;
                    refresh.run();
                }));
        TextView note = text("Если лаунчер добавит к значку рамку или фон — попробуйте другую "
                + "форму.", 13, R.color.text2);
        note.setPadding(0, dp(12), 0, 0);
        content.addView(note);
        refresh.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(app.label)
                .setView(content)
                .setPositiveButton("На рабочий стол", null)
                .setNegativeButton("Отмена", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (current[0] == null) return;
            pin(app, current[0], adaptive);
            dialog.dismiss();
        });
    }

    private void pin(App app, Bitmap bitmap, boolean adaptive) {
        ShortcutManager sm = activity.getSystemService(ShortcutManager.class);
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // У каждого варианта — свой ярлык: на столе их может быть несколько.
        String id = "glass:" + app.component.flattenToShortString() + ":" + style
                + (adaptive ? ":a" : ":b");
        ShortcutInfo info = new ShortcutInfo.Builder(activity, id)
                .setShortLabel(app.label)
                .setIcon(adaptive ? Icon.createWithAdaptiveBitmap(bitmap)
                        : Icon.createWithBitmap(bitmap))
                .setIntent(launch)
                .build();
        try {
            if (!sm.requestPinShortcut(info, null)) {
                Toast.makeText(activity, "Лаунчер отказал", Toast.LENGTH_LONG).show();
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            Toast.makeText(activity, "Не удалось: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Как лаунчер покажет адаптивную иконку: середина 72 из 108, скруглённая. */
    private static Bitmap adaptivePreview(Bitmap layer) {
        int s = layer.getWidth();
        int inset = Math.round(s * 18f / 108f);
        int size = s - 2 * inset;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(0, 0, size, size, size * 0.3f, size * 0.3f,
                android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        c.drawBitmap(layer, -inset, -inset, null);
        return out;
    }

    private interface IntGetter {
        int get();
    }

    private interface IntSetter {
        void set(int value);
    }

    private LinearLayout segments(String[] titles, IntGetter getter, IntSetter setter) {
        LinearLayout row = new LinearLayout(activity);
        TextView[] chips = new TextView[titles.length];
        Runnable restyle = () -> {
            for (int i = 0; i < chips.length; i++) {
                boolean on = getter.get() == i;
                chips[i].setBackgroundResource(on ? R.drawable.segment_selected
                        : R.drawable.segment_bg);
                chips[i].setTextColor(activity.getColor(on ? R.color.on_accent : R.color.text));
            }
        };
        for (int i = 0; i < titles.length; i++) {
            TextView chip = text(titles[i], 15, R.color.text);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            final int value = i;
            chip.setOnClickListener(v -> {
                if (getter.get() == value) return;
                setter.set(value);
                restyle.run();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            params.rightMargin = dp(8);
            row.addView(chip, params);
            chips[i] = chip;
        }
        restyle.run();
        return row;
    }

    private TextView label(String value) {
        TextView view = text(value, 14, R.color.text2);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(16), 0, dp(8));
        return view;
    }

    private TextView text(String value, int sizeSp, int colorRes) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(activity.getColor(colorRes));
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}
