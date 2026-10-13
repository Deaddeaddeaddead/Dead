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
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
    private TextView heroTitle;
    private TextView heroSubtitle;
    private boolean wallpaperActive;
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

        // Компактная верхняя панель и прокручиваемые карточки, как в One UI 8.
        LinearLayout screen = vertical();
        screen.setFitsSystemWindows(true);
        TextView appTitle = text(getString(R.string.app_name), 22, R.color.text);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setGravity(Gravity.CENTER_VERTICAL);
        appTitle.setPadding(dp(28), 0, dp(28), 0);
        screen.addView(appTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout root = vertical();
        root.setPadding(dp(12), dp(4), dp(12), dp(32));
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(screen);

        // Главная карточка: свечение, градиентный заголовок и «стеклянная» кнопка.
        LinearLayout hero = vertical();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setBackground(heroBackground());
        hero.setClipToOutline(true);
        hero.setPadding(dp(24), dp(32), dp(24), dp(28));
        heroTitle = text("", 28, R.color.text);
        heroTitle.setTypeface(Typeface.DEFAULT_BOLD);
        heroTitle.setGravity(Gravity.CENTER);
        heroTitle.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) applyTitleGradient();
        });
        hero.addView(heroTitle);
        heroSubtitle = text("", 15, R.color.text);
        heroSubtitle.setGravity(Gravity.CENTER);
        heroSubtitle.setAlpha(0.85f);
        heroSubtitle.setPadding(0, dp(6), 0, 0);
        hero.addView(heroSubtitle);
        setButton = button("Установить как обои", R.drawable.button_glass, R.color.on_glass);
        setButton.setPadding(dp(40), 0, dp(40), 0);
        setButton.setOnClickListener(v -> setAsWallpaper());
        LinearLayout.LayoutParams setParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(54));
        setParams.topMargin = dp(22);
        hero.addView(setButton, setParams);
        root.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Видео
        section(root, "Видео");
        LinearLayout card = card(root);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
        videoName = text("", 14, R.color.text2);
        videoName.setPadding(dp(8), dp(12), dp(8), 0);
        card.addView(videoName);
        pickButton = button("Выбрать видео", R.drawable.button_tonal, R.color.on_tonal);
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        pickParams.topMargin = dp(14);
        pickButton.setOnClickListener(v -> pickVideo());
        card.addView(pickButton, pickParams);

        // Воспроизведение
        section(root, "Воспроизведение");
        switchItem(root, Prefs.KEY_LOOP, cfg.loop, "Зациклить",
                "Видео крутится без остановки.");
        countRow = stepperItem(root);
        switchItem(root, Prefs.KEY_END_ON_LAST_FRAME, cfg.endOnLastFrame,
                "Замирать на последнем кадре",
                "Иначе после окончания сразу показывается первый кадр.");
        switchItem(root, Prefs.KEY_SOUND, cfg.sound, "Звук", null);

        // Запуск
        section(root, "Запуск");
        switchItem(root, Prefs.KEY_AFTER_UNLOCK, cfg.afterUnlock, "Только после разблокировки",
                "На экране блокировки видео ждёт и стартует, когда вы разблокируете телефон.");
        switchItem(root, Prefs.KEY_REPLAY_ON_HOME, cfg.replayOnHome,
                "При возврате на рабочий стол",
                "Видео проигрывается заново каждый раз, когда вы выходите из приложения "
                        + "на рабочий стол. Работает независимо от раздела «Повтор».");

        // Блокировка
        section(root, "После блокировки");
        switchItem(root, Prefs.KEY_REWIND_ON_LOCK, cfg.rewindOnLock, "Возвращать к началу",
                "После блокировки экрана видео возвращается к первому кадру.");
        LinearLayout reverseItem = switchItem(root, Prefs.KEY_REWIND_REVERSE, cfg.rewindReverse,
                "Возврат реверсом",
                "Видео плавно проигрывается назад на экране блокировки. "
                        + "Нужна разовая подготовка видео; звук в этом режиме не играет.");
        reverseBox = vertical();
        reverseBox.setPadding(dp(24), dp(4), dp(24), 0);
        reverseProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        reverseProgress.setMax(100);
        reverseProgress.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        reverseBox.addView(reverseProgress);
        reverseStatus = text("", 14, R.color.accent);
        reverseBox.addView(reverseStatus);
        reverseItem.addView(reverseBox);

        // Повтор
        section(root, "Повтор");
        switchItem(root, Prefs.KEY_REPLAY, cfg.replay, "Проигрывать повторно",
                "Если выключено, видео проиграется один раз и больше не запустится.");
        switchItem(root, Prefs.KEY_REPLAY_AFTER_LOCK, cfg.replayAfterLock,
                "Заново после каждой блокировки",
                "Вместе с «Только после разблокировки» — при каждой разблокировке.");

        // Эффекты
        section(root, "Эффекты");
        switchItem(root, Prefs.KEY_ZOOM_EFFECT, cfg.zoomEffect,
                "Приближение при открытии приложений",
                "Обои слегка приближаются, когда открыто приложение, "
                        + "и плавно отдаляются, когда вы возвращаетесь на рабочий стол.");

        // Дополнительно
        section(root, "Дополнительно");
        LinearLayout reset = row("Проиграть заново", "Сбросить «уже проиграно»");
        reset.setOnClickListener(v -> {
            Prefs.bumpRevision(this);
            Toast.makeText(this, "Видео проиграется заново", Toast.LENGTH_SHORT).show();
        });
        card(root).addView(reset);

        updateEnabled();
        updateVideo();
        // Реверс включён, но ролик не готов или подготовлен старым способом — готовим.
        if (cfg.rewindReverse && !cfg.reverseReady) ensureReverse(getApplicationContext());
        updateReverseStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = this;
        WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
        wallpaperActive = info != null && info.getComponent().equals(wallpaperComponent());
        updateHero();
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
        updateHero();
        if (!hasVideo) return;

        File video = Prefs.videoFile(this);
        new Thread(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(video.getAbsolutePath());
                frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST);
                int maxWidth = getResources().getDisplayMetrics().widthPixels;
                if (frame != null && frame.getWidth() > maxWidth) {
                    int height = Math.round(frame.getHeight() * (float) maxWidth / frame.getWidth());
                    frame = Bitmap.createScaledBitmap(frame, maxWidth, height, true);
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
        if (reverseRunning || Prefs.load(app).reverseReady || !video.isFile()) {
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
                            .putInt(Prefs.KEY_REVERSE_FORMAT, Prefs.REVERSE_FORMAT)
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
        boolean ready = Prefs.load(this).reverseReady;
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

    /** Серая подпись раздела над плашками. */
    private void section(LinearLayout root, String title) {
        TextView label = text(title, 14, R.color.text2);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(dp(24), dp(26), dp(24), dp(2));
        root.addView(label);
    }

    /** Скруглённая плашка, как в One UI 9. */
    private LinearLayout card(LinearLayout root) {
        LinearLayout card = vertical();
        card.setBackgroundResource(R.drawable.card_bg);
        card.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        root.addView(card, params);
        return card;
    }

    /** Фон главной карточки: карточка + мягкое цветное свечение снизу. */
    private Drawable heroBackground() {
        GradientDrawable base = new GradientDrawable();
        base.setColor(getColor(R.color.card));
        base.setCornerRadius(dp(28));
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[] {getColor(R.color.glow), Color.TRANSPARENT});
        glow.setGradientCenter(0.5f, 1.05f);
        glow.setGradientRadius(dp(300));
        glow.setCornerRadius(dp(28));
        return new LayerDrawable(new Drawable[] {base, glow});
    }

    private void applyTitleGradient() {
        int width = heroTitle.getWidth();
        if (width <= 0) return;
        heroTitle.getPaint().setShader(new LinearGradient(0, 0, width, 0,
                getColor(R.color.gradient_start), getColor(R.color.gradient_end),
                Shader.TileMode.CLAMP));
        heroTitle.invalidate();
    }

    private void updateHero() {
        boolean hasVideo = Prefs.videoFile(this).isFile();
        if (!hasVideo) {
            heroTitle.setText("Выберите видео");
            heroSubtitle.setText("Оно станет живыми обоями и запустится после разблокировки");
        } else if (wallpaperActive) {
            heroTitle.setText("Обои установлены");
            heroSubtitle.setText("Настройки ниже применяются сразу");
        } else {
            heroTitle.setText("Видео готово");
            heroSubtitle.setText("Осталось установить его как обои");
        }
        setButton.setText(wallpaperActive ? "Установить заново" : "Установить как обои");
        applyTitleGradient();
    }

    /**
     * Пункт с переключателем: отдельная плашка, пояснение серым текстом под ней.
     * Возвращает контейнер пункта (плашка + пояснение).
     */
    private LinearLayout switchItem(LinearLayout root, String key, boolean value, String title,
                                    String description) {
        LinearLayout item = vertical();
        LinearLayout card = card(item);
        LinearLayout row = row(title, null);
        Switch toggle = new Switch(this);
        toggle.setThumbResource(R.drawable.switch_thumb);
        toggle.setTrackResource(R.drawable.switch_track);
        toggle.setSwitchMinWidth(dp(40));
        toggle.setShowText(false);
        toggle.setBackground(null);
        toggle.setChecked(value);
        toggle.setOnCheckedChangeListener((button, isChecked) -> onSwitchChanged(key, isChecked));
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.toggle());
        card.addView(row);
        if (description != null) item.addView(description(description));
        switches.put(key, toggle);
        rows.put(key, item);
        root.addView(item);
        return item;
    }

    private View stepperItem(LinearLayout root) {
        LinearLayout item = vertical();
        LinearLayout row = row("Количество проигрываний", null);
        Button minus = roundButton("−");
        minus.setOnClickListener(v -> changeCount(-1));
        countValue = text(String.valueOf(playCount), 18, R.color.text);
        countValue.setTypeface(Typeface.DEFAULT_BOLD);
        countValue.setGravity(Gravity.CENTER);
        countValue.setMinWidth(dp(44));
        Button plus = roundButton("+");
        plus.setOnClickListener(v -> changeCount(1));
        row.addView(minus);
        row.addView(countValue);
        row.addView(plus);
        card(item).addView(row);
        item.addView(description("Сколько раз подряд проиграть видео."));
        root.addView(item);
        return item;
    }

    private TextView description(String value) {
        TextView view = text(value, 14, R.color.text2);
        view.setPadding(dp(24), dp(10), dp(24), dp(4));
        return view;
    }

    private LinearLayout row(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(24), dp(12), dp(20), dp(12));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        LinearLayout texts = vertical();
        texts.setPadding(0, 0, dp(12), 0);
        texts.addView(text(title, 17, R.color.text));
        if (subtitle != null) {
            TextView sub = text(subtitle, 14, R.color.text2);
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button button(String label, int backgroundRes, int textColorRes) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setStateListAnimator(null);
        button.setBackgroundResource(backgroundRes);
        button.setTextColor(getColor(textColorRes));
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
