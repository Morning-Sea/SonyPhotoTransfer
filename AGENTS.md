# AGENTS.md — SonyPhotoTransfer 项目AI协作指南

> 本文档写给后续接手本项目的 AI Agent / 开发者，便于快速理解项目背景、踩坑历程和维护要点。

---

## 1. 项目概述

**SonyPhotoTransfer** 是一款第三方 Android App，用于通过 Wi-Fi 从索尼 ZV-E10（初代，固件 v2.02）传输照片和视频到安卓手机。

### 为什么要做它？
- 索尼官方 **Imaging Edge Mobile (IEM)** 在 ColorOS / HyperOS 上有严重 Bug：切后台即闪退，无法稳定传图。
- 用户旅行在外，只有相机 + 手机 + USB 线（无 OTG），需要一款极简、稳定的 Wi-Fi 传图工具。
- 项目全程通过 GitHub Actions 自动编译 APK，实机迭代验证。

### 已实现能力
- 自动发现相机 Wi-Fi（通过 DHCP 网关）
- PTP/IP 协议直连相机 15740 端口
- 列出所有照片 / RAW / 视频
- 显示缩略图
- 按拍摄时间从新到旧排序
- 多选下载到 DCIM/SonyTransfer
- **大文件流式下载**：2GB+ 视频也能下，不会 OOM

---

## 2. 协议踩坑历程（非常重要）

### 2.1 尝试一：JSON-RPC（端口 10000）
索尼老款相机的 Camera Remote API 走 JSON-RPC + HTTP。
**失败**：ZV-E10 在“发送到智能手机”模式下 10000 端口未开启。

### 2.2 尝试二：SOAP / UPnP（端口 64321）
端口确实开着，返回了 `DigitalImagingDesc.xml`。
**失败**：所有 SOAP 控制端点（如 `/upnp/control/ContentDirectory`）都返回 404，控制接口已被新固件架空。

### 2.3 最终方案：PTP/IP（端口 15740）
从 XML 中深挖出关键线索：
- `X_ServerVersion=3.00`
- `X_PTP_Versions=3.00`
- `X_PTP_PairingNecessity=Unnecessary`（无需配对）

确认 ZV-E10 新固件将“发送到智能手机”底层协议换成了 **PTP/IP (ISO 15740)**，端口 **15740**。

于是把项目从 HTTP/OkHttp/Coil 彻底重构为 PTP/IP 二进制协议。

---

## 3. 核心技术与架构

### 3.1 技术栈
- Kotlin
- Jetpack Compose + Material 3
- libptp（Java 库，直接复制源码集成，路径 `app/src/main/java/com/fimagena/libptp/`）
- GitHub Actions CI 自动编译 APK

### 3.2 关键文件

| 文件 | 职责 |
|------|------|
| `SonyCameraClient.kt` | PTP/IP 连接、列目录、下载、缩略图 |
| `CameraViewModel.kt` | UI 状态、Wi-Fi 绑定、下载队列 |
| `MainActivity.kt` | Compose UI、网格、缩略图懒加载 |
| `MediaSaver.kt` | 保存到 MediaStore（图片/视频） |
| `DataBuffer.java` | libptp 数据缓冲，**大文件流式下载关键改造点** |
| `PtpIpSession.java` | libptp 会话层，**流式数据接收改造点** |
| `PtpSession.java` | libptp 高层 API，新增 `getObjectToStream()` |
| `PtpTransport.java` | 接口层，新增带 `OutputStream` 的 executeTransaction |

### 3.3 PTP/IP 连接流程
```
手机连相机 Wi-Fi
  → 读 DHCP 网关（通常是 192.168.122.1）
  → PTP/IP connect 到 192.168.122.1:15740
  → openSession()
  → getStorageIDs()
  → getObjectHandles(storageId)  // 不过滤格式，拿全部
  → 对每个 handle 调 getObjectInfo() 获取文件名、大小、拍摄时间、格式码
  → sortByDescending { captureDate }
```

### 3.4 下载流程
```
用户选择项目
  → getObjectSize() 获取总大小
  → MediaSaver.openOutputStream() 打开 MediaStore 输出流
  → PtpSession.getObjectToStream(handle, outputStream, listener)
       → DataBuffer 启用 streaming 模式
       → PTP/IP 每个数据包直接写入文件流
  → finalizePendingUri() 标记下载完成
```

---

## 4. 流式下载改造详解

这是本项目最核心、最困难的改动。原始 libptp 的 `getObject()` 会把整个文件读进内存再返回 `byte[]`，对大视频必然 OOM。

### 改造点 1：DataBuffer 增加 streaming 模式
- 新增 `enableStreaming(OutputStream)`
- `writeObject(byte[])` 在 streaming 模式下直接写到文件流，不写入内部 `ByteArrayOutputStream`
- `readObject()` 在 streaming 模式下返回空数组（数据已流到文件）

### 改造点 2：PtpTransport.Session 接口扩展
新增：`executeTransaction(Request, DataLoadListener, OutputStream)`

### 改造点 3：PtpIpSession 数据接收
在 `StartData` 包到达时：
```java
if (outputStream != null) {
    dataIn.enableStreaming(outputStream);
}
```
后续 `Data` / `EndData` 包自动走 streaming。

