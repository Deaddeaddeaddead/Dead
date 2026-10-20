package com.dead.unlockvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
 * Стеклянные ярлыки: список приложений, у выбранного — стеклянная иконка (GlassIcon) на
 * рабочий стол. Ставится виджетом (GlassWidget), который открывает само приложение.
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

    private GlassShortcuts(Activity activity) {
        this.activity = activity;
    }

    static void show(Activity activity) {
        if (!AppWidgetManager.getInstance(activity).isRequestPinAppWidgetSupported()) {
            Toast.makeText(activity, "Лаунчер не умеет добавлять виджеты из приложений",
                    Toast.LENGTH_LONG).show();
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
            // Своя копия: рисование меняет границы, а оригинал показан в списке.
            Drawable.ConstantState state = app.icon.getConstantState();
            Drawable icon = state != null ? state.newDrawable().mutate() : app.icon;
            WORKER.execute(() -> {
                Bitmap bmp = GlassIcon.render(icon, st);
                MAIN.post(() -> {
                    if (st != style) return;
                    current[0] = bmp;
                    preview.setImageBitmap(bmp);
                });
            });
        };

        content.addView(label("Значок"));
        content.addView(segments(new String[] {"Белый", "Цветной"},
                () -> style, value -> {
                    style = value;
                    refresh.run();
                }));
        TextView note = text("Ставится виджетом 1×1: так вокруг стекла прозрачно и нет значка "
                + "нашего приложения. В нижнюю панель виджет не встаёт.", 13, R.color.text2);
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
            if (!GlassWidget.requestPin(activity, app.component, app.label, current[0])) {
                Toast.makeText(activity, "Лаунчер отказал", Toast.LENGTH_LONG).show();
            }
            dialog.dismiss();
        });
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
