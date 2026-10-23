# WoniuKTV 0.8.3

2026-10-23，versionCode 14。

修复启动时 PlayerView 保存的 video_resize 未同步给 VLC 引擎的问题。在 engine.attach 后立即调用 setResizeMode(videoView.resizeMode)，使默认铺满和用户保存的显示模式在首次播放时生效，无需手动再选一次。

13 项 JVM 单元测试与 assembleDebug 通过。使用项目原签名覆盖安装电视，保留曲库与登录。此次未更改视频比例选项的业务含义。
