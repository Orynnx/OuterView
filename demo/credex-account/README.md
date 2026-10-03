# Credex 账户速览 1.1.0

适用于 Xiaomi 原生背屏智能应用区的 MAML／JsCanvas 应用包。跟随 [Credex](https://github.com/NickWoluff/Credex)「设置 → 背屏配置」中选择的 Assistant 来源。

下载 [Credex-account-1.1.0.zip](https://github.com/Orynnx/OuterView/releases/download/v3.0.0/Credex-account-1.1.0.zip)，在 OuterView 中导入后，使用系统智能应用管理添加、应用卡片。

- Codex：5 小时与每周额度、窗口详情、约数重置倒计时；点击数值切换已用／剩余。
- 其他服务：保留原始余额或额度、货币单位、状态、更新时间和详情。百分比的含义跟随 Credex，不能自动理解成剩余比例。
- 左右滑动翻页，点击查看详情；长详情继续点击翻段。长按 650 毫秒隐藏或恢复数值和详情。
- 点右上状态查看来源、登录、缓存或连接提示，点「读取」重新查询。左侧三分之一留给镜头。
- 未知值显示破折号，真实的 0 保留；不会将缺失数据转成 0。

## 数据接口

`content://com.nickwoluff.credex/quota/assistant`

ContentProviderBinder → MAML 变量 → JsCanvas HostVar → `data.cq`。仅使用 Credex 导出的展示数据。上游目前只导出选中的来源，切换账户需要在 Credex 内完成。三个余额槽位按实际返回数量显示。

Provider 查询会触发 Credex 自行调度刷新，最短间隔 60 秒；进入／恢复卡片重新读取，数据变化由 Provider 通知。Codex 重置字符串没有年份或时间戳，因此倒计时标注「约」；其他服务的重置说明保留原文。

参考源码版本：`NickWoluff/Credex@3515708712f6836d2d35e557f0874a3de3d9cbdc`。本示例的画面、脚本与打包代码为项目原创，按仓库 GPL-3.0 许可分发。

## 构建与预览

需要 Node.js、Python 3 和 Pillow：

```bash
python -m pip install Pillow
node --test test.js
python build.py
python preview_server.py
```

打开 `http://127.0.0.1:8767/preview.html`。演示场景仅用于预览，不会写进应用脚本或手机。真实预览需要安装 Credex、连接 ADB 手机；多设备时加 `--serial <设备编号>`，ADB 不在 PATH 时加 `--adb <路径>`。

输出 `credex-account-rearapp-1.1.0.zip`（推荐导入）及 `credex-account-rearscreen.mrc`（原始资源）。`preview.png` 使用演示数据。

20 项逻辑测试通过，浏览器验证 904×572、976×596 的来源切换、详情分页和隐私交互；包由 OuterView 实际解析器接受。手机原生渲染、读取按钮和 Provider 通知尚待实测。
