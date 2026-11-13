# 已知问题与排查记录

## 1. 模拟器「上滑 → Screenshot」截屏无法解析暗水印

### 现象

- 1:1 截屏（分辨率与逻辑分辨率一致）时，暗水印可以正常解析。
- 在模拟器里上滑打开通知栏、点 **Screenshot** 快捷按钮截屏时，解析失败，相关强度 `score ≈ 0.05`。
- 长按开关进入可见调试模式（α=40）后，屏幕上能看到细密噪点，但保存下来的 PNG 里**看不到**噪点。

### 证据

logcat（tag `SystemWP`）：

```
screen=1080x2400 dpi=420 density=2.625 physical=864x1920
resample 864x1920 -> 1080x2400
detect 864x1920 mime=image/png hit=false score=0.004713803845097235
```

截图尺寸 864×1920，而 App 拿到的逻辑分辨率是 1080×2400 —— 正好 **0.8×**。

### 原因

模拟器存在 `wm size` 覆盖：物理帧缓冲是 864×1920，逻辑分辨率被改写为 1080×2400，
SurfaceFlinger 合成时对整屏做 1.25× 放大，而截屏捕获的是**物理帧缓冲**。

- 旧参数（数据单元 4 px、单元内逐像素棋盘 = 空间周期 2 px）正好处于 Nyquist 频率，
  任何重采样都会把相邻的亮/暗像素平均成灰，信号在图像层面直接湮灭。
- `MainActivity.fitToScreen()` 把截图缩回逻辑分辨率只能修正网格对齐，
  无法恢复已被平均掉的高频信息，因此 `resample` 之后仍然是 `score ≈ 0.05`。

### 复现

1. `adb shell wm size` 可见 `Physical size: 864x1920` 与 `Override size: 1080x2400`。
2. 开启水印 → SystemUI 截屏 → 选该图解析 → `score ≈ 0.05`，未命中。
3. 用 `adb exec-out screencap -p > shot.png` 取同样画面 → 解析正常。

### 处理

- **临时规避**：`adb shell wm size reset`、`adb shell wm density reset`，让截图为 1:1。
- **代码侧抗缩放参数**（`ScreenWatermarkCodec`）：
  - `CHIP_SIZE` 4 → 12：0.8× 后仍剩 9.6 px 的数据单元；
  - 单元内棋盘改为 **2×2 子块**，空间周期 2 px → **4 px**，避开 Nyquist；
  - `TILE_CHIPS` 64 → 56（tile 672 px）：tile 必须小于屏幕，否则边缘槽位永远采不到样本；
  - 正文重复次数按 tile 剩余容量自适应，`MIN_BODY_REPEAT = 2`。
- 仿真结果（1080×2400 → 0.8× → 缩回 → 解码）：

  | 背景 | 1:1 | 0.8× |
  |---|---|---|
  | 纯白 | score 3.00，成功 | score 1.23，成功 |
  | 密集文字 | score 3.01，成功 | score 1.25，成功 |
  | 平滑渐变 | score 2.86，正文误码 | score 1.06，正文误码 |

- **仍未解决**：大面积纯色/渐变背景下，0.8× 缩放会因信噪比不足而失败。
  需要时可把 `ScreenWatermarkCodec.OVERLAY_ALPHA` 从 6 提到 9（代价是隐形度下降）。

### 调试手段

- **长按「水印开关」**：暗水印临时放大到 α=40，肉眼可见；再长按恢复隐形。
- **logcat** 过滤 `tag:SystemWP`：`addOverlay type=.. added=..`、`tile ready: plain=..B sealed=..B`、`detect WxH mime=.. hit=.. score=..`。
- `score` 量纲约为调制幅度的一半：正常 ≈ 3、0.8× 缩放后 ≈ 1.2、无信号 ≈ 0.05。

---

## 2. 其他已知限制（非缺陷）

| 现象 | 原因 |
|---|---|
| 有 `FLAG_SECURE` 的页面截图全黑 | 系统禁止截屏，水印无法随图带出 |
| 截图经 JPEG/WebP/HEIC 再压缩后无法解析 | 有损压缩抹掉 1~3 个灰阶级别的调制（解析时会提示格式） |
| 部分应用内截图无信号 | 应用可调用 `setHideOverlayWindows(true)`，隐藏第三方 `TYPE_APPLICATION_OVERLAY` 悬浮窗；只有 platform 签名的 `TYPE_INTERNAL_SYSTEM_WINDOW`（2021）不受影响 |
| 截图被任意比例缩放 | 像素级调制会被重采样削弱；本仓库已针对 0.8× 做加固，其他比例未保证 |
| 卸载或清除数据后旧截图无法解析 | 载荷用本机 Keystore 密钥加密，密钥不可导出 |
