# OuterView 2.3.0

OuterView 2.3.0 新增完整的背屏壁纸管理能力，并恢复 Assistant 与 Wallpaper Hook 共存。

## 新功能

- Assistant、Wallpaper、About 三栏底部导航与现代页面转场。
- 安全导入、列举、应用、重命名和删除 OuterView 自有 MRC/ZIP 壁纸。
- OuterView 壁纸同步进入小米系统背屏列表，可由系统列举和应用。
- 友好壁纸名称同步写入 runtime 与 metadata。
- About 页面注明项目基于 REAREye、作者凛野及 GitHub 仓库地址。

## 深空时钟

Release 附带 `Deep-Space-Clock-2.3.0.mrc`。布局基于小米 17 Pro/Max 背屏截图划定摄像头安全区：

- 设计坐标 `x < 320` 不放置有意义内容；
- 标题、时间、日期与状态全部布置在右侧卡片；
- 主时间中心位于设计坐标 `x = 700`。

## 安装

安装 `OuterView-2.3.0.apk`，在 LSPosed 中启用 OuterView，并勾选“小米背屏中心”
`com.xiaomi.subscreencenter`。升级后重启背屏中心或设备。

项目地址：https://github.com/Orynnx/OuterView
