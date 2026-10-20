package com.vvphone.app;

import android.app.Application;
import android.content.Context;

/**
 * 只做一件事：在进程最早阶段装好崩溃记录，保证「点开就闪退」时也能留下堆栈。
 */
public class VvApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            CrashStore.install(base);
        } catch (Throwable ignored) {
            // 记录器本身不能影响启动
        }
    }
}
