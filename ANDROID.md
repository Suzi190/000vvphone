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
| 隐藏顶栏状态栏 | 系统状态栏与导航栏全部隐藏；刘海屏使用 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` 让窗口延伸到挖孔区，且**不再按刘海安全区加内边距**——页面直接铺满整屏。页面自己画的 44px 假状态栏里时间和电量分列左右，居中挖孔不会挡住它们；若按安全区加 padding，屏幕顶端会露出一条黑边 |
| 数据导出 | 注入脚本接管 `<a download>` 的 `blob:` / `data:` 下载，分块通过 JS Bridge 写入「下载/VVPhone」；http(s) 直链交给系统 DownloadManager；对导出中的 blob URL 做引用计数保护，页面在 `click()` 后立即 `revokeObjectURL()` 也能完整落盘 |
| 数据导入 | `WebChromeClient.onShowFileChooser` 打开系统文件选择器，支持 JSON 备份 / 主题预设 / 图片等所有 `<input type="file">` |
| 数据存储 | 使用 `https://appassets.androidplatform.net` 承载本地资源，IndexedDB 与 localStorage 在稳定 https 源下工作，和浏览器行为一致 |
| 软键盘 | `windowSoftInputMode="adjustResize"`。Android 11+（API 30+）Activity 已 `setDecorFitsSystemWindows(false)`，系统不会为键盘 resize 窗口，IME 只以 `WindowInsets.Type.ime()` 上报；Android 7~10 拿不到逐帧 IME inset，靠 `adjustResize` 的系统行为避让。API 30+ 的做法是 **WebView 尺寸一动不动**：把键盘高度通过 `evaluateJavascript` 推给页面（`window.__vvKeyboardHeight(px)` → CSS 变量 `--vv-kb`），页面把它变成 `.phone-case` 的 `padding-bottom`，内容整体上移让出键盘，输入框不会被遮住。每次键盘变化只推**一次最终高度**（弹出/高度变化去抖 90ms，收起去抖 30ms），页面用 150ms 过渡平滑跟随；键盘稳定后再把焦点输入框 `scrollIntoView({block:'nearest'})` 滚进可视区 |
| 返回键 | 逐层返回：关闭弹层 → 退出聊天回到应用 → 从其他应用回桌面 → 已在桌面时退到后台 |
| 滑动手势 | 从屏幕左边缘向右滑动即返回上一页：起手点须在边缘 28dp 内，拖动时页面跟手平移，超过 72dp 触发，未达阈值自动弹回；与返回键共用同一套逻辑 |
| 崩溃防护 | 启动入口是极简的 `CrashGateActivity` 闸门：上次崩溃过、或上次启动没走完（原生层崩溃抓不到 Java 堆栈）时，它直接显示可复制的堆栈 + 启动轨迹页，`MainActivity` 一行都不执行；`VvApp` 在进程最早阶段把崩溃记录写进 `last-crash.txt`；WebView 初始化异常与渲染进程被杀都会就地恢复，不再整体闪退 |
| 启动自检 | 启动页显示版本号（含 versionCode）、Android 版本、WebView 包版本，可直接判断是否装上了新包；`BootLog` 把 `gate.open → main.onCreate → splash → webview.creating → webview.ready → loadUrl → page.started` 逐步写入 `files/boot.log`，页面开始渲染才标记 `healthy`；8 秒稳定运行后才清除崩溃计数，避免「一崩就清零」进不了安全模式 |
| 安装包命名 | Release 同时上传 `VVPhone.apk` 与 `VVPhone-<run>.apk`；固定名容易被浏览器/CDN 缓存成旧包，排查「装了没变化」时一律用带版本号的文件 |
| 沉浸式避坑 | `Window.getInsetsController()` 在 `DecorView` 创建前**不是返回 null，而是内部 NPE**（`PhoneWindow#getInsetsController` 直接调 `mDecor.getWindowInsetsController()`）。因此在 `onCreate` 里必须先 `getDecorView()` 强制安装 decor，并整段 `try/catch`；三星 Android 13 曾因这一行必崩 |
| 软键盘避坑 | ① **绝对不要为键盘改 WebView / 容器的尺寸**。WebView 一 resize，Chromium 就要重建绘制 surface，键盘动画期间每帧 resize 都掉帧、露整片黑帧；而且键盘窗口自己的动画和 insets 动画在不少 ROM（三星很典型）上是两套曲线，内容和键盘永远对不齐，怎么调缓动都是「输入框上下跳 + 露黑边」。1.0.9~1.0.11 依次试过线性跟随、方向性缓动、`DISPATCH_MODE_STOP`，全都没用——根因就是 resize 本身。② **不要逐帧推键盘高度**：逐帧改内边距 = 逐帧整页重排，ROM 上报的 inset 只要抖一下，输入框就一窜一窜。只推最终值，平滑交给 CSS 过渡。③ 让位要压在有底色的容器上（`.phone-case` 的 `padding-bottom`），露出的是页面自己的底色；同时用 `--vv-kb-bg`（按当前 `.screen.active` 的背景色算，透明就退成白色）替换 `.phone-case` 原本的黑色，否则那几帧就是一条黑边。④ 前端**不要**再叠一层 `visualViewport` 压缩：`body` 是 `display:flex; align-items:center`，压矮 body 会让 `.phone-case` 上下同时收缩，就是「黑边 + 上下跳」；visualViewport 兜底只保留给没有 `VVAndroid` 桥的普通浏览器。⑤ API 30+ 若某 ROM 仍然把窗口缩矮了，代码会检测容器高度并放弃页面让位，避免双重避让把输入框顶到屏幕中间 |
| 顶栏黑边避坑 | 顶部**绝对不要**按 `displayCutout` / `statusBars` 给容器加 `paddingTop`。padding 加在 WebView 自己身上时 Chromium 不缩 viewport，看不出问题；一旦挪到外层容器，WebView 就真的往下缩，屏幕顶端露出容器黑底，用户会直接说「不全屏了、顶栏成黑条」。要全屏就一路铺满，靠页面内容避开挖孔 |

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
