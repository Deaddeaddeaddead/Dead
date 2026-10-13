package com.dead.unlockvideo;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.NumberPicker;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_VIDEO = 1;

    private SharedPreferences sp;
    private Button pickVideo;
    private TextView videoStatus;
    private Switch loop;
    private TextView playCountLabel;
    private NumberPicker playCount;
    private Switch endOnLastFrame;
    private Switch sound;
    private Switch afterUnlock;
    private Switch replay;
    private Switch replayAfterLock;
    private Switch replayOnHome;
    private Button setWallpaper;
    private TextView wallpaperStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sp = Prefs.prefs(this);

        pickVideo = findViewById(R.id.pick_video);
        videoStatus = findViewById(R.id.video_status);
        loop = findViewById(R.id.loop);
        playCountLabel = findViewById(R.id.play_count_label);
        playCount = findViewById(R.id.play_count);
        endOnLastFrame = findViewById(R.id.end_on_last_frame);
        sound = findViewById(R.id.sound);
        afterUnlock = findViewById(R.id.after_unlock);
        replay = findViewById(R.id.replay);
        replayAfterLock = findViewById(R.id.replay_after_lock);
        replayOnHome = findViewById(R.id.replay_on_home);
        setWallpaper = findViewById(R.id.set_wallpaper);
        wallpaperStatus = findViewById(R.id.wallpaper_status);

        Prefs cfg = Prefs.load(this);
        playCount.setMinValue(1);
        playCount.setMaxValue(Prefs.MAX_PLAY_COUNT);
        playCount.setWrapSelectorWheel(false);
        playCount.setValue(cfg.playCount);
        loop.setChecked(cfg.loop);
        endOnLastFrame.setChecked(cfg.endOnLastFrame);
        sound.setChecked(cfg.sound);
        afterUnlock.setChecked(cfg.afterUnlock);
        replay.setChecked(cfg.replay);
        replayAfterLock.setChecked(cfg.replayAfterLock);
        replayOnHome.setChecked(cfg.replayOnHome);

        CompoundButton.OnCheckedChangeListener onToggle = (button, checked) -> save();
        for (Switch s : new Switch[] {loop, endOnLastFrame, sound, afterUnlock, replay,
                replayAfterLock, replayOnHome}) {
            s.setOnCheckedChangeListener(onToggle);
        }
        playCount.setOnValueChangedListener((picker, oldValue, newValue) -> save());

        pickVideo.setOnClickListener(v -> pickVideo());
        findViewById(R.id.reset).setOnClickListener(v -> Prefs.bumpRevision(this));
        setWallpaper.setOnClickListener(v -> setAsWallpaper());

        updateEnabled();
        updateVideoStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
        boolean active = info != null && info.getComponent().equals(wallpaperComponent());
        wallpaperStatus.setText(active ? R.string.status_active : R.string.status_inactive);
    }

    private void save() {
        sp.edit()
                .putBoolean(Prefs.KEY_LOOP, loop.isChecked())
                .putInt(Prefs.KEY_PLAY_COUNT, playCount.getValue())
                .putBoolean(Prefs.KEY_END_ON_LAST_FRAME, endOnLastFrame.isChecked())
                .putBoolean(Prefs.KEY_SOUND, sound.isChecked())
                .putBoolean(Prefs.KEY_AFTER_UNLOCK, afterUnlock.isChecked())
                .putBoolean(Prefs.KEY_REPLAY, replay.isChecked())
                .putBoolean(Prefs.KEY_REPLAY_AFTER_LOCK, replayAfterLock.isChecked())
                .putBoolean(Prefs.KEY_REPLAY_ON_HOME, replayOnHome.isChecked())
                .apply();
        Prefs.bumpRevision(this);
        updateEnabled();
    }

    private void updateEnabled() {
        boolean finite = !loop.isChecked();
        playCountLabel.setEnabled(finite);
        playCount.setEnabled(finite);
        endOnLastFrame.setEnabled(finite);
        replayAfterLock.setEnabled(replay.isChecked());
        replayOnHome.setEnabled(replay.isChecked() && finite);
    }

    private void updateVideoStatus() {
        boolean hasVideo = Prefs.videoFile(this).isFile();
        if (hasVideo) {
            String name = sp.getString(Prefs.KEY_VIDEO_NAME, "");
            videoStatus.setText(getString(R.string.video_selected, name));
        } else {
            videoStatus.setText(R.string.no_video);
        }
        setWallpaper.setEnabled(hasVideo);
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
        pickVideo.setEnabled(false);
        videoStatus.setText(R.string.copying);
        String name = displayName(uri);
        new Thread(() -> {
            String error = null;
            try {
                copyVideo(uri);
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            String finalError = error;
            runOnUiThread(() -> {
                pickVideo.setEnabled(true);
                if (finalError != null) {
                    Toast.makeText(this, getString(R.string.copy_failed, finalError),
                            Toast.LENGTH_LONG).show();
                } else {
                    sp.edit().putString(Prefs.KEY_VIDEO_NAME, name).apply();
                    Prefs.bumpRevision(this);
                }
                updateVideoStatus();
            });
        }).start();
    }

    private void copyVideo(Uri uri) throws IOException {
        File target = Prefs.videoFile(this);
        File temp = new File(getFilesDir(), "wallpaper_video.tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("пустой поток");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        if (!temp.renameTo(target)) {
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
}
