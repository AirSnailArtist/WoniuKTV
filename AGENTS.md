# WoniuKTV 项目协作

- 本项目由当前对话直接完成，不调用子智能体。
- 用户已授权本项目完成改动后快速上线：完成相关检查后直接构建并覆盖更新电视，无需逐次询问。以当前用户指令为准。
- 电视当前 ADB 地址为 192.168.110.15:5555；连接后核对设备状态。
- 覆盖安装使用 adb install -r，保留登录、曲库和队列；不使用卸载、清数据或重新扫描全库解决问题。
- 不运行 connectedDebugAndroidTest；设备测试使用独立测试库，手动覆盖安装测试 APK 后只执行明确指定的测试类。
- 项目开发签名保存在 /Users/woniu/.config/woniu-ktv/debug.keystore。构建传入 -PwoniuDebugKeystore=该路径；覆盖前校验签名。不得覆盖用户全局 debug.keystore。
- 本机 JDK 位于 /Users/woniu/Developer/hongguo-tools/jdk/Contents/Home，Android SDK 位于 /Users/woniu/Developer/hongguo-tools/android-sdk。
- 用户已授权接入 B 站视频搜索、账号收藏夹与播放，并在本地缺歌时选择 B 站 MV 版本；保留 115 曲库、遥控和播放。其他来源仍按用户明确需求推进。

- 智能推荐的数据库、学习与跨设备恢复规则以 SMART-LIBRARY.md 为准；0.9.6 接入与预算见 RELEASE-0.9.6.md。推荐与手机分页保留业务键批量读取和工作量预算。
