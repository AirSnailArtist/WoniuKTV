import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({base:'./',plugins:[vue()],build:{outDir:'../app/src/main/assets/songbook',emptyOutDir:true},test:{environment:'jsdom',globals:true}})
