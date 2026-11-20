import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: '/chatry/',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/chatry/api': {
        target: 'http://127.0.0.1:8084',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/chatry\/api/, '/api'),
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
