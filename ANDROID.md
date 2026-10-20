# VVPhone Android 打包说明

本项目是纯静态网页（`index.html` / `script.js` / `style.css`），Android 版通过一个零第三方依赖的原生 WebView 壳运行，并由 GitHub Actions 自动构建 APK。

## 构建产物

- 每次推送到 `main` 会自动触发工作流：`.github/workflows/build-apk.yml`
- 构建完成后可在以下位置下载 APK：
  - Release（推荐，免登录）：<https://github.com/Suzi190/000vvphone/releases/tag/android-apk>
  - Actions → 对应的运行记录 → Artifacts → `VVPhone-Android-APK`（需要登录 GitHub）
- 也可以手动触发：仓库 Actions 页面 → `Build Android APK` → `Run workflow`

## 已实现的 Android 适配

| 要求 | 实现方式 |
| --- | --- |
| 全屏 | 直接使用系统 `@android:style/Theme.Material.NoActionBar`（不再自定义主题，避免旧系统解析新属性失败），窗口背景在代码里设为纯黑，页面注入 `fullscreen-mode` 让 `.phone-case` 铺满屏幕 |
| 沉浸式 | Android 11+ 用 `WindowInsetsController` 隐藏状态栏/导航栏；低版本用 `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`，上滑可临时呼出 |
| 隐藏顶栏状态栏 | 系统状态栏与导航栏全部隐藏；刘海屏使用 `shortEdges` 并把安全区作为内容内边距，避免被挖孔遮挡 |
| 数据导出 | 注入脚本接管 `<a download>` 的 `blob:` / `data:` 下载，分块通过 JS Bridge 写入「下载/VVPhone」；http(s) 直链交给系统 DownloadManager；对导出中的 blob URL 做引用计数保护，页面在 `click()` 后立即 `revokeObjectURL()` 也能完整落盘 |
| 数据导入 | `WebChromeClient.onShowFileChooser` 打开系统文件选择器，支持 JSON 备份 / 主题预设 / 图片等所有 `<input type="file">` |
| 数据存储 | 使用 `https://appassets.androidplatform.net` 承载本地资源，IndexedDB 与 localStorage 在稳定 https 源下工作，和浏览器行为一致 |
| 软键盘 | 监听 IME insets 给 WebView 增加底部内边距，聊天输入框不会被键盘遮住 |
| 返回键 | 逐层返回：关闭弹层 → 退出聊天回到应用 → 从其他应用回桌面 → 已在桌面时退到后台 |
| 滑动手势 | 从屏幕左边缘向右滑动即返回上一页：起手点须在边缘 28dp 内，拖动时页面跟手平移，超过 72dp 触发，未达阈值自动弹回；与返回键共用同一套逻辑 |
| 崩溃防护 | 启动入口是极简的 `CrashGateActivity` 闸门：上次崩溃过、或上次启动没走完（原生层崩溃抓不到 Java 堆栈）时，它直接显示可复制的堆栈 + 启动轨迹页，`MainActivity` 一行都不执行；`VvApp` 在进程最早阶段把崩溃记录写进 `last-crash.txt`；WebView 初始化异常与渲染进程被杀都会就地恢复，不再整体闪退 |
| 启动自检 | 启动页显示版本号（含 versionCode）、Android 版本、WebView 包版本，可直接判断是否装上了新包；`BootLog` 把 `gate.open → main.onCreate → splash → webview.creating → webview.ready → loadUrl → page.started` 逐步写入 `files/boot.log`，页面开始渲染才标记 `healthy`；8 秒稳定运行后才清除崩溃计数，避免「一崩就清零」进不了安全模式 |
| 安装包命名 | Release 同时上传 `VVPhone.apk` 与 `VVPhone-<run>.apk`；固定名容易被浏览器/CDN 缓存成旧包，排查「装了没变化」时一律用带版本号的文件 |
| 沉浸式避坑 | `Window.getInsetsController()` 在 `DecorView` 创建前**不是返回 null，而是内部 NPE**（`PhoneWindow#getInsetsController` 直接调 `mDecor.getWindowInsetsController()`）。因此在 `onCreate` 里必须先 `getDecorView()` 强制安装 decor，并整段 `try/catch`；三星 Android 13 曾因这一行必崩 |

## 本地构建（可选，需要 JDK 17 + Android SDK）

```bash
# 1. 复制网页资源到 assets
mkdir -p android/app/src/main/assets/www
cp index.html script.js style.css manifest.json icon.png favicon.ico android/app/src/main/assets/www/
cp -r i android/app/src/main/assets/www/   # 存在 i/ 目录时

# 2. 构建
cd android
gradle assembleRelease

# 产物：android/app/build/outputs/apk/release/app-release.apk
```

## 安装提示

- 首次安装需在系统设置里允许「安装未知来源应用」。
- APK 使用 debug 签名（CI 缓存了 `debug.keystore`），同签名版本可直接覆盖安装并保留数据。
- 如需长期保留数据，升级前建议先「设置 → 导出备份 (JSON)」，导出文件保存在手机「下载/VVPhone」目录。
