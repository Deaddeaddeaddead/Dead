package com.dead.unlockvideo;

import android.content.Context;

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

/** Короткий журнал событий обоев — чтобы разобраться, что происходило на телефоне. */
final class EventLog {
    private static final int MAX_LINES = 120;
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    private EventLog() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "events.log");
    }

    static synchronized void add(Context context, String event) {
        File file = file(context);
        try {
            String line = TIME.format(new Date()) + "  " + event + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            if (file.length() > MAX_LINES * 160L) {
                List<String> lines = read(file);
                List<String> tail = lines.subList(Math.max(0, lines.size() - MAX_LINES),
                        lines.size());
                Files.write(file.toPath(), (String.join("\n", tail) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    static synchronized String text(Context context) {
        try {
            List<String> lines = read(file(context));
            return lines.isEmpty() ? "" : String.join("\n", lines);
        } catch (IOException e) {
            return "";
        }
    }

    static synchronized void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
    }

    private static List<String> read(File file) throws IOException {
        if (!file.isFile()) return new ArrayList<>();
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n")));
        lines.removeIf(String::isEmpty);
        return lines;
    }
}
