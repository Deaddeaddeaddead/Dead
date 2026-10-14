package com.dead.unlockvideo;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
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

    private static final String[] LOCK_TEXT = {
            "Продолжит с того же кадра",
            "К первому кадру, пока экран выключен",
            "Назад на блокировке, вперёд после разблокировки"};
    private static final String[] POSE_TEXT = {
            "Обои замирают",
            "Вид экрана блокировки, реверс — прямо на AOD",
            "Видео доигрывает, реверс — когда появится блокировка"};
    private static final String[] ZOOM_TEXT = {
            null,
            "Увеличивается после разблокировки",
            "Увеличено на блокировке, после разблокировки — как обычно"};
    private static final String[] ZOOM_SPEED_TEXT = {"Вместе с видео или реверсом", null};

    private SharedPreferences sp;
    private final Map<String, Switch> switches = new HashMap<>();
    private ImageView thumbnail;
    private TextView thumbnailHint;
    private TextView videoName;
    private Button pickButton;
    private Button setButton;
    private TextView heroTitle;
    private TextView heroSubtitle;
    private boolean wallpaperActive;
    private TextView countValue;
    private int plays;
    private Segmented endChoice;
    private Segmented lockChoice;
    private Segmented poseChoice;
    private Segmented zoomChoice;
    private Segmented zoomSpeedChoice;
    private TextView noTriggerWarning;
    private View screenOnRow;
    private View recentsRow;
    private View detectorRow;
    private View restrictedRow;
    private View zoomAmountItem;
    private View zoomDurationItem;
    private View zoomOnDismissRow;
    private LinearLayout reverseBox;
    private ProgressBar reverseProgress;
    private TextView reverseStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = Prefs.prefs(this);
        //noinspection ResultOfMethodCallIgnored
        Prefs.legacyReverseFile(this).delete();
        Prefs cfg = Prefs.load(this);
        plays = cfg.plays;

        // Компактная верхняя панель и прокручиваемые карточки, как в One UI 9.
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

        // Запуск
        LinearLayout start = island(root, "Запуск");
        switchRow(start, Prefs.KEY_START_ON_UNLOCK, cfg.startOnUnlock, "При разблокировке", null);
        switchRow(start, Prefs.KEY_UNLOCK_ON_LOCKSCREEN, cfg.unlockOnLockscreen,
                "Сразу после распознавания лица", "Ещё на экране блокировки, до свайпа");
        screenOnRow = switchRow(start, Prefs.KEY_START_ON_SCREEN_ON, cfg.startOnScreenOn,
                "При включении экрана", null);
        switchRow(start, Prefs.KEY_START_ON_HOME, cfg.startOnHome,
                "При возврате на рабочий стол", "Когда выходите из приложения");
        recentsRow = switchRow(start, Prefs.KEY_START_IN_RECENTS, cfg.startInRecents,
                "Также в списке недавних", null);
        detectorRow = row("Распознавание экрана", null);
        detectorRow.setOnClickListener(v -> openAccessibilitySettings());
        start.addView(detectorRow);
        restrictedRow = row("Включение заблокировано?",
                "О приложении → ⋮ → «Разрешить ограниченные настройки»");
        restrictedRow.setOnClickListener(v -> openAppDetails());
        start.addView(restrictedRow);
        noTriggerWarning = note("Не выбран ни один запуск — видео стоит на первом кадре");
        start.addView(noTriggerWarning);

        // Воспроизведение
        LinearLayout playback = island(root, "Воспроизведение");
        stepperRow(playback);
        endChoice = new Segmented(playback, "После окончания",
                new String[] {"Последний кадр", "Первый кадр"},
                cfg.endOnLastFrame ? 0 : 1, index -> {
                    sp.edit().putBoolean(Prefs.KEY_END_ON_LAST_FRAME, index == 0).apply();
                    Prefs.bumpRevision(this);
                });
        switchRow(playback, Prefs.KEY_ONCE, cfg.once, "Только один раз",
                "Повторить — «Проиграть сейчас»");
        switchRow(playback, Prefs.KEY_SOUND, cfg.sound, "Звук", null);

        // Блокировка и AOD
        LinearLayout lock = island(root, "Блокировка и AOD");
        lockChoice = new Segmented(lock, "При блокировке",
                new String[] {"Оставить", "К началу", "Реверсом"},
                cfg.lockAction, this::onLockActionChanged);
        reverseBox = vertical();
        reverseBox.setPadding(dp(24), 0, dp(24), dp(16));
        reverseProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        reverseProgress.setMax(100);
        reverseProgress.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        reverseBox.addView(reverseProgress);
        reverseStatus = text("", 14, R.color.accent);
        reverseBox.addView(reverseStatus);
        lockChoice.item.addView(reverseBox);
        poseChoice = new Segmented(lock, "На AOD после блокировки",
                new String[] {"Как есть", "Блокировка", "Рабочий стол"}, cfg.screenOffPose,
                index -> {
                    sp.edit().putInt(Prefs.KEY_SCREEN_OFF_POSE, index).apply();
                    Prefs.bumpRevision(this);
                    updateEnabled();
                });

        // Эффекты
        LinearLayout effects = island(root, "Эффекты");
        zoomChoice = new Segmented(effects, "Масштаб при разблокировке",
                new String[] {"Нет", "Приблизить", "Отдалить"}, cfg.unlockZoom, index -> {
                    sp.edit().putInt(Prefs.KEY_UNLOCK_ZOOM, index).apply();
                    Prefs.bumpRevision(this);
                    updateEnabled();
                });
        zoomAmountItem = sliderRow(effects, "Сила масштаба", null, 1, Prefs.MAX_ZOOM_AMOUNT,
                cfg.unlockZoomAmount, value -> value + "%", value -> {
                    sp.edit().putInt(Prefs.KEY_UNLOCK_ZOOM_AMOUNT, value).apply();
                    Prefs.bumpRevision(this);
                });
        zoomSpeedChoice = new Segmented(effects, "Скорость масштаба",
                new String[] {"Как у видео", "Своя"}, cfg.zoomSync ? 0 : 1, index -> {
                    sp.edit().putBoolean(Prefs.KEY_ZOOM_SYNC, index == 0).apply();
                    Prefs.bumpRevision(this);
                    updateEnabled();
                });
        zoomDurationItem = sliderRow(effects, "Своя длительность", null,
                Prefs.MIN_ZOOM_DURATION, Prefs.MAX_ZOOM_DURATION, cfg.zoomDurationMs / 100,
                value -> String.format(java.util.Locale.ROOT, "%.1f с", value / 10f),
                value -> {
                    sp.edit().putInt(Prefs.KEY_ZOOM_DURATION, value).apply();
                    Prefs.bumpRevision(this);
                });
        zoomOnDismissRow = switchRow(effects, Prefs.KEY_ZOOM_ON_DISMISS, cfg.zoomOnDismiss,
                "Масштаб после свайпа", "При разблокировке лицом видео — сразу, масштаб — "
                        + "когда убран экран блокировки");
        sliderRow(effects, "Параллакс", "Сдвиг по наклону телефона", 0, Prefs.MAX_PARALLAX,
                cfg.parallax, value -> value == 0 ? "выкл." : value + "%", value -> {
                    sp.edit().putInt(Prefs.KEY_PARALLAX, value).apply();
                    Prefs.bumpRevision(this);
                });

        // Дополнительно
        LinearLayout extra = island(root, "Дополнительно");
        LinearLayout playNow = row("Проиграть сейчас", "С начала — на рабочем столе");
        playNow.setOnClickListener(v -> {
            Prefs.playNow(this);
            Toast.makeText(this, "Видео запустится, когда вы вернётесь на рабочий стол",
                    Toast.LENGTH_SHORT).show();
        });
        extra.addView(playNow);
        LinearLayout log = row("Журнал событий", null);
        log.setOnClickListener(v -> showEventLog());
        extra.addView(log);

        updateEnabled();
        updateVideo();
        // Реверс включён, но ролик не готов или подготовлен старым способом — готовим.
        if (cfg.wantsReverse() && !cfg.reverseReady) ensureReverse(getApplicationContext());
        updateReverseStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = this;
        WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
        wallpaperActive = info != null && info.getComponent().equals(wallpaperComponent());
        updateHero();
        updateDetectorStatus();
        updateReverseStatus();
    }

    @Override
    protected void onPause() {
        if (shown == this) shown = null;
        super.onPause();
    }

    // ---- Настройки ----

    private void onSwitchChanged(String key, boolean checked) {
        SharedPreferences.Editor editor = sp.edit().putBoolean(key, checked);
        // Выключили «Только один раз» — забываем, что видео уже отыграло.
        if (Prefs.KEY_ONCE.equals(key) && !checked) editor.putBoolean(Prefs.KEY_FINISHED, false);
        editor.apply();
        Prefs.bumpRevision(this);
        updateEnabled();
    }

    private void onLockActionChanged(int action) {
        sp.edit().putInt(Prefs.KEY_LOCK_ACTION, action).apply();
        if (action == Prefs.LOCK_REVERSE) {
            reverseError = null;
            ensureReverse(getApplicationContext());
        }
        Prefs.bumpRevision(this);
        updateEnabled();
        updateReverseStatus();
    }

    /** 1, 2, … MAX_PLAYS, затем ∞. */
    private void changeCount(int delta) {
        int value;
        if (delta > 0) {
            value = plays == Prefs.INFINITE ? Prefs.INFINITE
                    : plays >= Prefs.MAX_PLAYS ? Prefs.INFINITE : plays + 1;
        } else {
            value = plays == Prefs.INFINITE ? Prefs.MAX_PLAYS : Math.max(1, plays - 1);
        }
        if (value == plays) return;
        plays = value;
        countValue.setText(countLabel());
        SharedPreferences.Editor editor = sp.edit().putInt(Prefs.KEY_PLAYS, value);
        // Реверс при бесконечном повторе невозможен — возвращаем к началу мгновенно.
        if (value == Prefs.INFINITE && lockChoice.selected == Prefs.LOCK_REVERSE) {
            editor.putInt(Prefs.KEY_LOCK_ACTION, Prefs.LOCK_REWIND);
            lockChoice.select(Prefs.LOCK_REWIND);
        }
        editor.apply();
        Prefs.bumpRevision(this);
        updateEnabled();
        updateReverseStatus();
    }

    private String countLabel() {
        return plays == Prefs.INFINITE ? "∞" : String.valueOf(plays);
    }

    private boolean checked(String key) {
        return switches.get(key).isChecked();
    }

    private void updateEnabled() {
        boolean finite = plays != Prefs.INFINITE;
        setRowEnabled(endChoice.item, finite);
        lockChoice.setOptionEnabled(Prefs.LOCK_REVERSE, finite);
        boolean anyTrigger = checked(Prefs.KEY_START_ON_UNLOCK)
                || checked(Prefs.KEY_START_ON_SCREEN_ON) || checked(Prefs.KEY_START_ON_HOME);
        noTriggerWarning.setVisibility(anyTrigger ? View.GONE : View.VISIBLE);
        boolean home = checked(Prefs.KEY_START_ON_HOME);
        setRowEnabled(recentsRow, home);
        setRowEnabled(detectorRow, home);
        setRowEnabled(restrictedRow, home);
        boolean zoomOn = zoomChoice.selected != Prefs.ZOOM_NONE;
        setRowEnabled(zoomAmountItem, zoomOn);
        setRowEnabled(zoomSpeedChoice.item, zoomOn);
        setRowEnabled(zoomDurationItem, zoomOn);
        setRowEnabled(zoomOnDismissRow, zoomOn);
        boolean reverse = lockChoice.selected == Prefs.LOCK_REVERSE;
        setRowBlocked(screenOnRow, reverse);
        setSubtitle(screenOnRow, reverse ? "Недоступно при «Реверсом»" : "Ещё до разблокировки",
                reverse ? R.color.accent : R.color.text2);
        setSubtitle(lockChoice.item, LOCK_TEXT[lockChoice.selected], R.color.text2);
        setSubtitle(poseChoice.item, POSE_TEXT[poseChoice.selected], R.color.text2);
        setSubtitle(zoomChoice.item, ZOOM_TEXT[zoomChoice.selected], R.color.text2);
        setSubtitle(zoomSpeedChoice.item, ZOOM_SPEED_TEXT[zoomSpeedChoice.selected],
                R.color.text2);
    }

    private void setRowEnabled(View row, boolean enabled) {
        row.setAlpha(enabled ? 1f : 0.4f);
        setEnabledDeep(row, enabled);
    }

    /** Строка недоступна, но подпись с причиной остаётся яркой. */
    private void setRowBlocked(View row, boolean blocked) {
        setEnabledDeep(row, !blocked);
        float alpha = blocked ? 0.4f : 1f;
        ViewGroup group = (ViewGroup) row;
        ((ViewGroup) group.getChildAt(0)).getChildAt(0).setAlpha(alpha);
        group.getChildAt(1).setAlpha(alpha);
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
                    Prefs.reverseFile(app).delete();
                    sp.edit()
                            .putString(Prefs.KEY_VIDEO_NAME, name)
                            .putLong(Prefs.KEY_VIDEO_VERSION, System.nanoTime())
                            .putBoolean(Prefs.KEY_REVERSE_READY, false)
                            .apply();
                    reverseError = null;
                    if (Prefs.load(app).wantsReverse()) ensureReverse(app);
                    Prefs.playNow(app);
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
        File output = Prefs.reverseFile(app);
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
                    if (Prefs.load(app).wantsReverse()) ensureReverse(app);
                } else if (finalResult != null) {
                    EventLog.add(app, "реверс подготовлен, " + finalResult.check);
                    prefs.edit()
                            .putBoolean(Prefs.KEY_REVERSE_READY, true)
                            .putInt(Prefs.KEY_REVERSE_FORMAT, Prefs.REVERSE_FORMAT)
                            .putInt(Prefs.KEY_REVERSE_LAST_MS, (int) finalResult.lastFrameMs)
                            .apply();
                    Prefs.bumpRevision(app);
                } else {
                    reverseError = finalError;
                    EventLog.add(app, "реверс не подготовлен: " + finalError);
                    prefs.edit().putInt(Prefs.KEY_LOCK_ACTION, Prefs.LOCK_REWIND).apply();
                    Prefs.bumpRevision(app);
                    if (shown != null) {
                        shown.lockChoice.select(Prefs.LOCK_REWIND);
                        shown.updateEnabled();
                    }
                }
                refreshShown();
            });
        }).start();
    }

    private static void refreshShown() {
        if (shown != null) shown.updateReverseStatus();
    }

    private void updateReverseStatus() {
        boolean wanted = lockChoice.selected == Prefs.LOCK_REVERSE;
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

    // ---- Распознавание экрана ----

    private boolean detectorEnabled() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        ComponentName ours = new ComponentName(this, RecentsDetectorService.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ComponentName name = new ComponentName(info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name);
            if (name.equals(ours)) return true;
        }
        return false;
    }

    private void updateDetectorStatus() {
        boolean on = detectorEnabled();
        setSubtitle(detectorRow, on ? "Включено — отличает список недавних"
                : "Выключено — нажмите, чтобы включить", on ? R.color.text2 : R.color.accent);
        restrictedRow.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    /** Сразу страница нашей службы; если прошивка так не умеет — общий список. */
    private void openAccessibilitySettings() {
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME,
                        new ComponentName(this, RecentsDetectorService.class).flattenToString());
        try {
            startActivity(details);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show();
        }
    }

    /** «О приложении» — там в меню ⋮ «Разрешить ограниченные настройки». */
    private void openAppDetails() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Журнал ----

    private void showEventLog() {
        String text = EventLog.text(this);
        TextView content = text(text.isEmpty() ? "Пока пусто." : text, 12, R.color.text);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setPadding(dp(24), dp(12), dp(24), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this)
                .setTitle("Журнал событий")
                .setView(scroll)
                .setPositiveButton("Копировать", (d, w) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Журнал видеообоев", text));
                    Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Очистить", (d, w) -> EventLog.clear(this))
                .setNegativeButton("Закрыть", null)
                .show();
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

    /** Островок: подпись и одна плашка со строками через тонкие разделители. */
    private LinearLayout island(LinearLayout root, String title) {
        section(root, title);
        LinearLayout card = card(root);
        GradientDrawable line = new GradientDrawable();
        line.setColor(getColor(R.color.divider));
        line.setSize(1, Math.max(1, Math.round(getResources().getDisplayMetrics().density * 0.7f)));
        card.setDividerDrawable(line);
        card.setDividerPadding(dp(24));
        card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
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

    /** Строка с переключателем; нажатие на всю строку переключает. */
    private LinearLayout switchRow(LinearLayout island, String key, boolean value, String title,
                                   String subtitle) {
        LinearLayout row = row(title, subtitle);
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
        switches.put(key, toggle);
        island.addView(row);
        return row;
    }

    private void stepperRow(LinearLayout island) {
        LinearLayout row = row("Сколько раз подряд", "После " + Prefs.MAX_PLAYS + " — ∞");
        Button minus = roundButton("−");
        minus.setOnClickListener(v -> changeCount(-1));
        countValue = text(countLabel(), 18, R.color.text);
        countValue.setTypeface(Typeface.DEFAULT_BOLD);
        countValue.setGravity(Gravity.CENTER);
        countValue.setMinWidth(dp(44));
        Button plus = roundButton("+");
        plus.setOnClickListener(v -> changeCount(1));
        row.addView(minus);
        row.addView(countValue);
        row.addView(plus);
        island.addView(row);
    }

    interface OnSelect {
        void onSelect(int index);
    }

    /** Строка выбора: заголовок, короткая подпись и «таблетки» под ними, как в One UI. */
    private final class Segmented {
        final LinearLayout item = vertical();
        final TextView[] options;
        int selected;

        Segmented(LinearLayout island, String title, String[] labels, int initial,
                  OnSelect listener) {
            TextView header = text(title, 17, R.color.text);
            header.setPadding(dp(24), dp(16), dp(24), 0);
            item.addView(header);
            item.setTag(addSubtitle(item, null));
            LinearLayout bar = new LinearLayout(MainActivity.this);
            bar.setBackgroundResource(R.drawable.segment_bg);
            bar.setPadding(dp(4), dp(4), dp(4), dp(4));
            options = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                TextView option = text(labels[i], 15, R.color.text);
                option.setGravity(Gravity.CENTER);
                option.setTypeface(Typeface.DEFAULT_BOLD);
                option.setMaxLines(1);
                final int index = i;
                option.setOnClickListener(v -> {
                    if (selected == index) return;
                    select(index);
                    listener.onSelect(index);
                });
                bar.addView(option, new LinearLayout.LayoutParams(0, dp(40), 1f));
                options[i] = option;
            }
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            barParams.setMargins(dp(16), dp(12), dp(16), dp(16));
            item.addView(bar, barParams);
            select(initial);
            island.addView(item);
        }

        void select(int index) {
            selected = index;
            for (int i = 0; i < options.length; i++) {
                boolean on = i == index;
                options[i].setBackgroundResource(on ? R.drawable.segment_selected : 0);
                options[i].setTextColor(getColor(on ? R.color.on_accent : R.color.text));
            }
        }

        void setOptionEnabled(int index, boolean enabled) {
            options[index].setEnabled(enabled);
            options[index].setAlpha(enabled ? 1f : 0.35f);
        }
    }

    interface IntFormat {
        String format(int value);
    }

    interface OnValue {
        void onValue(int value);
    }

    /**
     * Строка с ползунком: заголовок, значение справа, ползунок под ними. Значение
     * сохраняется, когда палец отпущен, — не на каждом шаге перетаскивания.
     */
    private LinearLayout sliderRow(LinearLayout island, String title, String subtitle, int min,
                                   int max, int value, IntFormat format, OnValue listener) {
        LinearLayout item = vertical();
        LinearLayout header = new LinearLayout(this);
        header.setPadding(dp(24), dp(16), dp(24), 0);
        TextView label = text(title, 17, R.color.text);
        header.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueText = text(format.format(value), 15, R.color.accent);
        valueText.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(valueText);
        item.addView(header);
        item.setTag(addSubtitle(item, subtitle));

        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(value);
        ColorStateList accent = ColorStateList.valueOf(getColor(R.color.accent));
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tonal)));
        bar.setPadding(dp(24), dp(12), dp(24), dp(18));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean tracking;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueText.setText(format.format(progress));
                if (fromUser && !tracking) listener.onValue(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                tracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                tracking = false;
                listener.onValue(seekBar.getProgress());
            }
        });
        item.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        island.addView(item);
        return item;
    }

    /** Предупреждение внутри островка. */
    private TextView note(String value) {
        TextView view = text(value, 14, R.color.accent);
        view.setPadding(dp(24), dp(14), dp(24), dp(14));
        return view;
    }

    /** Серая подпись под заголовком; пустая — скрыта. */
    private TextView addSubtitle(LinearLayout parent, String value) {
        TextView sub = text("", 14, R.color.text2);
        sub.setPadding(dp(24), dp(2), dp(24), 0);
        parent.addView(sub);
        sub.setText(value);
        sub.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
        return sub;
    }

    /** Меняет подпись строки (строки, выбора или ползунка — она хранится в теге). */
    private void setSubtitle(View item, String value, int colorRes) {
        TextView sub = (TextView) item.getTag();
        sub.setText(value);
        sub.setTextColor(getColor(colorRes));
        sub.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
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
        TextView sub = addSubtitle(texts, subtitle);
        sub.setPadding(0, dp(2), 0, 0);
        row.setTag(sub);
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
