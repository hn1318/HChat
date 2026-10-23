# hchat-react-chat

HChat 的 React 聊天组件：会话列表、聊天窗口、输入框，以及一个开箱即用的悬浮 Widget。

```bash
npm i hchat-react-chat hchat-core-sdk
```

```tsx
import { ChatWidget } from 'hchat-react-chat';
import 'hchat-react-chat/styles.css';

<ChatWidget
  baseUrl="https://chat.example.com"
  appId="app_demo"
  token={token}
  mode="c2c"
  conversation={{ productId: '1001', sellerId: 'seller_1' }}
/>;
```

需要自己排版时用底层组件（选中会话由 `ConversationList` 内部完成，其余组件从 Context 读 `activeId`）：

```tsx
import { ChatProvider, ConversationList, ChatWindow, MessageInput } from 'hchat-react-chat';

<ChatProvider baseUrl="https://chat.example.com" appId="app_demo" token={token}>
  <div style={{ display: 'flex', gap: 12 }}>
    <ConversationList />
    <div style={{ flex: 1 }}>
      <ChatWindow />
      <MessageInput />
    </div>
  </div>
</ChatProvider>;
```

- 状态用 Context + peer 组件管理，不强制引入 Redux / Zustand
- 样式走 CSS 变量，`data-theme="dark"` 可切深色
- 依赖 `react` / `react-dom` >= 18（peer）

接入文档见 [docs/integration.md](https://github.com/hchat-dev/hchat/blob/main/docs/integration.md)。

Apache-2.0
