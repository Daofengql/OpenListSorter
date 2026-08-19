# OpenList Sorter Android

这是同级 Qt5 OpenList Sorter 的原生 Android 版本，使用纯 Java + Android View 实现，不依赖 AndroidX 或第三方网络库。当前行为按 Qt 版的 OpenList API 和分类提交规则迁移。

## 已实现

- Token key 和账号密码登录，记住我、自动登录、注销。
- OpenList API：`/api/auth/login`、`/api/fs/list`、`/api/fs/get`、`/api/fs/put`、`/api/fs/remove`、`/api/fs/move`、`/api/fs/copy`。
- 远程目录选择：根目录、上一级、刷新、进入子目录、选择当前目录。
- 分类模式：完整目录文件列表，每页 20 个文件，编号保持全局序号。
- 文件状态：未处理、待保存、已提交、失败；tag 选择保存在完整列表中，不随翻页丢失。
- 单 tag 普通文件移动，多 tag 普通文件按目标目录逐个复制。
- 图片原始字节缓存、图片预览、视频交给系统播放器、文件信息页。
- 图片预览支持双指缩放、拖动、双击复位。
- 保存时转 WebP：内容 SHA-256 作为 `<sha256>.webp` 文件名，目标目录先去重，全部上传成功后删除源文件。
- 删除当前文件只更新本地列表，不重新拉取整个源目录。

Android 解码能力由系统 `BitmapFactory/ImageDecoder` 提供。HEIC/HEIF/AVIF 能否预览或转换取决于设备系统解码器；不支持时仍可对文件进行普通分类。

## 构建环境

构建工具已安装在 `/data/env/android`：

```text
/data/env/android/jdk-17
/data/env/android/gradle-8.7
/data/env/android/sdk
```

SDK 使用 platform 35、platform-tools 和 Build Tools 35（Gradle 也会按 Android Gradle Plugin 需要补充 Build Tools 34）。

## 构建

```bash
export JAVA_HOME=/data/env/android/jdk-17
export ANDROID_HOME=/data/env/android/sdk
/data/env/android/gradle-8.7/bin/gradle --no-daemon assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 运行

连接 Android 设备或启动模拟器后：

```bash
/data/env/android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
/data/env/android/sdk/platform-tools/adb shell am start -n com.openlistsorter.app/.MainActivity
```

应用使用 Android 应用专属的 `Download/` 目录保存“下载当前”文件，不需要申请外部存储权限。预览 raw 和解码图缓存位于应用 cache 目录，进入分类模式和退出分类模式时会清理。
