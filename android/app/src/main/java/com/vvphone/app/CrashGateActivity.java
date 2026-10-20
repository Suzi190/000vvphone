package com.vvphone.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 启动闸门。
 *
 * 如果 MainActivity 在 onCreate 阶段就崩（资源解析、WebView、类初始化……），进程会直接被杀，
 * 用户只看到「点了图标就闪退」，任何界面都来不及显示。
 * 所以真正没风险的入口放在这个尽量「什么都不做」的 Activity 上：
 * <ul>
 *   <li>没有异常记录：立刻转发到 {@link MainActivity}，几乎无感（透明转发 + 无过渡动画）。</li>
 *   <li>上次崩溃过、或上次启动没走完：停留在这个原生页面显示崩溃堆栈与启动轨迹，可复制、可清空后重试。</li>
 * </ul>
 */
public class CrashGateActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        } catch (Throwable ignored) {
            // 忽略
        }

        int count;
        String crashLog;
        String bootTail;
        boolean bootCompleted;
        try {
            count = CrashStore.crashCount(this);
            crashLog = CrashStore.readLastCrash(this);
            bootTail = BootLog.tail(this);
            bootCompleted = BootLog.lastBootCompleted(this);
        } catch (Throwable error) {
            count = 0;
            crashLog = CrashStore.describe(error);
            bootTail = null;
            bootCompleted = true;
        }

        // 上次崩溃过，或上次启动没走到「页面加载完成」（原生层崩溃抓不到 Java 堆栈）→ 先把现场摆出来
        if (crashLog != null || !bootCompleted) {
            showDiagnostics(count, crashLog, bootTail);
            return;
        }

        BootLog.begin(this, "boot");
        BootLog.mark(this, "gate.open");
        openMain();
    }

    private void openMain() {
        try {
            startActivity(new Intent(this, MainActivity.class));
        } catch (Throwable error) {
            CrashStore.record(this, error);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void showDiagnostics(int count, String crashLog, String bootTail) {
        try {
            StringBuilder builder = new StringBuilder();
            builder.append("app: ").append(versionLabel())
                    .append("\nandroid: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                    .append("\nmodel: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append("\n崩溃次数: ").append(count)
                    .append("\n\n--- 启动轨迹 (boot.log) ---\n")
                    .append(bootTail == null ? "（无记录）" : bootTail.trim())
                    .append("\n\n--- 崩溃堆栈 (last-crash.txt) ---\n")
                    .append(crashLog == null ? "（没有 Java 堆栈：崩溃发生在原生层或进程被直接终止）" : crashLog);
            String summary = builder.toString();

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.parseColor("#101014"));
            root.setPadding(dp(16), dp(28), dp(16), dp(16));

            TextView title = new TextView(this);
            title.setText("VVPhone 启动异常（已暂停加载）");
            title.setTextColor(Color.WHITE);
            title.setTextSize(18f);
            root.addView(title);

            TextView hint = new TextView(this);
            hint.setText("这次先不加载页面，把上次启动的最后一步和崩溃信息留在下面。\n点「复制信息」发给开发者，点「清空记录并重试」可再试一次。");
            hint.setTextColor(Color.parseColor("#8A8A99"));
            hint.setTextSize(12f);
            hint.setPadding(0, dp(8), 0, dp(8));
            root.addView(hint);

            TextView body = new TextView(this);
            body.setText(summary);
            body.setTextColor(Color.parseColor("#C8C8D2"));
            body.setTextSize(11f);
            body.setTextIsSelectable(true);

            ScrollView scroll = new ScrollView(this);
            scroll.addView(body);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);

            Button copy = new Button(this);
            copy.setText("复制信息");
            copy.setAllCaps(false);
            copy.setOnClickListener(v -> copyToClipboard(summary));
            buttons.addView(copy, weightParams());

            Button retry = new Button(this);
            retry.setText("清空记录并重试");
            retry.setAllCaps(false);
            retry.setOnClickListener(v -> {
                CrashStore.clear(this);
                BootLog.clear(this);
                BootLog.begin(this, "boot");
                BootLog.mark(this, "gate.retry");
                openMain();
            });
            buttons.addView(retry, weightParams());

            root.addView(buttons);
            setContentView(root);
        } catch (Throwable ignored) {
            // 诊断页本身失败时保持黑屏，绝不二次崩溃
        }
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("VVPhone", text));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
            Toast.makeText(this, "复制失败，请截图", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private String versionLabel() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
