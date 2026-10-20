import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    lib: {
      entry: fileURLToPath(new URL('src/index.ts', import.meta.url)),
      name: 'HChatWidget',
      fileName: 'hchat-web-component-chat',
      formats: ['es', 'umd'],
    },
  },
});
