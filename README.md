# 亮屏时间 · Screen On Time

一个极简的 Android 亮屏时间统计应用：显示每日屏幕点亮时长、解锁时长、解锁次数，
并提供日历热图、柱状趋势和单应用用时排行。

本项目是为了替代「应用时间限额」（`com.w.applimit`）免费版**只能查看 7 天数据**
的限制而重写的。原应用需要付费解锁更长历史，而它的统计口径由服务端与本地库共同决定；
这里用系统 API 重新实现，**数据完全本地、无广告、无付费墙、历史只增不减**。

---

## 功能

| 功能 | 说明 |
|---|---|
| **今日亮屏时长** | 屏幕点亮总时长，**包含锁屏期间亮屏** |
| **解锁后时长 / 解锁次数** | 副指标，`KEYGUARD_HIDDEN` / `KEYGUARD_SHOWN` 配对统计 |
| **日历热图** | 按月展示，颜色深浅反映当天用时 |
| **14 天柱状图** | 点击柱条切换日期 |
| **单应用用时排行** | 带图标、占比、首次/末次使用时间 |
| **左右箭头切换日期** | 顶部快速浏览相邻日期，跨月自动翻页 |
| **每日后台归档** | 每天 00:30 自动把数据写入自己的库 |

## 统计口径（重要）

- **"亮屏时长"= 屏幕点亮时长，锁屏亮屏计入。**
  数据源是 `UsageEvents` 的 `SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE` 配对。

- **不使用** `UsageStatsManager` 的 `INTERVAL_DAILY` 聚合桶。
  原因：**系统的日聚合桶不按自然日切分**，在测试机上每天约 **23:0x** 才翻页，
  因此"今天的桶"在当天大部分时间里并不存在，会导致今日显示 0，且与按自然日
  统计的应用对不上。

- **历史深度受系统限制**：Android 只保留约 **2 天**的逐条事件。因此：
  - 应用安装后**只能回溯约 2 天**
  - 更早的每日明细**无法还原**（数据已被系统清理，非算法问题）
  - 从今往后每次归档都会写入自有数据库，**永不截断**

## 构建

无需 Gradle / Android Studio，只用 Python 驱动 Android SDK 命令行工具。

```bash
# 1) 首次运行会自动下载 JDK 21、build-tools、platform-tools、android.jar
#    约 600 MB，存放在 tools/toolchain/
python tools/setup_toolchain.py

# 2) 构建（versionCode 自动递增）
python tools/build_app.py
```

产物：`app/build/screentime.apk`

### 依赖

- Python 3.8+
- Windows（脚本使用 `.bat` / `.exe` 工具；Linux/macOS 需自行替换工具名）
- 构建时自动获取：Temurin JDK 21、Android build-tools、platform-tools、platform

### 生产脚本（可选）

```bash
python tools/gen_manifest.py        # 重新生成 AndroidManifest.xml（UTF-8）
python tools/gen_manifest.py --diag-launcher   # 诊断页作为启动页
python tools/gen_icon.py            # 由 assets/icon/icon-source.png 生成各密度图标
python tools/gen_adaptive_icon.py   # 生成自适应图标（API 26+）
```

> `gen_manifest.py` 的存在原因：PowerShell 的 `Set-Content` 会按系统 ANSI 代码页
> 写入，曾把清单里的中文标签写坏。用 Python 生成可以确保 UTF-8。

## 安装

```bash
adb install -r app/build/screentime.apk
```

首次启动需要授予**使用情况访问**权限：

```
设置 → 隐私保护 → 特殊权限 → 使用情况访问 → 亮屏时间 → 允许
```

也可以用 adb 直接授权：

```bash
adb shell appops set cn.local.screentime GET_USAGE_STATS allow
```

## 项目结构

```
app/
  AndroidManifest.xml
  src/cn/local/screentime/
    MainActivity.java        主界面：顶部时长、日历、柱状图、应用排行
    UsageRepo.java           数据层：事件解析、按自然日统计、入库
    Db.java                  SQLite 表结构（增量迁移，永不删表）
    MonthCalendarView.java   日历热图（自定义 View）
    BarChartView.java        柱状图（自定义 View）
    ArchiveReceiver.java     每日后台归档（AlarmManager）
    BootReceiver.java        开机后补归档并重装闹钟
    Labels.java              包名 → 名称/图标，含包可见性处理
    Insets.java              挖孔/状态栏安全区适配
    Fmt.java / Utils.java    格式化与小工具
    DiagActivity.java        内部诊断页（无桌面图标）
  res/                       图标资源（含自适应图标）
tools/                       构建与资源生成脚本
assets/                      图标源文件、已构建的 APK
```

## 实现中踩过的坑

这些是实际调试出来的结论，记录在此以免重蹈覆辙。

1. **包可见性（Android 11+）**
   只声明 `QUERY_ALL_PACKAGES` 权限**不够**，必须同时提供 `<queries>` 元素。
   否则 `PackageManager` 只能看到极少数系统应用（实测：290 → 修复后 489 个包），
   使用记录里的第三方应用会全部解析失败、没有名字和图标。

2. **系统日聚合桶不对齐自然日**
   见上文"统计口径"。表现为"今日恒为 0 分钟"。

3. **aapt2 无法读取非 ASCII 路径**
   资源目录路径含中文时报 `failed to open directory`。
   解决：把编译过程放到纯 ASCII 的临时目录（`tools/build_app.py` 中的 staging）。

4. **子进程输出编码**
   `subprocess(text=True)` 会用系统代码页（GBK）解码，遇到工具输出的 UTF-8 字符
   （例如 AndroidX 资源里的希伯来文）直接抛异常。解决：捕获 bytes 后自行按 UTF-8
   解码并容错。

5. **Adaptive Icon 的安全区**
   若图标本身是圆形，直接作为前景会被启动器蒙版裁掉边缘；
   需按 66/108 安全区缩放，背景色可从图标外圈采样得到。

## 已知限制

- 逐条事件仅保留约 2 天，**更早日期无法还原每日明细**
- 小部件尚未实现（HyperOS 小部件基于 Android `AppWidget`，可后续补充）
- 后台归档依赖 `AlarmManager`，**Doze 会推迟执行**；小米等 ROM 建议将应用设为
  "无限制"省电策略
- 仅适配简体中文

## 许可

MIT，见 [LICENSE](LICENSE)。
