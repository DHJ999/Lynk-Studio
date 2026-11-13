<h1 align="center">LynkStudio</h1>

<p align="center">
  <b>把自定义仪表界面投到车机第二块屏（1920×720）</b><br/>
  <sub>Android 11 · Kotlin · 优先无 root · 前台服务常驻</sub>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/version-1.5.2-orange.svg" alt="Version">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/use-noncommercial-lightgrey.svg" alt="Noncommercial">
</p>

<p align="center">
  <a href="#功能">功能</a> ·
  <a href="#架构">架构</a> ·
  <a href="#输出路径">输出路径</a> ·
  <a href="#真实车辆数据">数据</a> ·
  <a href="#使用">使用</a> ·
  <a href="#已知限制">已知限制</a> ·
  <a href="#许可">许可</a>
</p>

---

中控屏上的主界面是控制台，真正的仪表画面渲染在副屏。它不是替换原厂仪表程序，
而是在显示层叠一层自己的画面：换主题、切全屏/不遮挡、被原车切走自动切回、开机自启。

> 应用显示名是 **LynkStudio**，包名仍是 `com.duan.lynkcluster`（改包名会让已装设备上的配置丢失）。

## 功能

| 功能 | 说明 |
|---|---|
| 副屏识别 | 合并普通 Display、presentation 分类与 display id 探测，按 1920×720 / 名称 / `FLAG_PRESENTATION` 选仪表屏 |
| 手动选屏 | 控制台列出每块屏的 id / 尺寸，可指定目标屏，避免选错副屏 |
| 悬浮层输出 | `TYPE_APPLICATION_OVERLAY` + 目标 Display 的 `WindowManager`，主界面退出后仍显示 |
| 副屏 Activity | `ActivityOptions.setLaunchDisplayId()` 把 Activity 直接投到仪表屏 |
| 投屏窗口 | `Presentation` 输出；关闭「顶层显示」且主界面存活时可用 |
| 顶层显示 | 强制悬浮层输出，压住普通 Activity；普通应用压不过系统级窗口 |
| 全屏模式 | 不透明 1920×720 底图整屏覆盖 |
| 不遮挡模式 | 只画表盘元素，透明区域露出原厂仪表；可隐藏本应用挡位 |
| 主题切换 | 深空 / 极简 / 霓虹 / 冰蓝 / 琥珀 / 极简科技，运行中即时切换 |
| 被原车切走自动切回 | 每 2 秒检查窗口是否还挂着，被移除后重新挂上 |
| 开机自启 | `BOOT_COMPLETED` 后按配置拉起前台服务 |
| 前台服务 | 主界面退出、进程切后台后继续运行，通知栏可回到控制台 |
| 原车挡位显示 | 不遮挡模式下隐藏本应用挡位，露出原厂挡位 |
| 真实车辆数据 | 优先通过厂商 `CarProxyService` 读车速、转速、水温、挡位；失败后回退标准 `CarService`/VHAL |
| 缺失信号显示 `--` | 车机没给的信号不用 0 冒充，尤其车速/转速/水温/胎压；断开后停止刷新 |
| 数据源状态 | 控制台显示 CAR/SIM、已订阅信号、缺失信号、失败原因；仪表底部标 SIM/IDLE |
| 车辆信号诊断 | 枚举车机全部 `VehicleProperty`（含厂商私有属性），可复制结果用于适配 |
| 一键接管 | 自动申请悬浮窗权限并启用，减少第一次使用时的配置步骤 |
| 屏幕诊断 | 每块屏的 id / 尺寸 / public/private / state，可一键复制诊断信息 |

> 这是**显示层覆盖**，不是替换原厂仪表进程。原厂仪表仍在运行。
> 覆盖能力取决于车机给第三方应用暴露的显示层与窗口层级，静态代码无法保证，必须上车验证。

## 架构

