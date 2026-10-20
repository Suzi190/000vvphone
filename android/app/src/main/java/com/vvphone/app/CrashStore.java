package com.vvphone.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 极简崩溃记录器。
 *
 * 由 {@link VvApp#attachBaseContext(Context)} 最早安装，所以即使 MainActivity 都还没创建起来，
 * 崩溃堆栈也会落盘并计数；下次启动时主界面会读取并展示，便于定位「一开就闪退」的问题。
 */
final class CrashStore {

    private static final String PREFS = "vv_crash";
    private static final String KEY_COUNT = "count";
    private static final String KEY_LAST_AT = "last_at";
    private static final String FILE_NAME = "last-crash.txt";

    private CrashStore() {
    }

    static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record(context, error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    static void record(Context context, Throwable error) {
        try {
            try (FileOutputStream out = new FileOutputStream(crashFile(context))) {
                out.write((header() + describe(error)).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // 记录失败不能再抛异常
        }
        try {
            SharedPreferences prefs = prefs(context);
            // 必须用 commit()：apply() 是异步落盘，崩溃后进程立刻被系统杀死，
            // 计数经常写不进去（这次实测 last-crash.txt 有内容但「崩溃次数: 0」）。
            prefs.edit()
                    .putInt(KEY_COUNT, prefs.getInt(KEY_COUNT, 0) + 1)
                    .putLong(KEY_LAST_AT, System.currentTimeMillis())
                    .commit();
        } catch (Throwable ignored) {
            // 同上
        }
    }

    static int crashCount(Context context) {
        try {
            return prefs(context).getInt(KEY_COUNT, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static long lastCrashAt(Context context) {
        try {
            return prefs(context).getLong(KEY_LAST_AT, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 读取上次崩溃信息；超过 10 分钟的记录会被当作过时直接丢弃。 */
    static String readLastCrash(Context context) {
        try {
            File file = crashFile(context);
            if (!file.isFile()) {
                return null;
            }
            long age = System.currentTimeMillis() - file.lastModified();
            if (age < 0 || age > 10 * 60 * 1000L) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return null;
            }
            int size = (int) Math.min(file.length(), 64 * 1024L);
            byte[] buffer = new byte[size];
            int read;
            try (FileInputStream in = new FileInputStream(file)) {
                read = in.read(buffer);
            }
            if (read <= 0) {
                return null;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void clear(Context context) {
        try {
            prefs(context).edit().clear().commit();
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            crashFile(context).delete();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File crashFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        return new File(dir, FILE_NAME);
    }

    private static String header() {
        return "time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "\nmodel: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "\nandroid: " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")\n\n";
    }

    static String describe(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        error.printStackTrace(printer);
        printer.flush();
        return writer.toString();
    }
}
