package com.dead.unlockvideo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Отдаёт системе кадр покоя обоев — миниатюру для экранов, где обои показываются картинкой
 * (Android 16, WallpaperDescription). Читать могут только системные приложения; каждое
 * обращение пишется в журнал.
 */
public class StillProvider extends ContentProvider {
    static final String AUTHORITY = "com.dead.unlockvideo.still";

    /** Адрес миниатюры; версия в адресе меняется вместе с кадром. */
    static Uri uri(Context context) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("still.jpg")
                .appendQueryParameter("v", String.valueOf(restStill(context).lastModified()))
                .build();
    }

    /** Кадр покоя: последний, если видео так и остаётся в конце, иначе первый. */
    private static File restStill(Context context) {
        Prefs cfg = Prefs.load(context);
        boolean atEnd = cfg.lockAction == Prefs.LOCK_KEEP && cfg.endOnLastFrame && !cfg.infinite();
        return atEnd ? Stills.last(context) : Stills.first(context);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = getContext();
        String caller = getCallingPackage();
        if (!"r".equals(mode) || !callerIsSystem(context)) {
            EventLog.add(context, "миниатюра: отказано " + caller);
            throw new SecurityException("Миниатюра доступна только системе");
        }
        EventLog.add(context, "миниатюра: читает " + caller);
        Stills.ensure(context);
        return ParcelFileDescriptor.open(restStill(context), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static boolean callerIsSystem(Context context) {
        int uid = Binder.getCallingUid();
        if (uid < Process.FIRST_APPLICATION_UID) return true;
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages) {
            try {
                if ((pm.getApplicationInfo(name, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