```
MainActivity                 控制台：开关、主题、模式、权限、屏幕列表
      │ 保存 DashboardSettings（SharedPreferences）
      ▼
DashboardService             前台服务：保活 + 通知
      │
      ▼
DashboardEngine              状态机：选屏、挂载、看门狗、时钟、数据分发
      │
      ├── ClusterOverlay     TYPE_APPLICATION_OVERLAY + 目标 Display
      ├── ClusterActivitySurface
      │        └── ClusterDisplayActivity
      │              ActivityOptions.setLaunchDisplayId()
      └── ClusterPresentation Presentation（主界面存活时的旧式回退）
                │
                ▼
           ClusterView       单 View + Canvas 绘制，主题/透明度运行时切换
                ▲
                │
       TelemetrySource       数据源接口（start/stop + 回调）
          ├── HybridTelemetrySource    协程 + StateFlow 逐字段合并（真实数据默认路径）
          │      ├── CarProxyTelemetrySource  厂商 CarProxy（当前车型首选）
          │      └── CarTelemetrySource       标准 CarService / VHAL
          └── MockTelemetrySource      90 秒模拟驾驶剧本（桌面预览 / 回退）
```

### 调度与合并（1.5.0 起）

两路子源的回调收敛成一条流，不再各自维护一套 `Handler` + `Runnable`：

```
vendor 回调 ─┐
car 回调    ─┼─→ MutableSharedFlow<Event> ─→ scan(Merged) ─→ StateFlow
两路状态    ─┘                                     │
                                      ┌────────────┴────────────┐
                                 frames(Telemetry)        states(SourceState)
```

| 机制 | 作用 |
|---|---|
| `CoroutineScope(SupervisorJob() + Dispatchers.Default)` | 一路 Binder 挂掉不连坐另一路，也不会取消整个 scope |
| `scan` 累积 `Merged` | 合并状态集中在一份不可变对象里，不再散落在多个可变字段 |
| `onStart { emit(Merged()) }` | 子源静止（熄火 / 刚开机）时也有首帧；500 ms 轮询退化为子源内部的兜底快照 |
| `SharingStarted.WhileSubscribed(5 s)` | 仪表停止收集 5 秒后上游自动停采，回到时重新拉起 |
| 指数退避重连 | 两条链路统一 1s→2s→4s→8s→16s→30s 封顶；`CarTelemetrySource` 原先断开后不会自愈，1.5.0 补上了 |

### 渲染（1.5.0 起）

`onDraw` 路径零对象分配：数值写进复用的 `CharArray` 再用 `Canvas.drawText(chars, start, count, …)` 绘制，
`MinimalClusterRenderer.Frame` 由 `ClusterView` 复用同一实例，`Paint`/`RectF`/`Path` 全部字段化。
24/7 常驻下不再产生「跑几小时后偶发卡一下」的 GC 抖动。

## 输出路径

「自动」的选择顺序：

1. **顶层显示开启 / 不遮挡模式 / 主界面已退出** → 悬浮层。
2. **顶层显示关闭且主界面存活** → `Presentation`；悬浮层失败时也会回退到这里。
3. 前两条都不行 → 副屏 Activity。
4. 三条都失败 → 控制台显示每一步的具体错误。

也可以在「输出方式」里手动锁定某一条，方便在车机上判断是哪一层被系统拦了。

## 真实车辆数据

数据源优先走厂商车辆服务，失败后再走 Android 标准车身信号入口；两条都不碰 CAN 线：

```
厂商车辆服务 / QNX
      ↓
CarProxyService（Binder，只读订阅）
      ↓
ICarProxyCallback.onChangeEvent(proId, CarPropertyValue)
      ↓
CarProxyTelemetrySource  →  HybridTelemetrySource  →  ClusterView
```

```
CAN / 车身网络 → 车机 VHAL → CarService → CarPropertyManager
      ↓
CarTelemetrySource  →  HybridTelemetrySource  →  ClusterView
```

厂商 CarProxy 读取的属性：

| 仪表字段 | 厂商属性 ID | 单位/说明 |
|---|---|---|
| 车速 | `0x11600207` / `0x11600208` | 标准 m/s 与显示车速；统一换算 km/h |
| 转速 | `0x11600305` | r/min |
| 水温 | `0x11600301` | °C |
| 挡位 | `0x11400400` / `0x11400401` | `VehicleGear` 枚举 |
| 车外温度 | `0x11600703` | °C |

