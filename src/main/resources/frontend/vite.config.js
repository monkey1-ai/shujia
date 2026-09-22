import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 说明：开发态用 proxy 把 /api 与 /rag 等请求转发到后端 8080，避免跨域；
// 生产态 build 产物直接输出到 Spring Boot 的 static 目录，与后端同源部署。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 评测、健康检查、记忆清理等 /api 前缀接口
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // RAG 问答接口
      '/rag': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 清空会话记忆接口
      '/cleanMemory': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 后端健康检查（actuator）
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // 打包产物直接落到后端 static 目录，随 jar 一起发布
    outDir: '../static',
    emptyOutDir: false
  }
})
