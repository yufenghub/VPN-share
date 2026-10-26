# VPN share 1.2.7-adb

基于 Shizzi v0.4.0-rc.3，提交 `4f8f049a2090db25fa7346c1581d9d4b59351d2a`。本版内置无线 ADB，不依赖 Shizuku，不需要 root。黑白界面，布局参考 Clash Meta Android。

## 配对与使用

1. 首次配对时，手机连接 Wi-Fi，在开发者选项打开“无线调试”。
2. 应用打开“ADB → 配对”，允许通知。若“配对码”通知被关闭或降为低优先级，应用会打开该通知渠道的系统设置；开启通知和横幅/悬浮通知，返回后继续。未连接 Wi-Fi 时会先打开 Wi-Fi 面板。
3. 打开无线调试，点击“使用配对码配对设备”。**保持系统配对码窗口打开，不要切回主应用。** 发现端口后发布高优先级通知，在顶部横幅中点击“输入配对码”，输入当前六位码后点击系统发送按钮。通知内回复由 RemoteInput 提供，不申请悬浮窗权限，也不打开应用输入 Activity。
4. 若横幅没有出现，下拉通知栏并展开“输入配对码”通知，点击通知动作“输入配对码”后输入并发送。是否允许横幅由系统通知设置及勿扰模式控制；可从 ADB 的“高级设置 → 配对通知设置”调整。旧端口关闭后旧通知回复失效，需使用新窗口对应的新通知。mDNS 发现失败时确认无线调试及系统配对码窗口已经打开，并允许应用后台联网。若使用 MIUI 且通知回复异常，Shizuku 官方建议将通知样式切为原生样式。
5. 系统热点设置中配置名称、密码，连接已有 VPN。系统接口检查通过后点击“启动共享”。热点设备直接连接，无需代理配置。
6. 停止使用时点击“停止共享”或通知中的停止按钮。

手动配对字段用于分屏等能保留系统配对码窗口的场景。

## 一次配置的范围

- 配对私钥加密保存在应用私有目录，使用 AndroidKeyStore AES-GCM 保护。重开应用和同签名覆盖升级复用原密钥，不因普通连接错误重新生成。
- 应用优先重连已启动的本地 shell 服务。服务不存在时，尝试保存的无线调试连接端口，再通过 mDNS 发现新的本机端口。
- Android 可能在重启、断开 Wi-Fi 或网络变化时关闭无线调试。普通应用不能自行绕过该限制；服务被结束后，需要重新开启无线调试以激活服务。系统授权到期、撤销配对、卸载或清除数据后可能需要重新配对。
- 因此不能承诺“配置一次后，任何重启及网络条件下永久可用”。

## 原理及支持范围

ADB 仅用于启动独立的 shell 权限服务。服务使用测试网络 TUN 和 `setPreferTestNetworks` 接管系统热点出口；Go/gVisor 处理热点 TCP/UDP，将新连接绑定到已有 VPN。客户端不设置代理。VPN 规则/全局模式取决于原 VPN 如何处理这些连接；分应用 VPN 需允许本应用和系统 Shell。

应用与服务通过仅监听 127.0.0.1 的 TCP 通信，避免依赖宽松 SELinux 策略。256 位随机令牌用作共享密钥，HMAC-SHA256 随机挑战实现双向认证，每次 RPC 使用独立方向密钥和 AES-256-GCM 加密；令牌不直接发送。服务仅提供固定操作白名单，启动时检查 shell UID、应用 UID 和安装路径。无线 ADB 也仅连接本机 TLS 端口。

64 位 Android，包含 ARM64、x86_64。建议 Android 13 及以上；Android 11–12 取决于系统热点模块接口。本版不安装系统模块，不提供 VPN 节点。厂商隐藏 API 和真实热点的兼容性需实机验证。

本版强制使用已有 VPN；VPN 句柄为零时转发核心拒绝默认路由。VPN 消失后会停止并尝试关闭热点，不能据此承诺已验证的零泄漏。MuMu 检查也不能代替真实热点联网验证。

## 构建与测试

需要 JDK 17+、SDK API 35、NDK 28.2.13676358、CMake 3.22.1、Go 1.26.3。Gradle Wrapper 8.14.3。

```powershell
.\build.ps1 -AndroidSdkPath 'E:\AndroidSDK' -JavaPath 'E:\生产力\Android\jbr' -GoRootPath 'C:\Program Files\Go'
```

脚本在项目内写缓存，运行应用单元测试和 Lint，结束后停止 Gradle。输出 `app/build/outputs/apk/debug/app-debug.apk`。发布版本需自己的签名；源码包不包含本地签名私钥。

`adb` 模块内置 LibADB 3.1.1 和原生 SPAKE2，详情见 `adb/NOTICE.md`。原始 Java 算法在固定测试中失败，保留于 `adb/reference` 供参考，不编译进应用；实际实现使用 `adb/src/main/cpp`。

