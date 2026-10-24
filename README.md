# Web For Tomato 3.2.2 —— 界面原生的番茄小说下载器

把 [zhongbai2333/Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader) 的下载内核装进安卓应用，
**界面全部用 Compose 原生写**：搜索、任务、书库、配置都在 App 里点，不加载网页，也不用手动下载内核二进制。

- 下载 / 解析能力：上游 Rust 内核（v2.4.16，未改一行）
- 界面：Android + Kotlin + Jetpack Compose（原生组件，非 WebView）
- 界面语言：中文

> **生成方式声明**：本版（3.2.2）的安卓工程改造、原生界面、打包与文档，由 **AI（DeepSeek Harness 智能体）**
> 在用户要求下生成与修改。下载与解析内核完全来自下面的上游开源项目，未做任何改动。

## 零、本版源码仓库

<https://github.com/BlueBigFish001/webForTomatoFix>

完整工程（安卓工程 + 内置内核 + 构建脚本）都在这个仓库；`build.ps1` 可在本机构建出 APK。

## 零点五、开源来源与许可（MIT）

| 项目 | 作用 | 许可 |
|---|---|---|
| [zhongbai2333/Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader) | 下载 / 解析内核（Rust），本应用全部下载能力来源 | MIT |
| [yujunc39-arch/Tomato-Novel-Downloader](https://github.com/yujunc39-arch/Tomato-Novel-Downloader) | 安卓客户端原型（原为 WebView 外壳），本版在其 `Android/` 工程上改造 | MIT |

上游致谢（引自原项目 README）：作者 **zhongbai2333** 及所有贡献者；前期基础项目 **Dimily**；
API 支持 **@helloplhm-qwq**、**@jingluopro**、QQ 用户 **@终忆**。

MIT 许可要求保留原版权声明与许可文本；本应用分发时需一并保留上述来源与 `LICENSE`。
软件按「原样」提供，不附带任何明示或默示担保。

---

## 一、与原版客户端（2.1.8）的差别

| 环节 | 原版 2.1.8 | 本版 3.2.2 |
|---|---|---|
| 下载内核 | 点「获取 Web」→ 联网从 GitHub Release 下 8.6MB 二进制 | **APK 内置** arm64 + arm32，首次启动释放到可执行目录，**离线可用**；可在「更多 → 内核日志」里手动联网更新 |
| 操作界面 | 服务起来后用**系统浏览器**打开网页 | **原生 Compose 界面**：搜书 / 任务 / 书库 / 更多四个页签，直接调内核的本地 HTTP 接口 |
| 导出 | 由浏览器决定落点 | **应用内复制**：书库页一键把 .epub/.txt 写入 `Download/Tomato/` |
| 书库 | 只能一本本「复制到…」 | 书库页可浏览目录、导出、删除；「关于」页还能一键全部导出 |
| 手机布局 | — | 3.1.0 起对网页做了手机适配；3.2.2 起**原生界面本身就是按手机设计的** |
| 输入法 | — | 修复了键盘弹出时界面被「黑色遮罩」压塌的问题 |

---

## 二、安装

- 支持 Android 6.0（API 23）～ Android 15
- 安装包：运行 `build.ps1` 本地构建（产物在 `dist/`），或从本仓库 **Releases** 下载；仓库不提交 APK
  （`.gitignore` 已排除 `dist/`，二进制产物走 Release 更规范）
- 安装时系统可能提示「此应用专为旧版 Android 构建」，选择**继续安装**

> `targetSdk = 28` 是刻意保留的：Android 10 起 `filesDir` 被施加 W^X（noexec）保护，
> 只有 `targetSdk <= 28` 的应用才能执行自己目录下的原生二进制。

---

## 三、界面是怎么「原生」的

内核仍然要跑：它是唯一懂番茄接口、加密、EPUB 生成的引擎（Rust，16k 行）。
但它本来就是一台本地 HTTP 服务（`127.0.0.1:18423`），自带完整 JSON API（`/api/search`、`/api/jobs`、
`/api/library`、`/api/config`…）。所以这一版的做法是：

```
Compose 界面  ──HTTP/JSON──▶  127.0.0.1:18423（内置内核，前台服务常驻）
   搜书/任务/书库/配置           搜索、下载、EPUB 生成、历史、追更
```

- 打开 App 只会看到原生页面，**不会加载任何网页**
- 内核由前台服务拉起，锁屏 / 切后台继续下载（和其它下载器一样）
- 「更多 → 原版网页界面」保留了一个 WebView 入口作为备用（万一某个上游新功能原生页还没覆盖）

---

## 四、四个页签做什么

| 页签 | 功能 |
|---|---|
| **搜书** | 输入书名 / Book ID / 链接 → 内核搜索 → 点书本图标创建下载任务；已有任务时提示「等它结束」 |
| **任务** | 每 1.5s 轮询进度（章节 x/y、百分比、分组），可取消 / 删除；内核要挑「格式」或「书名」时用原生对话框问你 |
| **书库** | 浏览保存目录（含子文件夹）、显示大小与时间、**一键导出到 Download/Tomato**、删除 |
| **更多** | 服务状态（版本/端口/保存目录/局域网地址）、下载配置（txt/epub/pdf/下载后选、有声书开关与格式）、下载历史、追更扫描、内核日志、原版网页界面、关于 |

导出后的书在手机文件管理器里能直接找到：`内部存储/Download/Tomato/书名.epub`。

---

## 五、这次修的两个问题

### 1）键盘弹出时「黑色遮罩」

原因：布局里把键盘高度算了两遍 —— `Scaffold` 已经用 `imePadding()` 把内容顶上去，
底部又额外留了一次悬浮导航栏的高度（`92dp`）。两者叠加后内容区被压成一条缝，
露出大面积深色背景（看着就像一块黑色遮罩）。

修法：键盘弹出时底部不再额外留白（`bottomInset = 0`），收起时恢复 92dp 给悬浮导航栏。

### 2）界面依赖网页

3.1.0 还是 WebView + 注入 CSS 的手机版网页。3.2.2 把这些页面全部换成 Compose 原生实现：

- 列表用 `LazyColumn`，进度用 `LinearProgressIndicator`，弹窗用 `AlertDialog`
- 数据来自内核自带的 JSON API，字段与 `src/ui/web/routes/*.rs` 一一对应
- 原来的「终端 / Web服务 / 关于」整页收进「更多」的二级页面，功能没丢

---

## 六、目录结构

```
tomato-app/
├── README.md
├── build.ps1                         # 一键构建（Windows）
├── dist/                             # 构建产物（.gitignore 排除，本地生成）
│   └── WebForTomato-3.2.2.apk
├── screenshots/
├── release.jks                       # 自签名密钥（口令 Tomato2024!，别名 tomato）
└── Tomato-Novel-Downloader-main/
    ├── src/ Cargo.* …                # 上游 Rust 源码（未改动）
    └── Android/TomatoWeb/
        └── app/src/main/
            ├── assets/tomato/        # 内置内核 arm64/arm32 + VERSION
            │   ├── mobile.css        # 3.1.0 的网页手机适配（原版网页入口里仍会用到）
            │   └── mobile.js
            ├── res/xml/network_security_config.xml
            └── java/com/tomato/downloader/
                ├── core/BundledCore.kt        # 内置内核释放
                ├── core/LocalApi.kt           # ★ 内核 JSON API 客户端 + 数据模型
                ├── core/DownloadStore.kt      # MediaStore / 公共目录落盘
                ├── core/WebDownloadManager.kt # 原版网页入口的下载接管
                ├── core/MobileUiInjector.kt   # 原版网页入口的手机适配注入
                ├── core/ServerManager.kt      # 内置优先 + 联网回退
                ├── core/BookStore.kt          # 「关于」页书库扫描 / 一键导出
                ├── ui/NativeComponents.kt     # ★ 原生控件（卡片/徽章/空态/错误条）
                ├── ui/SearchScreen.kt         # ★ 搜书
                ├── ui/JobsScreen.kt           # ★ 任务（含格式/书名选择对话框）
                ├── ui/LibraryScreen.kt        # ★ 书库（浏览/导出/删除）
                ├── ui/MoreScreen.kt           # ★ 更多（状态/配置/入口）
                ├── ui/HistoryScreen.kt        # ★ 下载历史
                ├── ui/UpdatesScreen.kt        # ★ 追更
                ├── ui/InAppWebView.kt         # 原版网页入口（备用）
                ├── ui/TerminalScreen.kt       # 内核日志
                ├── ui/AboutScreen.kt          # 关于 + 书库导出
                └── MainActivity.kt            # 原生四页签 + 二级页面 + IME 修复
```

---

## 七、构建（复现）

环境：JDK 17、Android SDK（platform 35 + build-tools 35.0.0）、Gradle 8.11.1。

```powershell
# 一次性环境（本机示例路径）
$env:JAVA_HOME='D:\Program Files\Java\jdk-17'
$env:ANDROID_HOME='F:\ai\android-sdk'
$env:GRADLE_USER_HOME='F:\ai\gradle-home'
$env:TOMATO_KS_FILE='F:\ai\tomato-app\release.jks'
$env:TOMATO_KS_PASS='Tomato2024!'
$env:TOMATO_KS_ALIAS='tomato'

& 'F:\ai\gradle-8.11.1\bin\gradle.bat' `
  -p 'F:\ai\tomato-app\Tomato-Novel-Downloader-main\Android\TomatoWeb' `
  --no-daemon assembleRelease
```

或直接运行 `build.ps1`。

---

## 八、已知限制

- 内核版本随 APK 发布（当前 v2.4.16），可在「更多 → 内核日志 → 获取 Web」联网更新；失败自动回退内置版本。
- 内置内核只有 arm64 / arm32；x86 模拟器没有内置（会尝试联网下载，上游也无 x86 包）。
- 原生页覆盖的是高频功能（搜书/任务/书库/配置/历史/追更）。上游那些低频项（章节预览、完整 YAML 配置编辑、
  程序自更新）仍走「更多 → 原版网页界面」。
- 自签名 APK：升级安装需保持同一签名（本次密钥在 `release.jks`）。

---

## 九、免责声明（沿用上游）

- 本程序仅供 Rust 网络爬虫技术、网页数据处理及相关研究的学习用途。
- 请勿用于任何违反法律法规或侵犯他人权益的活动；下载到的小说仅供自行阅读。
- 请勿将第三方 API 转载使用，也请勿把本程序用于批量滥用。
- 使用本程序的用户需自行承担由此引发的任何法律责任和风险。

上游：<https://github.com/zhongbai2333/Tomato-Novel-Downloader>（MIT）
