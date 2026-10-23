// Skip config bundling when the host's esbuild executable cannot traverse
// sandboxed parent directories. The application configuration stays identical.
import vue from '@vitejs/plugin-vue'
import { build } from 'vite'
import { startVitest } from 'vitest/node'
const plugins = [vue()]
if (process.argv.includes('--build')) {
  await build({ configFile: false, base: './', plugins,
    build: { outDir: '../app/src/main/assets/songbook', emptyOutDir: true } })
} else {
  const runner = await startVitest('test', [], { config: false,
    environment: 'jsdom', globals: true, watch: false, maxWorkers: 2, minWorkers: 1 }, {plugins})
  if (!runner) process.exitCode = 1
  else { if (runner.state.getUnhandledErrors().length || runner.state.getFiles().some(f => f.result?.state === 'fail')) process.exitCode = 1; await runner.close() }
}
