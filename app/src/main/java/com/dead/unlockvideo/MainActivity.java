package com.dead.unlockvideo;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_VIDEO = 1;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Подготовка реверса переживает пересоздание экрана; трогается только на главном потоке.
    private static boolean reverseRunning;
    private static int reversePercent;
    private static String reverseError;
    private static MainActivity shown;

    private SharedPreferences sp;
    private final Map<String, Switch> switches = new HashMap<>();
    private final Map<String, View> rows = new HashMap<>();
    private ImageView thumbnail;
    private TextView thumbnailHint;
    private TextView videoName;
    private Button pickButton;
    private Button setButton;
    private TextView wallpaperStatus;
    private View countRow;
    private TextView countValue;
    private int playCount;
    private LinearLayout reverseBox;
    private ProgressBar reverseProgress;
    private TextView reverseStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = Prefs.prefs(this);
        Prefs cfg = Prefs.load(this);
        playCount = cfg.playCount;

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setClipToPadding(false);
        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        // Заголовок
        TextView title = text("Видеообои", 32, R.color.text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);
        wallpaperStatus = text("", 13, R.color.on_tonal);
        wallpaperStatus.setBackgroundResource(R.drawable.chip_bg);
        wallpaperStatus.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = dp(8);
        root.addView(wallpaperStatus, chipParams);

        // Видео
        LinearLayout card = card(root, null);
        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundResource(R.drawable.thumb_bg);
        preview.setClipToOutline(true);
        thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.addView(thumbnail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        thumbnailHint = text("Видео не выбрано", 15, R.color.text2);
        preview.addView(thumbnailHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        card.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        videoName = text("", 14, R.color.text2);
        videoName.setPadding(dp(4), dp(10), dp(4), dp(4));
        card.addView(videoName);
        pickButton = button("Выбрать видео", false);
        pickButton.setOnClickListener(v -> pickVideo());
        card.addView(pickButton, buttonParams(8));

        // Воспроизведение
        card = card(root, "Воспроизведение");
        switchRow(card, Prefs.KEY_LOOP, cfg.loop, "Зациклить",
                "Видео крутится без остановки");
        countRow = stepperRow(card);
        switchRow(card, Prefs.KEY_END_ON_LAST_FRAME, cfg.endOnLastFrame,
                "Замирать на последнем кадре",
                "Иначе после окончания сразу показывается первый кадр");
        switchRow(card, Prefs.KEY_SOUND, cfg.sound, "Звук", null);

        // Запуск
        card = card(root, "Запуск");
        switchRow(card, Prefs.KEY_AFTER_UNLOCK, cfg.afterUnlock, "Только после разблокировки",
                "На экране блокировки видео ждёт и стартует, когда вы разблокируете телефон");

        // Блокировка
        card = card(root, "После блокировки");
        switchRow(card, Prefs.KEY_REWIND_ON_LOCK, cfg.rewindOnLock, "Возвращать к началу",
                "После блокировки экрана видео возвращается к первому кадру");
        switchRow(card, Prefs.KEY_REWIND_REVERSE, cfg.rewindReverse, "Возврат реверсом",
                "Видео плавно проигрывается назад на экране блокировки. "
                        + "Нужна разовая подготовка видео; звук в этом режиме не играет");
        reverseBox = vertical();
        reverseBox.setPadding(0, dp(4), 0, dp(8));
        reverseProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        reverseProgress.setMax(100);
        reverseProgress.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        reverseBox.addView(reverseProgress);
        reverseStatus = text("", 13, R.color.text2);
        reverseBox.addView(reverseStatus);
        card.addView(reverseBox);

        // Повтор
        card = card(root, "Повтор");
        switchRow(card, Prefs.KEY_REPLAY, cfg.replay, "Проигрывать повторно",
                "Выключено — видео проиграется один раз и больше не запустится");
        switchRow(card, Prefs.KEY_REPLAY_AFTER_LOCK, cfg.replayAfterLock,
                "Заново после каждой блокировки",
                "Вместе с «Только после разблокировки» — при каждой разблокировке");
        switchRow(card, Prefs.KEY_REPLAY_ON_HOME, cfg.replayOnHome,
                "Заново при возврате на рабочий стол", null);

        // Эффекты
        card = card(root, "Эффекты");
        switchRow(card, Prefs.KEY_ZOOM_EFFECT, cfg.zoomEffect,
                "Приближение при открытии приложений",
                "Обои слегка приближаются, когда открыто приложение, "
                        + "и плавно отдаляются, когда вы возвращаетесь на рабочий стол");

        // Действия
        setButton = button("Установить как обои", true);
        setButton.setOnClickListener(v -> setAsWallpaper());
        root.addView(setButton, buttonParams(24));
        Button reset = button("Проиграть заново", false);
        reset.setOnClickListener(v -> {
            Prefs.bumpRevision(this);
            Toast.makeText(this, "Видео проиграется заново", Toast.LENGTH_SHORT).show();
        });
        root.addView(reset, buttonParams(10));

        updateEnabled();
        updateVideo();
        updateReverseStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = this;
        WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
        boolean active = info != null && info.getComponent().equals(wallpaperComponent());
        wallpaperStatus.setText(active ? "● Обои установлены" : "○ Обои ещё не установлены");
        updateReverseStatus();
    }

    @Override
    protected void onPause() {
        if (shown == this) shown = null;
        super.onPause();
    }

    // ---- Настройки ----

    private void onSwitchChanged(String key, boolean checked) {
        sp.edit().putBoolean(key, checked).apply();
        if (Prefs.KEY_REWIND_REVERSE.equals(key) && checked) {
            reverseError = null;
            ensureReverse(getApplicationContext());
        }
        Prefs.bumpRevision(this);
        updateEnabled();
        updateReverseStatus();
    }

    private void changeCount(int delta) {
        int value = Math.max(1, Math.min(Prefs.MAX_PLAY_COUNT, playCount + delta));
        if (value == playCount) return;
        playCount = value;
        countValue.setText(String.valueOf(value));
        sp.edit().putInt(Prefs.KEY_PLAY_COUNT, value).apply();
        Prefs.bumpRevision(this);
    }

    private boolean checked(String key) {
        return switches.get(key).isChecked();
    }

    private void updateEnabled() {
        boolean finite = !checked(Prefs.KEY_LOOP);
        setRowEnabled(countRow, finite);
        setRowEnabled(rows.get(Prefs.KEY_END_ON_LAST_FRAME), finite);
        setRowEnabled(rows.get(Prefs.KEY_REWIND_ON_LOCK), finite);
        setRowEnabled(rows.get(Prefs.KEY_REWIND_REVERSE),
                finite && checked(Prefs.KEY_REWIND_ON_LOCK));
        boolean replay = checked(Prefs.KEY_REPLAY);
        setRowEnabled(rows.get(Prefs.KEY_REPLAY_AFTER_LOCK), replay);
        setRowEnabled(rows.get(Prefs.KEY_REPLAY_ON_HOME), replay && finite);
    }

    private void setRowEnabled(View row, boolean enabled) {
        row.setAlpha(enabled ? 1f : 0.4f);
        setEnabledDeep(row, enabled);
    }

    private static void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledDeep(group.getChildAt(i), enabled);
            }
        }
    }

    // ---- Видео ----

    private void updateVideo() {
        boolean hasVideo = Prefs.videoFile(this).isFile();
        videoName.setText(hasVideo ? sp.getString(Prefs.KEY_VIDEO_NAME, "") : "");
        videoName.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        thumbnailHint.setVisibility(hasVideo ? View.GONE : View.VISIBLE);
        pickButton.setText(hasVideo ? "Выбрать другое видео" : "Выбрать видео");
        setButton.setEnabled(hasVideo);
        setButton.setAlpha(hasVideo ? 1f : 0.5f);
        thumbnail.setImageDrawable(null);
        if (!hasVideo) return;

        File video = Prefs.videoFile(this);
        new Thread(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(video.getAbsolutePath());
                frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null && frame.getWidth() > 900) {
                    int height = Math.round(frame.getHeight() * 900f / frame.getWidth());
                    frame = Bitmap.createScaledBitmap(frame, 900, height, true);
                }
            } catch (RuntimeException ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
            Bitmap result = frame;
            runOnUiThread(() -> {
                if (!isDestroyed()) thumbnail.setImageBitmap(result);
            });
        }).start();
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*");
        startActivityForResult(intent, REQUEST_PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_VIDEO || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        // Копируем видео к себе, чтобы обои не зависели от исходного файла и выданных прав.
        pickButton.setEnabled(false);
        pickButton.setText("Копирую видео…");
        String name = displayName(uri);
        Context app = getApplicationContext();
        new Thread(() -> {
            String error = null;
            try {
                copyVideo(app, uri);
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            String finalError = error;
            MAIN.post(() -> {
                if (finalError == null) {
                    //noinspection ResultOfMethodCallIgnored
                    Prefs.reversibleFile(app).delete();
                    sp.edit()
                            .putString(Prefs.KEY_VIDEO_NAME, name)
                            .putLong(Prefs.KEY_VIDEO_VERSION, System.nanoTime())
                            .putBoolean(Prefs.KEY_REVERSE_READY, false)
                            .apply();
                    reverseError = null;
                    if (Prefs.load(app).rewindReverse) ensureReverse(app);
                    Prefs.bumpRevision(app);
                } else {
                    Toast.makeText(app, "Не удалось скопировать видео: " + finalError,
                            Toast.LENGTH_LONG).show();
                }
                if (!isDestroyed()) {
                    pickButton.setEnabled(true);
                    updateVideo();
                    updateReverseStatus();
                }
            });
        }).start();
    }

    private static void copyVideo(Context context, Uri uri) throws IOException {
        File target = Prefs.videoFile(context);
        File temp = new File(context.getFilesDir(), "wallpaper_video.tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("пустой поток");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("не удалось сохранить файл");
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return uri.getLastPathSegment();
    }

    // ---- Подготовка реверса ----

    /** Запускает подготовку ролика «вперёд + назад», если её ещё нет. Главный поток. */
    private static void ensureReverse(Context app) {
        SharedPreferences prefs = Prefs.prefs(app);
        File video = Prefs.videoFile(app);
        if (reverseRunning || prefs.getBoolean(Prefs.KEY_REVERSE_READY, false)
                || !video.isFile()) {
            return;
        }
        long version = prefs.getLong(Prefs.KEY_VIDEO_VERSION, 0);
        File output = Prefs.reversibleFile(app);
        File temp = new File(output.getPath() + ".tmp");
        reverseRunning = true;
        reversePercent = 0;
        reverseError = null;
        refreshShown();

        new Thread(() -> {
            VideoReverser.Result result = null;
            String error = null;
            try {
                result = VideoReverser.build(video, temp, percent -> MAIN.post(() -> {
                    reversePercent = percent;
                    refreshShown();
                }));
                if (!temp.renameTo(output)) throw new IOException("не удалось сохранить файл");
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            VideoReverser.Result finalResult = result;
            String finalError = error;
            MAIN.post(() -> {
                reverseRunning = false;
                if (prefs.getLong(Prefs.KEY_VIDEO_VERSION, 0) != version) {
                    // Пока готовили, выбрали другое видео — готовим заново.
                    //noinspection ResultOfMethodCallIgnored
                    output.delete();
                    if (Prefs.load(app).rewindReverse) ensureReverse(app);
                } else if (finalResult != null) {
                    prefs.edit()
                            .putBoolean(Prefs.KEY_REVERSE_READY, true)
                            .putInt(Prefs.KEY_REVERSE_LAST_MS, (int) finalResult.lastFrameMs)
                            .putInt(Prefs.KEY_REVERSE_SPLIT_MS, (int) finalResult.splitMs)
                            .apply();
                    Prefs.bumpRevision(app);
                } else {
                    reverseError = finalError;
                    prefs.edit().putBoolean(Prefs.KEY_REWIND_REVERSE, false).apply();
                    Prefs.bumpRevision(app);
                    if (shown != null) shown.switches.get(Prefs.KEY_REWIND_REVERSE)
                            .setChecked(false);
                }
                refreshShown();
            });
        }).start();
    }

    private static void refreshShown() {
        if (shown != null) shown.updateReverseStatus();
    }

    private void updateReverseStatus() {
        boolean wanted = checked(Prefs.KEY_REWIND_REVERSE);
        boolean ready = sp.getBoolean(Prefs.KEY_REVERSE_READY, false);
        if (reverseRunning) {
            reverseBox.setVisibility(View.VISIBLE);
            reverseProgress.setVisibility(View.VISIBLE);
            reverseProgress.setProgress(reversePercent);
            reverseStatus.setText("Готовлю реверс… " + reversePercent + "%");
        } else if (reverseError != null) {
            reverseBox.setVisibility(View.VISIBLE);
            reverseProgress.setVisibility(View.GONE);
            reverseStatus.setText("Не получилось подготовить реверс: " + reverseError
                    + ". Видео будет возвращаться к началу мгновенно.");
        } else if (wanted && ready) {
            reverseBox.setVisibility(View.VISIBLE);
            reverseProgress.setVisibility(View.GONE);
            reverseStatus.setText("✓ Реверс готов");
        } else {
            reverseBox.setVisibility(View.GONE);
        }
    }

    // ---- Установка обоев ----

    private ComponentName wallpaperComponent() {
        return new ComponentName(this, VideoWallpaperService.class);
    }

    private void setAsWallpaper() {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, wallpaperComponent());
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        }
    }

    // ---- Построение интерфейса ----

    private LinearLayout card(LinearLayout root, String title) {
        if (title != null) {
            TextView header = text(title, 14, R.color.accent);
            header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            header.setPadding(dp(8), dp(24), dp(8), dp(8));
            root.addView(header);
        }
        LinearLayout card = vertical();
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(16), dp(title == null ? 16 : 8), dp(16), dp(title == null ? 16 : 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (title == null) params.topMargin = dp(20);
        root.addView(card, params);
        return card;
    }

    private void switchRow(LinearLayout card, String key, boolean value, String title,
                           String subtitle) {
        LinearLayout row = row(title, subtitle);
        Switch toggle = new Switch(this);
        toggle.setChecked(value);
        toggle.setOnCheckedChangeListener((button, isChecked) -> onSwitchChanged(key, isChecked));
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.toggle());
        switches.put(key, toggle);
        rows.put(key, row);
        card.addView(row);
    }

    private View stepperRow(LinearLayout card) {
        LinearLayout row = row("Количество проигрываний", "Сколько раз подряд");
        Button minus = roundButton("−");
        minus.setOnClickListener(v -> changeCount(-1));
        countValue = text(String.valueOf(playCount), 18, R.color.text);
        countValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        countValue.setGravity(Gravity.CENTER);
        countValue.setMinWidth(dp(44));
        Button plus = roundButton("+");
        plus.setOnClickListener(v -> changeCount(1));
        row.addView(minus);
        row.addView(countValue);
        row.addView(plus);
        card.addView(row);
        return row;
    }

    private LinearLayout row(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(0, dp(10), 0, dp(10));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        LinearLayout texts = vertical();
        texts.setPadding(0, 0, dp(12), 0);
        texts.addView(text(title, 16, R.color.text));
        if (subtitle != null) {
            TextView sub = text(subtitle, 13, R.color.text2);
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button button(String label, boolean filled) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setStateListAnimator(null);
        button.setBackgroundResource(filled ? R.drawable.button_filled : R.drawable.button_tonal);
        button.setTextColor(getColor(filled ? R.color.on_accent : R.color.on_tonal));
        return button;
    }

    private Button roundButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        button.setTextColor(getColor(R.color.on_tonal));
        button.setStateListAnimator(null);
        button.setBackgroundResource(R.drawable.round_tonal);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int sizeSp, int colorRes) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(getColor(colorRes));
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
