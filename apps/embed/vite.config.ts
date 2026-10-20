import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: { port: 5174 },
  build: {
    // 作为独立部署产物，直接输出静态文件
    outDir: 'dist',
    emptyOutDir: true,
  },
});
