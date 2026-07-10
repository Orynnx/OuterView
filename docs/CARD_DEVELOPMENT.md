# 卡片适配指南

## 最小 ZIP 结构

```text
my-card.zip
├── manifest.xml
├── reareye-card.json       可选
└── assets/
    └── image.png
```

`manifest.xml` 必须位于 ZIP 顶层，根节点必须是 `<Widget>`，并声明 `version="2"`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<Widget version="2"
        frameRate="30"
        screenWidth="1080"
        scaleByDensity="false">
    <Rectangle w="#view_width" h="#view_height" fillColor="#FF101418"/>
</Widget>
```

不要在 ZIP 外再套一层目录。文件名区分大小写。

## 元数据

可选的 `reareye-card.json` 只影响导入展示和默认 payload，不能覆盖最终 business 或宿主路径：

```json
{
  "schemaVersion": 1,
  "name": "My Card",
  "author": "Your Name",
  "version": "1.0.0",
  "defaultMamlConfig": {}
}
```

OuterView 首次导入时生成随机 128 位 `cardId`，并固定使用 `reareye_custom_<cardId>` 作为 business。替换模板不会改变身份。

## 尺寸与交互

- 使用 `#view_width`、`#view_height` 适配宿主实际区域。
- 固定设计稿可以通过统一 `scale` 和居中 offset 映射，Dino Run 使用 480 x 304 逻辑画布。
- 触摸区域应使用宿主支持的根层 `touchable` Group/Rectangle，并在真机验证摄像头遮挡区。
- 动画应有稳定尺寸，不要依赖内容变化改变根布局。
- 避免高帧率常驻动画；静止状态暂停 Animation，减少背屏功耗。

## Payload

普通模式下用户编辑 `maml_config`，OuterView 自动包装 rear payload 并强制覆盖 business。需要完整兼容系统业务时可使用高级 rear/focus JSON，但 business 仍由 OuterView 管理。

卡片应为缺失字段准备默认值，不要假定 payload 一定包含网络数据、定位或账户信息。

## 安全要求

导入器限制：

- 压缩包最大 16 MB。
- 解压估算最大 64 MB。
- 最多 1024 个条目。
- 拒绝绝对路径、`..`、DOCTYPE 和非 Widget v2 模板。
- 扫描 `IntentCommand` 与 `ExternCommand`，发现后要求用户确认。

除非功能确实需要，不要使用外部命令、广播或启动 Activity。不得把密钥、账号、设备标识或私有接口凭据写进卡片。

## 打包与测试

Dino Run 提供仅使用 Python 标准库的打包脚本：

```bash
python demo/dino-run/build_demo.py
```

开发卡片建议按以下顺序测试：

1. 在桌面工具或主题环境中确认 XML 基本语法。
2. 用 OuterView 导入，确认预检统计与风险扫描。
3. 显示后检查诊断中的 `templateReadable=true`、`managerListContains=true`、`loadSucceeded=true`。
4. 反复执行显示、隐藏和宿主重启。
5. 删除后确认 manager list 与模板路径均不存在。

完整实例见 [Dino Run](../demo/dino-run/README.md)。