### 改造点 4：PtpSession 高层 API
新增：
```java
void getObjectToStream(ObjectHandle handle, OutputStream stream, DataLoadListener listener)
```

### 改造点 5：SonyCameraClient
`downloadPhotoToStream()` 调用 `getObjectToStream()`。

> 经过这次改造，内存占用与大文件大小解耦，2GB+ 视频也可以稳定下载。

---

## 5. 已踩的坑 & 注意事项

### 5.1 网络绑定
国产 ROM 会阻止 Socket 级别的 `bindToNetwork()`，报 `EPERM`。
**解决**：使用进程级绑定 `connectivityManager.bindProcessToNetwork(network)`。

### 5.2 ZV-E10 的 GetPartialObject 不支持
标准 PTP 有 `GetPartialObject (0x101b)` 用于分块下载。
ZV-E10 实际行为：offset=0 的请求能成功，offset>0 立刻返回 `0x2009 InvalidObjectHandle`。
**结论**：索尼这台相机的 GetPartialObject 实现不完整，不能用。
**解决**：改走真正的流式 `GetObject`。

### 5.3 缩略图
视频文件通常没有缩略图，`getThumb()` 会抛异常。
**处理**：thumbnail 加载加 try-catch，失败时显示占位图标，不能影响主流程。

### 5.4 格式码
相机返回的格式码不完全遵循标准：
- JPEG 可能是 `0x3801`
- RAW (ARW) 是 `0xB101`
- 视频格式码不固定，需要按文件名扩展名兜底判断

**策略**：只跳过 `0x3001`（文件夹），其余全部保留；再用格式码 + 扩展名分类。

### 5.5 MediaStore 清理
Android 10+ 写入 MediaStore 时设置 `IS_PENDING=1`，失败后必须删除或标记为失败，否则会留下只有几百字节的损坏文件。

---

## 6. 构建与发布

### 6.1 本地构建
```bash
./gradlew assembleDebug
```
APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

### 6.2 GitHub Actions
工作流文件：`.github/workflows/android.yml`
- 每次 push 到 main 自动触发
- 编译 debug APK
- 产物可在 Actions → 最新 run → Artifacts 中下载

### 6.3 发布到 GitHub Releases
```bash
# 先获取最新 Actions 产物中的 APK
# 然后创建 release
gh release create v0.1.0 app-debug.apk --title "SonyPhotoTransfer v0.1.0" --notes "..."
```

---

## 7. 未来维护 & 更新建议

### 7.1 如果要支持更多索尼机型
- 不同机型可能有不同的 PTP 端口或配对要求
- 建议先扫常见端口：10000、15740、64321
- 测试 `getThumb()` 和 `getObjectToStream()` 行为是否一致

### 7.2 如果要支持缩略图缓存到磁盘
目前缩略图缓存在内存 ConcurrentHashMap 中，照片多的时候仍会占内存。
可改为：
- 缩略图保存到 `context.cacheDir` / `thumbnails/`
- 用 `handle` 做文件名（如 `thumb_<handle>.jpg`）

### 7.3 如果要支持后台下载
目前下载绑定在 ViewModelScope，切后台可能被系统杀。
可改为：
- WorkManager + Foreground Service
- 下载进度通过 Notification 展示

### 7.4 如果要支持实时取景 / 遥控拍照
ZV-E10 的 PTP/IP 应该支持 `InitiateCapture`。
可扩展：
- 添加拍照按钮
- 调用 `PtpSession.initiateCapture()`

### 7.5 如果要支持更多视频格式
目前识别：`.mp4 .mov .mts .m2ts .avi`
若相机生成 `.mpg` / `.avchd` / `.xavc` 等，需要扩展 `classifyFormat()` 中的扩展名列表。

### 7.6 内存与性能
- `largeHeap="true"` 已加，但流式下载后其实不再需要，可保留作为保险
- 缩略图加载可限制并发数量（如用 Semaphore），避免同时解码大量缩略图

### 7.7 稳定性
- PTP/IP 连接在相机休眠或切换模式时会断开，需要优雅的重新连接逻辑
- 当前 `disconnect()` 只是关闭 socket，下次进入会重建 session

### 7.8 安全性
- 不要硬编码 API key 或凭证到代码中
- 当前项目无后端，不需要网络权限之外的敏感权限

---

## 8. 设计哲学

- **极简**：只做传图，不做编辑、不做社交
- **稳定优先**：宁可慢一点，也不能闪退
- **开源可维护**：libptp 源码直接集成，方便针对索尼设备魔改
- **日志驱动**：关键节点都有 Log，出问题先看 logcat

---

## 9. 常用调试命令

```bash
# 只看本 App 日志
logcat -v time | grep -E "SonyCam|CameraVM"

# 抓崩溃
logcat -v time | grep -E "FATAL|AndroidRuntime|SonyCam|CameraVM|Exception"

# 确认相机端口开放
nc -vz 192.168.122.1 15740
```

---

## 10. 参考资源

- libptp 原始仓库：https://github.com/Fimagena/libptp
- PTP/IP 协议：ISO 15740
- 索尼 Imaging Edge Mobile（官方 App，本项目灵感受挫之源）

---

**维护者备注**：
每次修改 libptp 内部类后，务必在真机上测试大文件（>500MB）下载，模拟器和小文件无法暴露 OOM 问题。
