# OpenList Sorter Android

这是同级 Qt5 项目 `/data/projects/OpenListSorter` 的原生 Android 版本，使用 Java、Android View、Material Components 和 OkHttp。文件操作语义、分页、tag 暂存、WebP 提交规则与 Qt 版保持一致；触屏布局、系统文件保存和生命周期处理按 Android 平台调整。

## 功能

- Token key 或账号密码登录，支持记住连接、自动登录、注销和连接诊断。
- OpenList API：`/api/auth/login`、`/api/fs/list`、`/api/fs/get`、`/api/fs/put`、`/api/fs/remove`、`/api/fs/move`、`/api/fs/copy`。
- 远程目录选择、完整目录文件列表、每页 20 项和跨页上一张/下一张。
- 文件状态包括未处理、待保存、已提交和失败；tag 选择保存在完整列表中。
- 单 tag 移动、多 tag 复制；失败项保留，可再次执行“保存待提交”。
- 保存时转 WebP：使用转换输入的 SHA-256 作为文件名，目标目录先去重，所有目标上传成功后才删除源文件。
- 下载当前文件使用 Android Storage Access Framework，不需要存储权限；文件写入在后台线程执行。
- 图片预览支持双指缩放、拖动和双击复位；视频交给系统播放器。

## 图片格式与超大图预览

图片解码按以下顺序执行：

1. Android `ImageDecoder`（API 28 及以上）。
2. Android `BitmapFactory`。
3. AAR 内置的 libheif/libde265 软件解码器（`com.aliyun:libheif:1.0.1`），用于 HEIC/HEIF 后备。
4. Android 媒体栈 `MediaMetadataRetriever` 后备路径。

内置 libheif 包含 `arm64-v8a`、`armeabi-v7a`、`x86` 和 `x86_64`，用于 HEIC/HEIF 系统解码不可用的情况；AVIF 仍由 Android 平台/媒体解码路径处理。相关许可证见 `THIRD_PARTY_NOTICES.md` 和 `LICENSES/LGPL-libheif.txt`。

交互预览最长边限制为 4096 像素，磁盘缓存重新载入限制为 2048 像素；显式执行 WebP 转换时才保留源图片宽高。这样可正常显示由数百个 HEVC tile 组成、展开后宽度达到数万像素的 HEIF grid 图片，而不会为一个预览 Bitmap 一次申请数百 MiB。BitmapFactory 路径会采样并处理 EXIF 方向；旧版 libheif 的 JNI 接口只能输出全尺寸 Bitmap，因此超大预览会优先使用 Android 平台/媒体缩放路径，并跳过可预见的超大 native 分配。解码、JPEG 缓存和 WebP 编码捕获内存不足与编码错误，失败时回退到文件信息，不影响普通分类。

## 并发与缓存

- OpenList 前台/提交 I/O 使用有界 6 线程池，预热下载使用独立的低优先级 4 线程池，不再使用无界 cached thread pool，也不会由预热占满交互请求。
- 当前页原始数据预热默认 3 路，可在“更多 > 性能与缓存”设置为 1 到 10；预热不进入全尺寸解码队列。
- 批量提交默认 2 路，可设置为 1 到 4；每个文件的多目标上传保持顺序，相同内容/输出名在批次内串行去重，确保删除源文件前所有目标均成功。
- 当前预览、分页预热和转换对同一远程文件共享在途下载；同一文件的重复预览请求会合并全分辨率解码，避免旋转/快速重绘产生双份像素内存。
- 大文件下载取消原 20 秒短总超时，改为 30 分钟硬上限，并保留连接、读写超时；界面进度更新最多约每 120 ms 一次。
- 原始远程字节缓存为 `.bin`；HEIF/AVIF 转换输入缓存为满质量 `.jpg`，与交互预览 Bitmap 分离。
- 缓存 key 包含远程路径、大小、修改时间、名称和缓存版本。
- 磁盘缓存上限 256 MiB，按最近使用时间淘汰；解码图片内存 LRU 上限 24 MiB。
- 缓存写入使用线程锁、唯一临时文件和 rename，防止并发写损坏。
- 缓存跨分类模式复用，不再进入/退出时无条件删除；设置页显示缓存统计并提供显式清理。

## 界面

应用强制横屏（landscape），主工作区在横屏内按可用宽度排列：宽屏使用三栏工作区，窄横屏使用紧凑操作控件和可滚动面板：

- 顶部：标题 + 连接状态 + 更多/注销。
- 任务行：源目录选择与进入分类模式，覆盖/转 WebP 选项、统计与下载/转换/删除。
- 左栏「当前目录文件」：Material Card + `RecyclerView` 原生列表，显示序号、状态、文件名与已选 tag，当前项高亮描边。
- 中栏「当前文件」：文件名、文件信息、缩略图预览；点按预览区（或提示条）进入全屏查看页。
- 右栏「分类标签」：Material `Chip`（FilterChip）可勾选标签，新增/管理/清空与「提交已选分类」。
- 全屏查看页（`PreviewActivity`）：深色背景，顶部返回 + 上一张/下一张，双指缩放、拖动、双击复位，左右滑动切换图片；视频交给系统播放器。
- 顶部/底部与刘海（display cutout）安全区通过 window insets 预留；顶部状态栏默认隐藏，可从边缘暂时呼出，底部导航/手势区域不会遮挡内容。
- 操作按钮最小高度为 48dp；窄侧栏使用图标按钮和无障碍描述。

## 构建环境

工具链位于 `/data/env/android`：

```text
/data/env/android/jdk-17
/data/env/android/gradle-8.7
/data/env/android/sdk
```

项目使用 compileSdk/targetSdk 35、minSdk 26、AGP 8.5.2 和 Java 17。

## 构建与离线验证

首次解析新增依赖后，完整验证可以离线运行，不需要模拟器或真实设备：

```bash
export JAVA_HOME=/data/env/android/jdk-17
export ANDROID_HOME=/data/env/android/sdk
/data/env/android/gradle-8.7/bin/gradle --offline --no-daemon \
  testDebugUnitTest assembleDebug assembleRelease lintDebug verifyLibheifPackaging
```

Robolectric Native Graphics 会自动离线渲染并检查：

- 800x360 横屏连接页。
- 640x360 手机横屏三栏主界面（含未处理、待保存、已提交文件与 tag fixture）。
- 891x411 横屏三栏主界面。
- 1000x700 平板三栏主界面。
- 640dp 宽的横屏「性能与缓存」设置对话框。

测试断言截图非空、像素内容足够、响应式面板位置正确，以及可见按钮至少 48dp。PNG 输出位于：

```text
app/build/reports/screenshots/
```

APK 输出位于：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

Robolectric 的宿主 JVM 不能加载面向 Android/Bionic 的 JNI `.so`。因此离线测试验证真实 HEIF 容器识别、libheif Java API 存在和 APK 四 ABI 打包；实际 JNI 解码需要在 Android Runtime 验证。

## 当前边界

- 显式执行原分辨率转换仍有固有内存成本；极大图片可能返回可恢复的内存不足错误，交互预览不受此限制。
- 下载、转换和上传仍需要在处理阶段持有完整压缩字节；磁盘缓存已限制长期占用，但超大单文件的流式转换需要后续单独设计。
- 外部视频播放器无法附带 OpenList `Authorization` 请求头；需要鉴权的 raw URL 可能无法由第三方播放器直接打开。
