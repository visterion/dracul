import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// Create a virtual module that exports empty object (for CSS stubs)
const cssStub = '\0virtual:css'

export default defineConfig({
  plugins: [
    vue(),
    {
      name: 'css-stub',
      resolveId(id: string) {
        if (id.endsWith('.css')) {
          return cssStub
        }
      },
      load(id: string) {
        if (id === cssStub) {
          return 'export default {}'
        }
      },
    },
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.{spec,test}.ts'], // Block C+D nutzt .test.ts-Namen
  },
})
