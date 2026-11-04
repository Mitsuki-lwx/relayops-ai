# Proposal

## Why
当前回调目录只有本机固定 HTTP 接收端，且签名密钥不能轮换，限制了生产团队把结果交付到受控 HTTPS 接收方。

## What Changes
- 新增受控 HTTPS 目的地目录：只接受部署配置白名单中的主机、HTTPS、固定 `/relayops/events` 路径；浏览器不能提交任意 URL。
- OWNER 可在无活跃交付时轮换目的地签名密钥；旧密钥立即失效并记录审计。
- 保留本机演示目录、签名、重试、TTL、幂等、授权和回放边界。

## Capabilities
### New Capabilities
- `external-delivery`: 受控外网 HTTPS 目的地与签名密钥轮换。

### Modified Capabilities
无；原本机目录和交付契约保持。

## Impact
DestinationService/Controller、WebhookTransport、配置、前端目的地面板、后端/浏览器测试和运维文档。无数据库迁移。
