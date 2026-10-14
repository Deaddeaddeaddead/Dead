package com.dead.unlockvideo;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Короткий журнал событий обоев. Запись — на фоновом потоке, главный поток только
 * формирует строку; файл обрезается до MAX_LINES изредка, а не при каждой записи.
 */
final class EventLog {
    private static final int MAX_LINES = 150;
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    private static Handler writer;
    /** Сколько строк дописано с последней обрезки. */
    private static int appended;

    private EventLog() {
    }

    private static synchronized Handler writer() {
        if (writer == null) {
            HandlerThread thread = new HandlerThread("event-log");
            thread.start();
            writer = new Handler(thread.getLooper());
        }
        return writer;
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "events.log");
    }

    static void add(Context context, String event) {
        String line;
        synchronized (TIME) {
            line = TIME.format(new Date()) + "  " + event + "\n";
        }
        File file = file(context);
        writer().post(() -> {
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                return;
            }
            if (++appended >= MAX_LINES) {
                appended = 0;
                trim(file);
            }
        });
    }

    /** Весь журнал, включая ещё не дописанное (ждёт фоновую запись, но не дольше 1 с). */
    static String text(Context context) {
        File file = file(context);
        String[] result = {""};
        CountDownLatch done = new CountDownLatch(1);
        writer().post(() -> {
            try {
                trim(file);
                result[0] = String.join("\n", read(file));
            } catch (IOException ignored) {
            }
            done.countDown();
        });
        try {
            done.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    static void clear(Context context) {
        File file = file(context);
        writer().post(() -> {
            appended = 0;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        });
    }

    /** Оставляет последние MAX_LINES строк. Только на потоке записи. */
    private static void trim(File file) {
        try {
            List<String> lines = read(file);
            if (lines.size() <= MAX_LINES) return;
            List<String> tail = lines.subList(lines.size() - MAX_LINES, lines.size());
            Files.write(file.toPath(), (String.join("\n", tail) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static List<String> read(File file) throws IOException {
        if (!file.isFile()) return new ArrayList<>();
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n")));
        lines.removeIf(String::isEmpty);
        return lines;
    }
}
