import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 5175,
    // 文档源文件在仓库根 docs/，需要允许多读一层
    fs: { allow: [fileURLToPath(new URL('../..', import.meta.url))] },
  },
});
