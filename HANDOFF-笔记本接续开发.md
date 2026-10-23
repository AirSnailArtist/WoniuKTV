# 窝牛家庭KTV：笔记本开发交接

交接日期：2026-10-23。当前 Android 0.8.0，versionCode 11，包名 com.pocketktv.app。当前为可安装的开发构建，不是已全面验收的正式发行版。项目尚未建立 Git 仓库，建议解压后先 git init 并保存基线。

## 先读这个

用户要在客厅电视旁边继续修改和测试。先稳定现有 115 曲库、电视遥控、播放和手机点歌台；百度、夸克、Bilibili 接入已明确暂停。不要擅自启用子智能体。不要重新扫描 10T 全库。不要卸载应用或清除数据。

本包包含 source/ 完整项目、apk/ 最新 APK、dev-signing/ 原开发签名，以及本交接说明。没有用户 115 Cookie、登录密钥、私人曲库数据库、视频文件或构建缓存。开发签名用于保留电视和掌机已有安装的数据升级能力，请私人保存，不要上传公开仓库。

## 设备与当前状态

- 客厅电视：192.168.110.15:5555，之前的 .14 已失效。IP 可能随 DHCP 改变。arm64-v8a，已安装当前修复版。
- Pocket DS：USB 序列号 01108BHB01013973；已装 0.8.0，但本次最后的目录遥控修复仅安装在电视上。
- 主界面 .SongbookActivity（Vue WebView）；设置/115 登录/目录导入 .PocketActivity（原生 Android）。原生管理页仍有旧 0.5 文案，不代表实际包版本。
- 电视已登录 115，最后检查曲库还是 0 首，停在从 115 导入 JSON 窗口。不要假定电视已导入 7 万首。
- 掌机已有约 70,111 首支持歌曲：原小目录 23 首，加大目录 70,088 首；给资源者所述 70,093 包含被过滤文件，不能直接当作软件歌曲数。
- 115 中已有目录备份 /窝牛家庭KTV/Woniu-catalog-20261020-021305.json。新设备登录同账号后，可从曲库管理→目录备份与恢复→从115导入目录清单找到该文件，无需重新刮削全库。

## 新电脑环境与构建

安装 JDK 17、Android Studio（SDK Platform 35、相应 Build Tools、Platform Tools）、Node.js 22 或更新的兼容版本、pnpm。项目 Gradle wrapper 可用；首次运行需要联网下载依赖。minSdk 26，目前仅打包 arm64-v8a。

1. 解压到短的本地路径，例如 D:\WoniuKTV；在 source/ 创建 local.properties，写本机 SDK 路径，例如 sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk。不要沿用旧电脑绝对路径。
2. 把包内 dev-signing/debug.keystore 复制到笔记本用户目录 .android/debug.keystore。若已有其他项目的签名，先备份原文件。当前工程使用默认 debug 签名；保持本包签名才能覆盖现有应用，签名不同时不要卸载解决。
3. 在 source/frontend 执行 pnpm install --frozen-lockfile；pnpm test；pnpm build。前端产物直接输出到 app/src/main/assets/songbook，已随源码保留当前构建产物。
4. 在 source/ 执行 gradlew.bat testDebugUnitTest assembleDebug。PowerShell 用 .\gradlew.bat。JAVA_HOME 指向 JDK17。
5. APK 在 app/build/outputs/apk/debug/app-debug.apk。这是 debug 包，可直接体验；真正公开发行前需要单独处理发布签名、版本号与完整验收。

若 Vite 的配置打包因本机 esbuild 环境异常而失败，可在 frontend 执行 node verify-local.mjs（测试）和 node verify-local.mjs --build（生产构建）。这是与 vite.config.js 等价的配置入口。

若 Kotlin daemon 权限异常，可添加 -Pkotlin.compiler.execution.strategy=in-process。原电脑的 PowerShell/.NET Runtime 10 加载异常是原环境问题；当时用 cmd.exe 绕过，不要为此修改应用代码。

## 客厅安装和排错命令

```bat
adb connect 192.168.110.15:5555
adb devices -l
adb -s 192.168.110.15:5555 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 192.168.110.15:5555 shell am start -W -n com.pocketktv.app/.SongbookActivity
adb -s 192.168.110.15:5555 shell input keyevent 20
adb -s 192.168.110.15:5555 shell input keyevent 23
adb -s 192.168.110.15:5555 logcat -d -t 200 -s AndroidRuntime:E chromium:E
```

方向键 19/20/21/22 对应上下左右；23 确认，4 返回。截屏可用 shell screencap -p /sdcard/woniu.png，再 adb pull。优先读代码、日志、UI hierarchy 和本地渲染，避免反复截图猜布局。不要大范围输出日志中的 Cookie、签名地址或私人文件目录。

安装必须用 -r，禁止 adb uninstall、pm clear。也不要运行 connectedDebugAndroidTest：此前该流程会卸载应用并丢失数据。仪器测试请 assembleDebugAndroidTest 后手动 install -r 测试 APK，再 am instrument；有些真实曲库测试会改变播放/队列，先读测试代码。

## 架构定位

