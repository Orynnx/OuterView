# 贡献指南

1. 从 `main` 创建短分支，保持修改集中。
2. 不提交签名密钥、设备私有数据、无法确认再分发权利的媒体或第三方反编译源码。
3. 新代码不得复制 REAREye 或其他非宽松许可项目的实现；只提交可独立说明来源的代码。
4. 新运行时依赖必须与 GPL-3.0 兼容；优先选用 MIT、BSD、Apache-2.0 等宽松许可证，
   并更新 `LICENSES/NOTICE.md` 和对应完整通知。
5. 行为变化同步更新 README、Changelog 或对应文档。

提交前运行：

```powershell
.\gradlew.bat :core:testDebugUnitTest :app:assembleDebug
py -3 demo/hello-card/build_card.py --check
py -3 tools/audit_reareye_similarity.py --reareye C:\path\to\REAREye
.\tools\verify-runtime-licenses.ps1
```

Hook 兼容改动需要说明设备型号、系统版本、`com.xiaomi.subscreencenter` 版本和已脱敏的
运行证据。贡献代码默认按 GNU GPL-3.0 授权。
