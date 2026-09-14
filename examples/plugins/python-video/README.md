# Python 插件示例

本项目是独立运行的 Python 服务，可打包为 Docker 镜像。示例读取平台商品资料并生成视频简报；配置 `modelId` 后，通过宿主非流式模型 HTTP API 写脚本。它尚不生成视频文件。

```powershell
$env:PLUGIN_SERVICE_TOKEN = 'replace-with-your-service-token'
python main.py
# 或
docker compose up --build -d
```

宿主添加 `agent-start-plugin-agent`，启用 Web 模块，并配置：

```yaml
spring-agent:
  plugin:
    host-base-url: http://host.docker.internal:18090/agent-start/plugin-host/v1
    host-signing-secret: ${PLUGIN_HOST_SIGNING_SECRET}
    grants:
      "[example.product-video-python]": [product.read, model.chat]
    remotes:
      - manifest: file:C:/your-project/examples/plugins/python-video/manifest.json
        endpoint: http://localhost:8091
        token: ${PLUGIN_SERVICE_TOKEN}
        timeout: 120s
```

`host-signing-secret` 至少 32 字节，由宿主生成保存，与插件服务 Token 不同，不能发送给插件。多宿主实例使用同一签名密钥。`host-base-url` 必须是插件容器能访问的地址。真实跨主机环境使用 TLS。宿主自己的登录鉴权层需让这些端点使用插件调用令牌校验，不能把该令牌误当用户登录令牌。

宿主注册 `PluginHostCapability`，名称为 `product.read`，根据传入身份查询本租户商品；也可以使用 `HttpPluginHostCapability` 指向内部服务，或 `PluginToolCapability` 指向已经注册的 MCP 工具。输入 `productId` 不代表访问许可，宿主查询必须验证数据权限。

通过已有 Connector 控制台同步安装并启用插件。工作流选择插件 Action `prepare`，配置商品编号、风格、可选平台模型 ID。

## 直接调用模型

平台调用插件时在请求里附加 `host = {baseUrl, token, expiresAt}`。Python 代码使用：

```python
from host_client import PluginHost

response = PluginHost(body['host']).chat(
    model_id='your-tenant-model-id',
    messages=[{'role': 'user', 'content': '请生成商品视频脚本'}],
)
script = response['text']
```

这是一次普通非流式 POST。插件只持有短期调用令牌，不持有平台模型厂商的 API Key。模型 ID 在签名身份所属租户中解析；不接受请求参数覆盖租户或模型服务地址。

## 验证

`python -m unittest -v` 验证插件协议。Java `PluginRuntimeTest` 验证 HTTP 往返。Docker 可用时另行执行构建和容器健康检查，源码测试不能证明镜像已运行。
