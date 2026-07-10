# 架构

```text
Compose UI
  -> RearCardManagementEndpoints
  -> App 私有 ZIP / registry
  -> 签名权限 Binder
  -> com.xiaomi.subscreencenter 中的 OuterView Hook
  -> 宿主模板目录与私有 registry
  -> Smart Assistant 原生 Post/Remove 管线
  -> manager list -> MAML loader -> 背屏
```

## 为什么不使用通知

早期版本通过真实静默通知触发 `handleNotificationPosted`。这条链路容易被用户划除通知、通知权限和系统清理策略打断。Host API v3 在宿主进程中构造等价 extras，并直接调用已解析的原生 Post Runnable；隐藏则调用宿主按 package/business 移除方法。

## 一致性

显示和隐藏最多等待 5 秒，以 manager list 和 live widget 证据作为成功条件。删除先确认 runtime 消失，再删除宿主模板。宿主离线时，本地删除会留下 cleanup tombstone，后续连接时补做清理。

## 文件边界

宿主模板位于当前用户的 `subscreencenter/smart_assistant/reareye_custom_<cardId>`，是无扩展名 ZIP。Hook 只删除规范化后位于该目录且名称符合专属前缀的文件。系统模板与 `notification_widget.json` 永远只读。
