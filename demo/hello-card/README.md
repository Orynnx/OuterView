# Hello Card Demo

这是一个只使用 MAML 基础图形和文本的最小卡片。仓库内的 XML、元数据与打包脚本均由
OuterView 项目创建，不含字体、图片、音频或其他外部媒体文件。

在 OuterView 中导入 `hello-card.zip` 即可。修改源文件后执行：

```powershell
py -3 demo/hello-card/build_card.py
py -3 demo/hello-card/build_card.py --check
```

包结构只有顶层 `manifest.xml` 与 `outerview-card.json`。
