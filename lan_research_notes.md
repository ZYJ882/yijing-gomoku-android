# 局域网联机功能调研结论

- Android 官方 Network Service Discovery（NSD）可用于同一局域网内发布、扫描和连接服务，文档明确将多人游戏列为适用的点对点场景。
- NSD 使用 DNS-SD 服务发现机制。房主可注册服务实例，客户端按服务类型发现后解析出主机与端口，再建立连接。
- 房主不应硬编码监听端口；应以 `ServerSocket(0)` 让系统分配空闲端口，并将该端口通过服务广播提供给客户端。
- Android 会在服务名冲突时更改实际服务名，因此发布成功后的服务名应以回调返回值为准。
- 对 target Android 13（API 33）及以上、管理 Wi-Fi 连接的应用，Android 官方要求申请 `NEARBY_WIFI_DEVICES` 运行时权限；在旧版本中则可能需要声明位置权限。当前实现会在需要扫描/开房时请求附近 Wi-Fi 权限，并对拒绝授权给出可理解的提示。
- 架构选择：使用 NSD/mDNS 扫描房间 + 单房主 `ServerSocket` 的 TCP 长连接同步 JSON 事件。该方案不需要互联网服务器或账号，适合“同一 Wi-Fi 下扫描房间后加入”这一产品需求；双方均必须在同一局域网，且局域网不能隔离客户端设备。

来源：
1. https://developer.android.com/develop/connectivity/wifi/use-nsd
2. https://developer.android.com/develop/connectivity/wifi/wifi-permissions

调研日期：2026-08-27
