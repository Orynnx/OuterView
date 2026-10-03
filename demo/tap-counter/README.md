# 轻触计数器

OuterView 的原创 JsCanvas 学习示例：点击加一、减一或归零，范围为 0–999999，尝试用 `localStorage` 保存数值。绘制在右侧，左侧预留镜头区域。

本样例随 `3.0.0` 源码发布，供本地学习。**此说明不声明原生安装、背屏触摸、存储或休眠恢复已实机通过；OuterView 的应用登记恢复功能也尚未完成真机验收。** 打包、构建与单元测试结果不能替代这些验证，实际结果需逐项记录。

## 构建

在仓库根目录运行：

```bash
python3 demo/tap-counter/build_example.py
```

Windows 可用 `py -3 demo/tap-counter/build_example.py`。脚本只依赖 Python 标准库，从 `counter.js` 生成：

- `manifest.xml`：MAML Widget + JsCanvas + 内嵌 Script。
- `assets/index.html`：同一脚本的 HTML 载体。
- `preview.html`：桌面 Canvas 预览，可切换 904×572 / 976×596。
- `rearscreen` / `rearscreen.zip`：相同的原始 MAML ZIP。
- `tap-counter-learning.zip`：包含 `description.xml` 和 `rearscreen` 的主题外包。
- `build-report.json`：源文件及包的 SHA256；构建脚本不执行设备测试。

请修改 `counter.js` 后重新生成，避免手动编辑多个脚本副本。脚本会检查 XML 和包装分隔符，但这不是完整的 JavaScript 或宿主兼容性测试。

## 预览与导入

用浏览器打开 `preview.html` 验证基本绘制和点击。需要稳定的本地来源时，可在该目录运行 `python3 -m http.server 8765`，然后打开 `http://localhost:8765/preview.html`；结束后停止服务器。

在 OuterView 中选择 `tap-counter-learning.zip`，检查名称与脚本提示后确认导入。也可以选择 `rearscreen.zip` 验证 raw 格式；缺少外层名称、图标和预览时会使用默认信息。

测试期间避免同时从多个管理界面修改应用：OuterView 只串行处理自己的请求，跨客户端变更并非原子事务。遇到超时、仍在执行或状态校验失败时，先刷新核对，不要立即重复导入。原生流程可能已改变部分状态；OuterView 不会因结果未知而追加删除资源，也不保证自动回滚。仅有系统管理记录的条目经用户确认移除时会保留资源文件。

浏览器与 JsCanvas 使用同一段业务脚本，但宿主 API、触摸坐标、存储隔离和生命周期可能不同。不要仅凭浏览器预览声称背屏功能已完成，也不要把 JsCanvas 直接等同于 WebView。

验证建议：三种按钮与数值边界、滑动取消点击、离开再进入、存储恢复、宿主重启，以及分别通过 OuterView/系统管理页移除后的登记、管理记录和资源文件状态。各项单独记录结果，区分预期保留与未确认的清理状态。本地原始学习资源应保留；不在此样例中加入账号、密钥或真实外部设备控制动作。

接口与格式详见 [应用包开发](../../docs/CARD_DEVELOPMENT.md)。本目录源码遵循仓库 [LICENSE](../../LICENSE)。
