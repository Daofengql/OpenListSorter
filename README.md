# OpenListSorter

OpenListSorter 是用于整理 OpenList / Alist 远程文件目录的工具，包含两个独立实现：

| 平台 | 目录 | 技术栈 | 说明 |
| --- | --- | --- | --- |
| Qt5 桌面版 | [`qt/`](qt/) | C++ / CMake | 连续预览、tag 暂存和批量提交 |
| Android 原生版 | [`android/`](android/) | Java / Gradle | 功能语义与桌面版一致，按触屏和系统生命周期适配 |

两个子项目独立构建，构建方式见各自目录下的 README：

- [Qt5 桌面版构建说明](qt/README.md)
- [Android 原生版构建说明](android/README.md)

原 `OpenListSorter-Qt` 与 `OpenListSorter-Android` 仓库已合并到此仓库。