- frontend/src/views/HomeView.vue：首页，榜单左侧放大，功能入口右侧。
- RankingView.vue、components/ChartRecommendations.vue：独立榜单与推荐、原唱候选匹配。
- components/PagedCarousel.vue：Swiper 14.3.0，跟手横滑、无惯性、不自动吸附，虚拟渲染和相邻页预取。相同轮询内容不会重置半页位置。
- frontend/src/remote.js：网页空间焦点导航；app/src/main/assets/player-overlay.html：播放菜单、已点半屏浮层。
- app/src/main/assets/phone-songbook.html：手机点歌台，原生 PhoneSongbookServer.kt 提供受 token 保护的同局域网页面/API。
- SongbookActivity.kt：WebView 本地桥、播放队列、系统音量、原伴唱切换、遥控分发。
- PocketActivity.kt：115 登录、网盘目录选择、扫描、备份/导入。用户目前指出无法遥控的就是该原生目录弹窗，不是 Vue 主页。
- Drive115.kt：扫码会话及直链。SecretStore/SessionCipher：Android Keystore 加密凭据，每台设备自行扫码。
- PlaybackEngine.kt：统一 LibVLC 3.6.5 播放；PlaybackTransport.kt：带请求头和 Range 的本机回环代理；MpegTimeline.kt：MPEG 首尾 PTS 解析。
- LibraryDb.kt：SQLite schema4，song_artists 多对多索引、title_key 索引；CatalogCodec.kt/CatalogRepository.kt：目录导入导出及 115 校验备份。
- ArtistNames.kt/LibraryMetadata.kt：多人歌手拆分、受保护组合名、语种识别。PublicCharts.kt：公开榜单缓存、本地歌曲匹配、歌手头像缓存。
- future/ 为历史备用代码，不是当前入口；以 app/src/main 和 frontend/src 为准。

## 已完成与验证边界

- 115 扫码登录、本地扫描、断点续扫、目录备份及跨设备恢复已实现，早期完整恢复测试通过。
- 多歌手拆分：例如刘若英和合唱对象共用独立歌手分类，保留歌曲完整演唱者信息；保护 Beyond、S.H.E 等组合。华语优先，英文独立分类，不能凭拉丁字母歌名直接判定英文。
- QQ 抖音主题榜、网易热歌、酷狗短视频榜已对接匿名来源，标明来源和更新时间、6小时缓存。它们不是抖音官方授权榜。推荐只播放用户115里匹配的资源。头像按需获取、失败回退字母。
- 系统音量已通过 AudioManager STREAM_MUSIC；电视固定音量时需提示通过电视/功放调整。
- 视频默认拉伸全屏；返回点歌在菜单最左侧；已点右半浮层不遮底部按钮，再按已点收回。
- 暂停/首次进入时新点歌曲优先播放，旧队列保留；正在播放时新歌排队。原唱/伴奏一键切换，不应再弹音轨选择框。
- 0.8 前端 24 项单元测试、Android 单元测试和构建通过。近期横滑、队列、原伴唱尚需全面实体遥控和视频验证，不要把编译成功当作体验验收。
- 本次最新修复：目录 ListView 自动聚焦、加载后选第一项、绿色高亮边框、关闭按钮向上可返回列表。已在电视通过 ADB 下键和确认键进入文件夹。实体遥控器是否完全正常仍等待用户确认。不要直接声称所有窗口已经修好。
- 手机队列渲染签名纳入 queueId；榜单签名纳入状态和匹配结果，避免旧按钮回调或旧推荐残留。

## 优先待修清单

1. 实体遥控器全面测试。当前修复只覆盖原生目录选择器；标准管理菜单、导入确认、网页主页、选歌页、已点浮层、播放菜单均需走一遍。检查焦点可见性、返回、弹窗焦点隔离、跨页后焦点，不要只验证 ADB 注入。
2. 115 MPEG 时长仍未解决：晴天 MPG，VLC raw_length=7110217ms（118:30）异常。首部 Range 返回206，尾部 Range 返回403，correctedDuration=0。现已在探测前不展示错误总时长，但不等于时长已修好。优先查直链签名/Range/重定向/服务端限制；不要用任意5分钟上限伪造时长。MpegTimeline 只读取首尾各512KiB。
3. MPEG 探测完成前的续播已延后执行，避免切伴奏重建播放器丢位置；需要真实文件验证百分比 seek 后 native time 是否对应正确。
4. 原伴唱资源能力判断：独立音轨、配对文件、已有左右声道配置。普通MV没有伴奏时必须诚实提示；不能把任意双声道当作伴奏。检查切回原唱、配对资源续播和缺资源情况。
5. Swiper 的真实手指拖动与松手停留、遥控页按钮。CategoryBrowseView 歌手分类仍有旧离散翻页；ArtistView 存在旧未使用 setSort/load 等残留，后续清理时避免破坏当前功能。
6. 电视主界面适配：Android TV 启动 category 已加入，暂未制作专用 TV banner。电视 WebView 版本及所有CSS布局还需验收。管理页旧0.5文案需统一。
7. 真正公开发行前，增加 versionCode、建立发布签名与完整回归记录。当前 0.8.0 多次原地覆盖开发，不代表每个APK内容相同，本迁移包为最后目录遥控修复后的包。

## 暂停的后续需求

多来源曲库：百度、夸克、Bilibili。候选 https://github.com/OpenListTeam/OpenList 、OpenList-Mobile、InfinityLoop1308/PipePipeExtractor、10miaomiao/bilimiao2。只做过搜索，没有克隆、编译或集成成功验证。OpenList 的 AGPL 和其他 GPL 项目复制/分发时需履行许可证要求；不能假定某条官方接口等同于全站搜索播放，也不能宣称源码已接入。目前用户要求先上线当前版本，暂时不要继续扩展来源。

## 给下一位开发助手的第一条消息

“先读 HANDOFF-笔记本接续开发.md。项目是窝牛家庭KTV，先连接电视192.168.110.15:5555，核对安装和曲库状态，继续修实体遥控器与115 MPEG时长。不要清数据、卸载、重新扫描10T，也不要擅自启用子智能体。多网盘接入暂停。先给我当前问题的证据，再改代码构建，覆盖安装后实测。”
