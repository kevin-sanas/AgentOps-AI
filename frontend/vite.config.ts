import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174, strictPort: true,
    proxy: { '/api': { target: process.env.BACKEND_API_ORIGIN || 'http://127.0.0.1:18080', changeOrigin: true } },
  },
  preview: { port: 4173, strictPort: true },
  build: { sourcemap: false },
})
