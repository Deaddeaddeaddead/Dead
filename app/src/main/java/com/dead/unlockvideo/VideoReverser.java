package com.dead.unlockvideo;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Делает из видео его копию задом наперёд для плавного возврата к началу.
 *
 * MediaPlayer не умеет играть назад, поэтому видео перекодируется так, что каждый кадр
 * ключевой, а затем кадры переписываются в файл в обратном порядке. Вперёд обои всегда
 * играют оригинал, этот файл нужен только на время перемотки назад.
 */
final class VideoReverser {
    private static final String OUT_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long TIMEOUT_US = 10_000;

    interface Progress {
        void onProgress(int percent);
    }

    static final class Result {
        /**
         * Время последнего кадра оригинала. Позиция r в обратном видео соответствует
         * позиции lastFrameMs - r в оригинале.
         */
        final long lastFrameMs;
        /** Итог проверки кадров — для журнала. */
        String check = "";

        Result(long lastFrameMs) {
            this.lastFrameMs = lastFrameMs;
        }
    }

    private VideoReverser() {
    }

    static Result build(File input, File output, Progress progress) throws IOException {
        File intra = new File(output.getPath() + ".intra");
        try {
            int rotation = transcodeAllIntra(input, intra, progress);
            Result result = writeBackward(intra, output, rotation);
            result.check = verify(input, output, result.lastFrameMs);
            return result;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            intra.delete();
        }
    }

