# 窝牛家庭 KTV

![窝牛 KTV](assets/branding/woniu-logo-v1.png)

Android 大屏家庭点歌应用。使用自己的 115 曲库，在电视上搜索、点歌、播放，也支持 B 站视频搜索与收藏夹，同一局域网内可用手机扫码点歌。当前版本 **0.9.8**。

**有问题请联系微信：18538573855。**

## 当前功能

- 115 扫码登录、曲库目录导入与备份、扫描断点续做。
- 歌名、歌星、拼音首字母、语种与分类浏览，收藏和已点队列。
- 在线播放、原唱/伴奏、音轨与声道选择，默认铺满画面。
- 9 张公开音乐榜单，覆盖流行指数、热歌、飙升、新歌、原创、K歌金曲；每小时检查更新，缺歌可到 B 站选 MV。
- B 站歌曲点播后后台自动收藏到账号的「窝牛KTV」收藏夹，新建时为私密；重复点播不会重复添加视频。
- 首页左侧热榜、右侧可能想唱；本机偏好随点歌与实际演唱学习。
- 曲库与智能偏好保存到同一份115备份，同账号在其他设备恢复。
- 手机扫码点歌、智能推荐、B站搜索点播、纵向列表和播放控制。
- 遥控器方向键导航，统一深色暖金与日间暖白界面。
- 按设备可用窗口自动适配、手动画面大小调整、整卡点歌与短距离翻页。
- 同文件双音轨低延迟切换，支持 MPEG 进度条比例定位。
- B站扫码登录、视频搜索、收藏夹与分享链接浏览；本地缺歌时选择B站MV版本并保存关联。
- 最小化后继续播放，系统播放通知提供暂停、继续、下一首和停止。

## 安装与升级

下载本仓库 Releases 中的安装包或发布包，适用于 **Android 8.0及以上、ARM64设备**。应用ID为 `com.pocketktv.app`。

已有同签名版本时直接覆盖安装，保留登录、曲库和队列。不要先卸载。首次安装后需使用自己的115账号与曲库，本项目不提供歌曲文件。

新设备恢复：登录同一个115账号，在曲库管理中导入 `/窝牛家庭KTV/` 下最新的 `Woniu-catalog-*.json`。换设备或卸载前，先备份并等待成功，智能偏好与曲库一起保存。账号登录凭据不随目录备份迁移。

## 开发与构建

需要 Node.js、pnpm、JDK 17或更新版本、Android SDK 35和项目Gradle Wrapper。

```sh
cd frontend
pnpm install --frozen-lockfile
node verify-local.mjs --build
cd ..
./gradlew assembleDebug -PwoniuDebugKeystore=/path/to/your/keystore
```

SDK目录使用本机 `local.properties` 配置 `sdk.dir`，或设置 `ANDROID_HOME`。签名密钥由项目维护者在受控位置保存，不在GitHub或发布包内。要覆盖已有应用，必须使用原签名；默认生成的其他调试签名不能覆盖当前电视版本。

APK输出：`app/build/outputs/apk/debug/app-debug.apk`。启动Activity为 `com.pocketktv.app.SongbookActivity`，管理页为 `PocketActivity`。

前端相关检查：`cd frontend && node verify-local.mjs`。Android数据库检查使用独立测试库，开发时不要用会卸载真实应用的 `connectedDebugAndroidTest`。

## 数据与安全

- 115凭据保存在Android Keystore保护的本机存储中，网页不获得Cookie或临时播放地址。
- 手机点歌需要扫码配对，手机与电视应在同一局域网。应用重启后重新扫码。
- 切换115账号会隔离原账号曲库；目录仅支持对应账号恢复。
- 仓库及发布包不包含账号登录凭据、真实曲库备份、数据库、签名密钥或私人播放资源。

## 版本与实现

- [0.9.8 首页三行歌曲与设置返回](RELEASE-0.9.8.md)
- [0.9.7 多榜单、遥控器选择、B站自动收藏与手机点歌](RELEASE-0.9.7.md)
- [0.9.6 B站接入、后台播放与收藏夹布局](RELEASE-0.9.6.md)
- [0.9.5适配、点歌与播放修复](RELEASE-0.9.5.md)
- [0.9.4发布说明](RELEASE-0.9.4.md)
- [界面、焦点与歌星分页](RELEASE-0.9.3.md)
- [智能推荐与备份模型](SMART-LIBRARY.md)
- [图标设计与生成提示词](assets/branding/DESIGN.md)
- [上游来源](UPSTREAM.md)与[第三方许可说明](NOTICE)

整合项目按 `GPL-3.0-or-later` 分发；保留Home KTV、115driver等上游许可，详见 `LICENSE`、`UPSTREAM.md` 和 `app/src/main/assets/licenses/`。发布包同时提供对应版本源码。
