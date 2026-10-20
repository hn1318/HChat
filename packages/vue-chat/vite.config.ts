import { fileURLToPath } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [vue()],
  build: {
    lib: {
      entry: fileURLToPath(new URL('src/index.ts', import.meta.url)),
      name: 'HChatVueChat',
      fileName: 'hchat-vue-chat',
      formats: ['es', 'umd'],
    },
    rollupOptions: {
      external: ['vue', 'hchat-core-sdk', 'hchat-shared-types'],
      output: {
        globals: {
          vue: 'Vue',
          'hchat-core-sdk': 'HChatCore',
          'hchat-shared-types': 'HChatTypes',
        },
      },
    },
  },
});
