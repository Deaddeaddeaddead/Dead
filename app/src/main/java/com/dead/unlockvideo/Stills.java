package com.dead.unlockvideo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Неподвижные кадры видео — первый и последний. Готовятся один раз на каждое видео и
 * показываются мгновенно: в превью (вместо видео) и пока плеер не выдал первый кадр.
 */
final class Stills {
    private static final int QUALITY = 95;
    /** Запас под увеличение эффектами (масштаб + параллакс). */
    private static final float ZOOM_RESERVE = 1.4f;

    private Stills() {
    }

    static File first(Context context) {
        return new File(context.getFilesDir(), "still_first.jpg");
    }

    static File last(Context context) {
        return new File(context.getFilesDir(), "still_last.jpg");
    }

    private static boolean fresh(File still, File video) {
        return still.isFile() && still.lastModified() >= video.lastModified();
    }

    /** Готовит кадры, если их нет или видео новее. Долго — только не на главном потоке. */
    static synchronized boolean ensure(Context context) {
        File video = Prefs.videoFile(context);
        if (!video.isFile()) return false;
        File first = first(context);
        File last = last(context);
        if (fresh(first, video) && fresh(last, video)) return true;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = duration == null ? 0 : Long.parseLong(duration);
            save(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST), first);
            // Как у обоев: последний кадр — за 50 мс до конца.
            save(retriever.getFrameAtTime(Math.max(0, durationMs - 50) * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST), last);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static void save(Bitmap frame, File target) throws IOException {
        if (frame == null) throw new IOException("кадр не получен");
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
        } finally {
            frame.recycle();
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("не удалось сохранить кадр");
        }
    }

    /**
     * Кадр, уменьшенный ровно настолько, чтобы закрыть область width×height с запасом
     * под эффекты (без потери чёткости). null — кадра нет.
     */
    static Bitmap load(File file, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        float need = ZOOM_RESERVE * Math.max(width / (float) options.outWidth,
                height / (float) options.outHeight);
        int sample = 1;
        while (need > 0f && need * sample * 2 <= 1f && sample < 16) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }
}
