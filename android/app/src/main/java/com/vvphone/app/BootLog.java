package com.vvphone.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 启动流程逐步打点。
 *
 * Java 未捕获异常处理器只能抓到 Java 层崩溃；如果 WebView / GPU 驱动在 native 层 SIGSEGV，
 * 进程会直接消失，什么都来不及记录。这种情况下「最后一条打点」就是唯一线索，
 * 所以每走一步都落盘（内部存储，无需权限，立即 flush）。
 */
final class BootLog {

    private static final String FILE_NAME = "boot.log";
    private static final long MAX_BYTES = 32 * 1024L;
    private static final String HEALTHY = "healthy";

    private BootLog() {
    }

    static synchronized void begin(Context context, String label) {
        write(context, "\n=== " + label + " " + time() + " ===");
    }

    static synchronized void mark(Context context, String step) {
        write(context, time() + " " + step);
    }

    static synchronized void clear(Context context) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file(context).delete();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 上一次启动是否走到了「页面加载完成」；没有日志（首次启动）视为正常。 */
    static synchronized boolean lastBootCompleted(Context context) {
        String tail = tail(context);
        if (tail == null || tail.isEmpty()) {
            return true;
        }
        int start = tail.lastIndexOf("=== boot");
        String segment = start >= 0 ? tail.substring(start) : tail;
        return segment.contains(HEALTHY);
    }

    static synchronized String tail(Context context) {
        try {
            File file = file(context);
            if (!file.isFile()) {
                return null;
            }
            long length = file.length();
            if (length <= 0) {
                return null;
            }
            int size = (int) Math.min(length, 8 * 1024L);
            byte[] buffer = new byte[size];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(length - size);
                raf.readFully(buffer);
            }
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static void write(Context context, String text) {
        try {
            File file = file(context);
            if (file.length() > MAX_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Throwable ignored) {
            // 打点失败不能影响主流程
        }
    }

    private static String time() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
