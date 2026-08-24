# OuterView 3.0 许可证转换与来源边界

## 许可边界

OuterView 3.0 当前工作树由版权所有者 Orynnx 按 MIT License 发布。Git 历史中的 2.x
版本已经按 GPL-3.0 发布，该许可不会被追溯撤销。需要只取得 MIT 内容时，应下载 3.0.0
或之后的 source archive，或检出首次 MIT 转换提交及其后继版本；不要把旧提交中的文件
当作 MIT 内容复制。

## 与 REAREye 的工程隔离

3.0 删除了四个与 REAREye 历史源码逐行相同的工具文件，并重新实现宿主入口发现：

- 不再加载 DexKit native library；
- 不再使用 DexKit API、MMKV 或 REAREye 的缓存/版本工具；
- 使用 OuterView 自有查询模型读取宿主 `classes*.dex`；
- 只依赖 BSD-3-Clause 的 `com.android.tools.smali:smali-dexlib2`；
- 项目代码和 AIDL 使用 `org.orynnx.outerview.core` 命名空间。

对 REAREye 的名称引用仅允许出现在历史说明、冲突诊断、审计工具参数，以及旧
OuterView 数据的兼容常量中。这些引用不是代码链接或运行时依赖。

## 可重复相似代码审计

审计器读取 REAREye 仓库所有 Git 引用中的唯一 Kotlin、Java、AIDL、Gradle 与 XML
历史 blob，并与 OuterView 当前源码比较：

```powershell
py -3 tools/audit_reareye_similarity.py --reareye C:\path\to\REAREye
```

检查包含：规范化后的完整文件 SHA-256、连续 20 个非空代码行、连续 120 个词法 token，
以及连续 300 个标识符归一化结构 token。`package` 与 `import` 行不参与片段判定，以免
把 Compose/Android 公共导入列表误认为实现复制。阈值用于发现实质性相似片段，不能替代
法律意见，但能作为每次发布前的工程门禁。

对当前树全部文件与 REAREye 全历史 Git blob 的额外精确对象比对，只允许命中上游
Apache-2.0 Gradle Wrapper 的三个标准文件：`gradlew`、`gradlew.bat` 与
`gradle-wrapper.jar`。OuterView 自有源码、清单和媒体资源不允许存在完全相同对象。

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

早期 Dino Run Demo 包含无法随包核验许可证的图片和音频，3.0 已将其从当前树和发布包
删除。Hello Card 使用 MAML 原生矩形、圆形和文本，不包含第三方媒体。应用图标为
OuterView 的独立设计，来源说明见 `branding/README.md`。

## 非法律意见

本文记录工程来源、依赖和验证边界，不构成法律意见。商业发布者仍应根据其发行地区、
分发方式和签名渠道自行完成法律审查。
