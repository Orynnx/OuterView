# 贡献指南

感谢参与 OuterView。

1. 从 `main` 创建短分支。
2. 保持修改集中，不提交 APK、签名密钥、设备日志或系统私有文件。
3. Kotlin 代码使用 JDK 17，并遵循现有格式。
4. 行为变化需要更新 README、Changelog 或对应文档。
5. 提交前运行：

```bash
./gradlew :core:testDebugUnitTest :app:assembleDebug
python demo/dino-run/build_demo.py --check
```

Hook 反射点涉及具体系统版本。新增兼容逻辑时，请在 Issue 或 PR 中说明设备型号、系统版本、`com.xiaomi.subscreencenter` 版本和不含隐私的诊断证据。

贡献代码默认按 GPL-3.0 授权。提交媒体素材时必须同时提交明确许可证和来源，不接受无法确认再分发权利的资源。
