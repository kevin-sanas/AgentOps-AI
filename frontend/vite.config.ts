import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue(),tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src',import.meta.url)) } },
  server: {
    port: 5174, strictPort: true,
    proxy: { '/api': { target: process.env.BACKEND_API_ORIGIN || 'http://127.0.0.1:18080', changeOrigin: true } },
  },
  preview: { port: 4173, strictPort: true },
  build: { sourcemap: false },
})