CarProxy 实现直接使用原始 Binder transaction，避免在 APK 中打包只在车机 boot classpath 存在的厂商 AIDL 类。
它只调用 `registerCarProxyService()`、`addEventProId()`、`getIntProperty()` 和回调，**不调用 `setIntProperty()`**。
客户端会监听 Binder death；车辆服务崩溃或重启时立即清空最后一帧并触发回退，不会让仪表冻结在旧车速上。

标准 VHAL 读取的属性：`PERF_VEHICLE_SPEED(_DISPLAY)`、`ENGINE_RPM`、`ENGINE_COOLANT_TEMP`、
`CURRENT_GEAR` / `GEAR_SELECTION`、`TIRE_PRESSURE`、`FUEL_LEVEL`、`RANGE_REMAINING`、
`PERF_ODOMETER`、`ENV_OUTSIDE_TEMPERATURE`。

没读到的字段显示 `--`（`Float.NaN` / `rpm < 0` / 挡位 `--` 约定），不用 0 冒充真实值。

### 实车排查命令

```bash
# 1. 有没有标准 CarService / VHAL
adb shell service list | grep -i -E "car|vehicle"
adb shell dumpsys car_service --services

# 2. 有没有厂商 CarProxy / DIM 服务
adb shell service list | grep -i -E "CarProxy|DimNavi|DimWhole|ecarx"
adb shell ls -l /system/lib64 | grep -i -E "fdbus|vehicle|oneos|qnx"
```

- `service list` 能看到 `CarProxyService` → 优先使用厂商数据源，不需要系统权限。
- 两者都没有 → 使用模拟数据，或考虑 OBD/GPS 兜底。

## 权限

| 权限 | 用途 | 不给会怎样 |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | 悬浮层输出；不遮挡模式必需 | 回退到 `Presentation`，退出主界面后断开 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | 只能手动打开 |
| `FOREGROUND_SERVICE` | 仪表盘后台常驻 | 服务无法常驻 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14 前台服务类型 | Android 14 上启动失败 |
| `android.car.permission.CAR_SPEED` | 标准 VHAL 车速 | 标准 VHAL 车速不可用；会先尝试厂商 CarProxy |
| `android.car.permission.CAR_POWERTRAIN` | 转速、挡位 | 转速/挡位读不到 |
| `android.car.permission.CAR_ENGINE_DETAILED` | 水温 | 水温读不到 |
| `android.car.permission.CAR_TIRES` | 四轮胎压 | 胎压显示 `--` |
| `CAR_ENERGY` / `CAR_MILEAGE` / `CAR_EXTERIOR_ENVIRONMENT` / `CAR_INFO` | 油量、续航、里程、车外温度 | 对应字段不可用 |

`android.car.permission.*` 是标准 VHAL 路线的系统权限，普通安装拿不到。需要把 APK 放进
`/system/priv-app/`（或平台签名），并在车机的 `privapp-permissions-*.xml` 白名单里放行，
否则 CarService 会拒绝订阅。仓库里提供了
[`system/privapp-permissions-com.duan.lynkcluster.xml`](system/privapp-permissions-com.duan.lynkcluster.xml)。

无网络权限，不收集数据。

## 使用

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

1. 打开 **LynkStudio**。
2. 点「一键接管仪表屏」；没有悬浮窗权限时会先跳到系统设置，授权后回来继续。
3. 如果自动选屏不对，在「检测到的屏幕」里点目标屏的 id。
4. 选主题和显示模式；「应用并刷新」可强制重新挂载。
5. 某条输出路径失败时，在「输出方式」里逐条试：自动 / 悬浮层 / 投屏窗口 / 副屏 Activity。
6. 需要开机自动显示时，再打开「开机自启」。
7. 在「车辆数据」里选数据源：真实数据（默认，厂商 CarProxy 优先）/ 模拟数据。
8. 读不到真实信号时，点「车辆信号诊断」：先看 8 个 `android.car.permission.*`
   是 ✓ 还是 ✗、APK 是否在 `/system/`，再看车机属性表里目标信号是否存在。