原生密码学桌面测试需先用本机 C 编译器和 JDK JNI 头文件构建 `spake2` 动态库，设置 `SPAKE2_TEST_LIB_DIR` 指向该目录，再运行 `:adb:testDebugUnitTest`。Windows 示例编译参数见 `adb/test-host.ps1`。Android 设备端测试使用 `:app:assembleDebugAndroidTest` 生成的测试包；测试包与应用签名必须相同。测试入口 `app.vpnshare.hotspot.test/dev.shizzi.AdbRuntimeInstrumentation`，默认检查密钥保存、JNI 密码学及 shell 服务；`-e mode pair` 在系统配对码窗口仍打开时验证实际配对。

原始上游 README 保存在 `README-UPSTREAM.md`，所有原文件的版权与第三方许可证保留。

## 图标

`design/icon.svg` 为白底黑色、倾斜 24° 的单个手柄图案。Android 桌面使用对应 VectorDrawable 和白底自适应图标，无位图放大。

## 配对通知实现依据

见 `design/pairing-notification-references.md`，记录 Shizuku 官方实现、使用手册及 Android 官方 RemoteInput 与通知渠道文档。本版使用通知直接回复；输入后需点击系统发送按钮。

## 配对失败诊断

1.2.3 保留通知内输入，加入 P01–P11 协议阶段错误编号，不再把设备断开、TLS 和协议错误统一显示为“检查配对码和端口”。通知会显示失败阶段；返回应用“ADB → 高级设置 → 复制配对诊断”可复制最近一次记录。记录包含版本、Android 版本、型号、ABI、配对端口、耗时、失败阶段、异常类型链和 TLS 实现，不包含配对码、私钥、TLS 导出材料、认证令牌、SSID 或原始异常消息。

1.2.3 的 P00 / OperatorCreationException / NoSuchAlgorithmException 已定位为显式 Bouncy Castle 签名 Provider 的反射算法实现被 R8 移除，见下节；其他阶段仍保留诊断。同时修复 TLS 包装前连接失败时原始 TCP socket 未关闭的问题，并在配对密码派生后清理临时字节。

原生配对及协议参考：https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/adb/AdbPairingClient.kt 。本地 LibADB 的协议交换异常此前会落入通用提示，诊断不会把设备关闭连接直接认定为配对码错误。

## 1.2.4 证书初始化修复

1.2.3 的混淆映射将 `org.bouncycastle.operator.OperatorCreationException` 映射为 `j3.c`；R8 的 usage 列表包含被移除的 `RSA$Mappings`、`DigestSignatureSpi$SHA256`。旧代码显式构造 BouncyCastleProvider 并指定给证书签名器，运行时通过反射查找的签名实现已不存在，因而在创建 TLS socket 之前失败。

现在使用平台默认 JCA 的 SHA256withRSA 签名及 X.509 CertificateFactory 解析证书；BC 仅用于明确引用的 ASN.1 构造。生成结果校验公钥及自签名，保存的身份加密格式不变，已有密钥不会因升级重新生成，无需清除数据。实现方式与 Shizuku 的证书生成路径一致：https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/adb/AdbKey.kt 。

`tools/certificate-r8-regression.py` 使用项目同版 R8 在桌面复现旧实现裁剪后的 OperatorCreationException → NoSuchAlgorithmException，并运行裁剪后的生产证书工厂验证新实现。此检查不依赖模拟器，不代替 AndroidKeyStore 或真实手机的完整无线 ADB 测试。调用时指定 `--java-home`、`--gradle-cache`、可选 `--output`；依赖使用项目缓存中的 AGP 8.9.2 和 BC 1.81。

## 1.2.5 界面调整

“复制配对诊断”仅在 ADB 的“高级设置”展开后显示，收起时隐藏。诊断采集及复制内容不变。

图标采用候选 04：白底黑色线框单手柄，倾斜 24°，上方热点波纹、下方圆形按键。SVG 及 Android VectorDrawable 同步，自适应前景按安全区域缩放。

应用名称为 `VPN share`，桌面名称、主页标题、通知渠道、快捷设置磁贴与新配对设备名称保持一致。应用 ID 和签名不变。

## 1.2.6 复用已开启热点

启动共享时读取系统 Wi-Fi 热点状态；热点已开启或正在开启时直接复用，未开启时才请求启动。不再在正常启动流程调用 stopTethering。停止共享及异常清理继续沿用原有逻辑。

## 1.2.7 热点与共享的开关顺序

- 热点已开启时复用；未开启时先启动热点，确认 Wi-Fi AP 为 ENABLED 后再建立 TUN、绑定 VPN 和切换共享出口。UNKNOWN、正在开启、USB 网络共享和 dumpsys 超时不再被视为热点已就绪。
- 使用 Wi-Fi AP 状态广播，并每秒读取状态兜底；热点被关闭时结束共享、释放 TUN 和监视器，应用同步为停止状态并移除前台通知。
- 手动停止共享时同时停止热点；热点已在关闭或已关闭时不重复发送关闭请求。停止完成前保持“停止中”，阻止重复开关。
- 会话操作串行执行，并使用会话代次过滤旧监视器回调及过期状态响应，避免上一轮关闭事件影响新一轮共享。
