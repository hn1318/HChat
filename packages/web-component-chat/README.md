# hchat-web-component-chat

HChat Web Component：一个 `<hchat-widget>` 自定义元素，任意框架（甚至无框架）页面都能用。

```bash
npm i hchat-web-component-chat
```

```html
<script type="module">
  import 'hchat-web-component-chat'; // 自动注册 <hchat-widget>
</script>

<hchat-widget
  base-url="https://chat.example.com"
  app-id="app_demo"
  token="…"
  mode="c2c"
  product-id="1001"
  seller-id="seller_1"
></hchat-widget>
```

- Shadow DOM 隔离样式，不受宿主页面 CSS 影响
- 创建会话用：`base-url` / `app-id` / `token` / `mode` / `product-id` / `order-id` / `seller-id` / `agent-id` / `dedupe-key` / `title`
- 打开已有会话用 `conversation-id`；另有 `theme`（light/dark/auto）与 `height`
- 只想注册成别的标签名：`import { defineHChatWidget } from 'hchat-web-component-chat'; defineHChatWidget('my-chat');`

接入文档见 [docs/integration.md](https://github.com/hchat-dev/hchat/blob/main/docs/integration.md)。

Apache-2.0