## 已知限制

- **不是替换原厂仪表。** 不接管、不停用原厂仪表进程；只是把一层画面叠上去。
- **覆盖范围由车机决定。** 部分车机的物理仪表屏由底层合成器管理，只对 Android 露出一块矩形区域，
  应用画面超出该区域的部分不会显示。这不是应用能绕过的，需实测确认。
- **「顶层显示」有限。** 悬浮层能压住普通 Activity，但压不过系统级/厂商级窗口。
- **「自动切回」是轮询。** 只能发现窗口被移除或 Display 变化，无法感知「被另一个窗口盖住」。
- **private Display 可能失败。** 部分车机即使给了悬浮窗权限，也不允许第三方应用向仪表屏添加窗口。
- **副屏 Activity 也可能被拒绝。** `setLaunchDisplayId()` 是公开 API，但车机可以限制后台启动 Activity。
- **真实数据取决于车机是否放行厂商 CarProxy 或 VHAL。** 按 `CarProxy → VHAL → 模拟` 回退，
  并在控制台和仪表上明确标 `SIM`。
- **CarProxy 属性 ID 可能随车型/OTA 变化。** 代码内置的是当前车型解出的候选 ID。
- **全屏覆盖有安全风险。** 会挡住原厂车速、挡位和警告灯；行驶中请先用「不遮挡仪表盘」，
  或只在封闭场地测试。应用内有一次性的风险确认。
- **不是 Launcher。** 不接管桌面，不改系统设置。卸载即恢复。

## 结构

```
app/src/main/java/com/duan/lynkcluster/
  MainActivity.kt            控制台：配置、权限、屏幕诊断
  DashboardEngine.kt         选屏、挂载、看门狗、状态分发
  DashboardService.kt        前台服务（保活 + 通知）
  BootReceiver.kt            开机自启
  DashboardSettings.kt       配置持久化（SharedPreferences）
  ClusterSurface.kt          悬浮层 / 副屏 Activity / Presentation
  ClusterDisplayActivity.kt  副屏 Activity + ClusterActivityHost 桥
  ClusterTheme.kt            主题配色
  ClusterView.kt             仪表绘制（单 View + Canvas）
  MinimalClusterRenderer.kt  「极简科技」形态级渲染器
  NumberBuffer.kt            绘制用零分配数字缓冲
  Palette.kt                 控制台配色
  Telemetry.kt               单帧车辆状态
  TelemetrySource.kt         数据源接口 + 工厂
  HybridTelemetrySource.kt   两路逐字段合并（协程 + StateFlow）
  CarProxyTelemetrySource.kt 厂商 CarProxy 数据源（原始 Binder，只读）
  CarTelemetrySource.kt      CarService / VHAL 数据源
  CarSignalProbe.kt          车辆属性表诊断
  MockTelemetrySource.kt     模拟数据源
```

## 构建

```bash
export JAVA_HOME="D:/Android Studio/jbr"     # JDK 17+
./gradlew assembleRelease
./gradlew testDebugUnitTest
```

- APK：`app/build/outputs/apk/release/app-release.apk`（版本 1.5.2，versionCode 14）
- 测试渲染图：`app/build/render/cluster_*.png`

运行时依赖只有 `kotlinx-coroutines-core`（StateFlow 合并链路），其余为零第三方；
Robolectric 仅测试期使用。

## 许可

本项目基于 **PolyForm Noncommercial License 1.0.0** 开源：

- ✅ 个人学习、研究、修改、分发
- ❌ **任何商业用途**（含内部商业使用、收费分发、商业产品集成）

完整条款见 [LICENSE](LICENSE)。如需商用授权，请联系作者。

字体 [Rajdhani](https://fonts.google.com/specimen/Rajdhani) 来自 Google Fonts，
遵循 SIL Open Font License 1.1。
