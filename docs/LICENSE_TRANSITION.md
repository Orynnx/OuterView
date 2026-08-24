# OuterView 2.3.2 许可证迁移与来源边界

## 许可边界

OuterView 2.3.2 是许可证迁移版本：当前工作树与该版本起的新分发由版权所有者 Orynnx
按 GNU GPL-3.0 发布。版本号和 `versionCode` 同步恢复为 2.3.2 / 9，以延续 2.x 版本线。

此前已经按其他许可证发布或取得的副本，仍按其取得时的授权条款处理；本文件不试图追溯
变更那些授权。发布者应以随当前版本分发的 [LICENSE](../LICENSE) 为准。

## 与 REAREye 的工程隔离

2.3.2 删除了四个与 REAREye 历史源码逐行相同的工具文件，并重新实现宿主入口发现：

- 不再加载 DexKit native library；
- 不再使用 DexKit API、MMKV 或 REAREye 的缓存/版本工具；
- 使用 OuterView 自有查询模型读取宿主 `classes*.dex`；
- 只依赖 BSD-3-Clause 的 `com.android.tools.smali:smali-dexlib2`；
- 项目代码和 AIDL 使用 `org.orynnx.outerview.core` 命名空间。

对 REAREye 的名称引用仅允许出现在历史说明、冲突诊断、审计工具参数，以及旧
OuterView 数据的兼容常量中。这些引用不是代码链接或运行时依赖。

## 可重复相似代码审计

审计器读取 REAREye 仓库所有 Git 引用中的唯一文本源码 blob，并与 OuterView 当前源码比较。
此外，它对两个树中全部未忽略文件做字节级完整文件比对；唯一允许的相同文件是路径也相同的
上游 Apache-2.0 Gradle Wrapper 标准文件：`gradlew`、`gradlew.bat` 与
`gradle/wrapper/gradle-wrapper.jar`，以及 SHA-256 固定的根目录 GNU GPL-3.0 标准
许可证文本 `LICENSE`：

```powershell
py -3 tools/audit_reareye_similarity.py --reareye C:\path\to\REAREye
```

检查包含连续 20 个非空代码行、连续 120 个词法 token、连续 300 个标识符归一化结构
token，以及 Kotlin/Java/Kotlin DSL 的函数级比对。函数级门禁会比较完整函数的归一化结构
token：双方至少 40 个 token 且相似度至少 90% 即失败，因此能发现短小但结构近乎相同的
反射或兼容辅助函数。`package` 与 `import` 行不参与片段判定，以免把 Compose/Android
公共导入列表误认为实现复制。阈值用于发现实质性相似片段，不能替代法律意见，但能作为
每次发布前的工程门禁。

## 运行时依赖审计

以下命令输出 Gradle 实际解析后的 `debugRuntimeClasspath`，而非可能包含被替换版本的
声明树：

```powershell
.\tools\verify-runtime-licenses.ps1
```

所有坐标必须能映射到 `LICENSES/NOTICE.md` 中的宽松许可族。发布 APK 还必须确认不存在
`libdexkit.so`、`org.luckypray.dexkit`、`com.tencent.mmkv` 或 RearEye 类，并确认
`assets/` 内含第三方通知和完整许可证文本。

## 素材边界

早期 Dino Run Demo 包含无法随包核验许可证的图片和音频，2.3.2 已将其从当前树和发布包
删除。Hello Card 使用 MAML 原生矩形、圆形和文本，不包含第三方媒体。应用图标为
OuterView 的独立设计，来源说明见 `branding/README.md`。

## 非法律意见

本文记录工程来源、依赖和验证边界，不构成法律意见。商业发布者仍应根据其发行地区、
分发方式和签名渠道自行完成法律审查。
