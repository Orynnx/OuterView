# 智能应用包开发

当前 `3.0.0` 导入器面向 **MAML Widget 内的 JsCanvas/Script**。它不再提供旧版 Smart Assistant payload、模板替换或壁纸管理接口。

## 两种输入格式

原始 MAML ZIP（扩展名可以是 `.zip` 或 `.mrc`）：

```text
my-app.zip
├── manifest.xml
└── assets/
    └── index.html     可选；是否使用由资源与宿主决定
```

一层主题外包：

```text
my-app.zip
├── rearscreen        内层 ZIP，或改名为 rearScreen.mrc，二者只能有一个
├── description.xml   可选
├── app/
│   └── app_icon.png  可选
└── preview/
    └── preview.png   可选；也支持 JPEG/WebP 预览
```

不要再套一层目录。原始包的 `manifest.xml` 必须位于根目录；外包根目录不能同时有 `manifest.xml` 与内层资源。只支持上述一层外包，不接受额外的 ZIP/MRC 资产嵌套。

未提供外层元数据时名称默认为“智能应用”，用户可在确认时修改。未提供图标或预览时由宿主创建默认图片。输入中的资源 ID、绝对路径或自定义 registry 不参与部署；最终 ID 由 OuterView 生成。

## 最小入口

```xml
<?xml version="1.0" encoding="utf-8"?>
<Widget version="2" screenWidth="904" frameRate="30" clearCanvas="true">
    <JsCanvas name="my_counter" x="0" y="0" w="#view_width" h="#view_height">
        <Script><![CDATA[
            // 在这里放置面向宿主兼容层编写的 JavaScript。
            const canvas = document.getElementById('c');
            const ctx = canvas.getContext('2d');
            ctx.fillStyle = '#101820';
            ctx.fillRect(0, 0, window.CANVAS_W || 904, window.CANVAS_H || 572);
        ]]></Script>
    </JsCanvas>
</Widget>
```

Parser 要求根为 `Widget`，有 1 至 8 个 JsCanvas，并且每个都有非空内嵌 Script。示例采用 version=2；仅通过这一结构检查不能证明任意脚本语法或 API 在宿主中可用。

`JsCanvas` 是 MAML 元素，不能仅凭 `document`、`window` 或同包 HTML 就把它等同于 WebView。桌面浏览器可预览 Canvas 逻辑，但运行引擎、API 兼容层及权限需按实际宿主验证。

## 名称与外层元数据

支持以下两种描述根节点，优先读取 `appName`，其次 `title`：

```xml
<theme>
    <resourceType>rearscreen</resourceType>
    <appName>轻触计数器</appName>
</theme>
```

```xml
<MIUI-Theme>
    <title>轻触计数器</title>
    <author>Local learning example</author>
</MIUI-Theme>
```

描述只提供展示信息，不是取得系统权限或指定安装目录的途径。名称应为 1 至 80 个字符，不含控制字符。

## 尺寸、触摸与存储

- 根据 `#view_width/#view_height` 和宿主提供的 Canvas 尺寸计算缩放；预留镜头遮挡区。904×572、976×596 是示例坐标，不是所有设备的固定规格。
- 绘制与触摸应使用同一变换：把事件坐标逆映射到逻辑画布后判断命中；区分轻触、滑动和取消。
- 对缺失的时间或其他宿主数据提供默认值。验证数据是在启动时注入还是持续更新，避免与本地计时重复累计。
- `localStorage`、刷新节奏、休眠/AOD节流和重启恢复必须在宿主中单独测试；浏览器行为不能作为这些能力的证明。
- 静止内容不应依赖常驻高帧率动画；处理页面退出与再次进入。

## 校验范围

当前限制：输入不超过 16 MiB；内外层累计解压不超过 32 MiB、512 项；XML 不超过 2 MiB；选用图标/预览不超过 8 MiB。大于 1 MiB 的条目还检查 200:1 压缩比上限。

路径穿越、绝对路径、重复/大小写冲突、文件目录冲突、CRC不一致、DOCTYPE及实体声明会被拒绝。所有 XML 检查格式与实体声明。原生 MAML Binder、Intent、MethodCommand 和其他命令正常传递给系统宿主，不按能力名称拒绝；具体运行能力由宿主实现与 Android 权限决定。JavaScript 明确允许，并显示执行脚本提示；包校验不是代码沙箱。

## 学习与验证

原创 [轻触计数器](../demo/tap-counter/README.md) 从同一 `counter.js` 生成 JsCanvas 和浏览器版本：

```bash
python3 demo/tap-counter/build_example.py
```

建议依次检查包结构、桌面逻辑、宿主导入、背屏实际显示/触摸、退出再进入、宿主重启，以及从 OuterView 和系统管理页分别移除后的持久化一致性。每项记录独立结果；导入返回成功不等同于每个交互均已验证。