    /** Перекодирует видео так, что каждый кадр — ключевой. Возвращает поворот видео. */
    private static int transcodeAllIntra(File input, File output, Progress progress)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        Surface encoderSurface = null;
        GlUtil.Egl egl = null;
        SurfaceTexture texture = null;
        Surface decoderSurface = null;
        HandlerThread frameThread = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            MediaFormat inFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    inFormat = f;
                    break;
                }
            }
            if (inFormat == null) throw new IOException("в файле нет видеодорожки");

            int srcWidth = inFormat.getInteger(MediaFormat.KEY_WIDTH);
            int srcHeight = inFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int rotation = inFormat.containsKey(MediaFormat.KEY_ROTATION)
                    ? inFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
            long durationUs = inFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            int fps = Math.round(number(inFormat, MediaFormat.KEY_FRAME_RATE, 30f));
            if (fps <= 0 || fps > 240) fps = 30;

            encoder = MediaCodec.createEncoderByType(OUT_MIME);
            MediaCodecInfo.CodecCapabilities caps =
                    encoder.getCodecInfo().getCapabilitiesForType(OUT_MIME);
            MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();

            // Сохраняем исходное разрешение, если кодировщик его тянет; иначе уменьшаем
            // ровно настолько, насколько нужно, сохраняя пропорции.
            int width = 0;
            int height = 0;
            for (float scale = 1f; scale > 0.2f; scale -= 0.05f) {
                int w = align(srcWidth * scale, video.getWidthAlignment());
                int h = align(srcHeight * scale, video.getHeightAlignment());
                if (video.isSizeSupported(w, h)) {
                    width = w;
                    height = h;
                    break;
                }
            }
            if (width == 0) throw new IOException("кодировщик не поддерживает такой размер видео");

            MediaFormat outFormat = MediaFormat.createVideoFormat(OUT_MIME, width, height);
            outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            // Все кадры ключевые, поэтому битрейт нужен высокий — берём сколько позволяет кодировщик.
            long wanted = (long) width * height * fps;
            int maxBitrate = video.getBitrateRange().getUpper();
            outFormat.setInteger(MediaFormat.KEY_BIT_RATE,
                    (int) Math.min(maxBitrate, Math.max(10_000_000L, wanted)));
            MediaCodecInfo.EncoderCapabilities encoderCaps = caps.getEncoderCapabilities();
            if (encoderCaps.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
                outFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
                outFormat.setInteger(MediaFormat.KEY_QUALITY,
                        encoderCaps.getQualityRange().getUpper());
            } else if (encoderCaps.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                outFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            }
            outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0); // все кадры ключевые

            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            egl = new GlUtil.Egl(encoderSurface, true);
            GlUtil.FrameDrawer renderer = new GlUtil.FrameDrawer();
            texture = new SurfaceTexture(renderer.textureId);
            FrameWaiter waiter = new FrameWaiter();
            frameThread = new HandlerThread("reverser-frames");
            frameThread.start();
            texture.setOnFrameAvailableListener(waiter, new Handler(frameThread.getLooper()));
            decoderSurface = new Surface(texture);

            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(inFormat, decoderSurface, null, 0);
            decoder.start();

            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(rotation);
            int muxerTrack = -1;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            float[] matrix = new float[16];
            Bundle syncFrame = new Bundle();
            syncFrame.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            boolean inputDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            int lastPercent = -1;

            while (!encoderDone) {
                // Сначала забираем всё готовое у кодировщика, чтобы он не встал.
                while (true) {
                    int index = encoder.dequeueOutputBuffer(encInfo, decoderDone ? TIMEOUT_US : 0);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new IOException("кодировщик сменил формат");
                        muxerTrack = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    } else if (index >= 0) {
                        ByteBuffer data = encoder.getOutputBuffer(index);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            encInfo.size = 0;
                        }
                        if (encInfo.size > 0 && muxerStarted && data != null) {
                            data.position(encInfo.offset);
                            data.limit(encInfo.offset + encInfo.size);
                            muxer.writeSampleData(muxerTrack, data, encInfo);
                        }
                        encoder.releaseOutputBuffer(index, false);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                            break;
                        }
                    }
                }
                if (encoderDone) break;

                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                if (!decoderDone) {
                    int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (index >= 0) {
                        boolean render = info.size != 0;
                        decoder.releaseOutputBuffer(index, render);
                        if (render) {
                            waiter.await();
                            texture.updateTexImage();
                            texture.getTransformMatrix(matrix);
                            renderer.draw(matrix, width, height, 1f, 1f);
                            // Каждый кадр — самостоятельный (IDR), чтобы декодер не мог
                            // переставить кадры при обратном порядке.
                            encoder.setParameters(syncFrame);
                            egl.swap(info.presentationTimeUs * 1000);
                            if (durationUs > 0 && progress != null) {
                                int percent = (int) Math.min(90,
                                        info.presentationTimeUs * 90 / durationUs);
                                if (percent != lastPercent) {
                                    lastPercent = percent;
                                    progress.onProgress(percent);
                                }
                            }
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoder.signalEndOfInputStream();
                            decoderDone = true;
                        }
                    }
                }
            }
            if (!muxerStarted) throw new IOException("не получилось ни одного кадра");
            return rotation;
        } finally {
            extractor.release();
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) { }
                decoder.release();
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (RuntimeException ignored) { }
                encoder.release();
            }
            if (muxer != null) {
                try {
                    if (muxerStarted) muxer.stop();
                } catch (RuntimeException ignored) {
                }
                muxer.release();
            }
            if (decoderSurface != null) decoderSurface.release();
            if (texture != null) texture.release();
            if (egl != null) egl.release();
            if (encoderSurface != null) encoderSurface.release();
            if (frameThread != null) frameThread.quitSafely();
        }
    }

    /**
     * Переписывает кадры (все ключевые) в обратном порядке. Кадры читаются строго
     * последовательно во временный файл, без перемоток — так результат не зависит
     * от того, как конкретный телефон ищет кадры в видео.
     */
    private static Result writeBackward(File intra, File output, int rotation)
            throws IOException {
        File raw = new File(output.getPath() + ".frames");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            extractor.setDataSource(intra.getAbsolutePath());
            extractor.selectTrack(0);
            MediaFormat format = extractor.getTrackFormat(0);
            int width = format.getInteger(MediaFormat.KEY_WIDTH);
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(1 << 20, width * height * 2));
            byte[] bytes = new byte[buffer.capacity()];

            // 1. Все кадры по порядку — во временный файл; запоминаем, где какой лежит.
            List<long[]> frames = new ArrayList<>(); // {время, смещение, размер}
            long offset = 0;
            try (FileOutputStream out = new FileOutputStream(raw)) {
                int size;
                while ((size = extractor.readSampleData(buffer, 0)) >= 0) {
                    if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                        throw new IOException(
                                "кодировщик телефона не умеет делать все кадры ключевыми");
                    }
                    buffer.position(0);
                    buffer.get(bytes, 0, size);
                    out.write(bytes, 0, size);
                    frames.add(new long[] {extractor.getSampleTime(), offset, size});
                    offset += size;
                    buffer.clear();
                    extractor.advance();
                }
            }
            int count = frames.size();
            if (count == 0) throw new IOException("пустое видео");
            long firstUs = frames.get(0)[0];
            long lastUs = frames.get(count - 1)[0] - firstUs;

            // 2. Пишем их в обратном порядке: последний кадр становится первым.
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(rotation);
            int track = muxer.addTrack(format);
            muxer.start();
            muxerStarted = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try (RandomAccessFile in = new RandomAccessFile(raw, "r")) {
                for (int k = count - 1; k >= 0; k--) {
                    long[] frame = frames.get(k);
                    int size = (int) frame[2];
                    in.seek(frame[1]);
                    in.readFully(bytes, 0, size);
                    buffer.clear();
                    buffer.put(bytes, 0, size);
                    buffer.flip();
                    info.set(0, size, lastUs - (frame[0] - firstUs),
                            MediaCodec.BUFFER_FLAG_KEY_FRAME);
                    muxer.writeSampleData(track, buffer, info);
                }
            }
            muxer.stop();
            muxerStarted = false;
            return new Result(lastUs / 1000);
        } finally {
            extractor.release();
            if (muxer != null) {
                try {
                    if (muxerStarted) muxer.stop();
                } catch (RuntimeException ignored) {
                }
                muxer.release();
            }
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /**
     * Проверяет, что видео действительно перевёрнуто: первый кадр обратного видео должен
     * быть похож на последний кадр оригинала больше, чем на первый.
     */
    private static String verify(File original, File reversed, long lastFrameMs)
            throws IOException {
        long endUs = lastFrameMs * 1000;
        Bitmap originalFirst = thumb(original, 0);
        Bitmap originalLast = thumb(original, endUs);
        Bitmap reversedFirst = thumb(reversed, 0);
        if (originalFirst == null || originalLast == null || reversedFirst == null) {
            return "проверка кадров не удалась (не прочитались кадры)";
        }
        double toLast = difference(reversedFirst, originalLast);
        double toFirst = difference(reversedFirst, originalFirst);
        String numbers = String.format(java.util.Locale.ROOT,
                "отличие от последнего кадра %.1f, от первого %.1f", toLast, toFirst);
        if (difference(originalFirst, originalLast) < 4) {
            return "проверка кадров: первый и последний кадры видео одинаковые, " + numbers;
        }
        if (toLast > toFirst) {
            throw new IOException("обратное видео получилось в прямом порядке (" + numbers + ")");
        }
        return "проверка кадров пройдена: " + numbers;
    }

    private static Bitmap thumb(File file, long timeUs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            return frame == null ? null : Bitmap.createScaledBitmap(frame, 32, 32, true);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Среднее отличие цвета по каналам, 0..255. */
    private static double difference(Bitmap a, Bitmap b) {
        long sum = 0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int p = a.getPixel(x, y);
                int q = b.getPixel(x, y);
                sum += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF))
                        + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
            }
        }
        return sum / (32.0 * 32 * 3);
    }

    private static int align(float value, int alignment) {
        int a = Math.max(2, alignment);
        return Math.max(a, Math.round(value / a) * a);
    }

    private static float number(MediaFormat format, String key, float fallback) {
        if (!format.containsKey(key)) return fallback;
        try {
            return format.getInteger(key);
        } catch (ClassCastException e) {
            return format.getFloat(key);
        }
    }

    private static final class FrameWaiter implements SurfaceTexture.OnFrameAvailableListener {
        private final Object lock = new Object();
        private boolean available;

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            synchronized (lock) {
                available = true;
                lock.notifyAll();
            }
        }

        void await() throws IOException {
            synchronized (lock) {
                long deadline = SystemClock.uptimeMillis() + 3000;
                while (!available) {
                    long left = deadline - SystemClock.uptimeMillis();
                    if (left <= 0) throw new IOException("декодер не отдал кадр");
                    try {
                        lock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("прервано");
                    }
                }
                available = false;
            }
        }
    }
}
