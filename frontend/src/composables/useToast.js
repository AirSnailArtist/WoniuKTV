/**
 * 全局轻量 toast 提示 composable。
 * 用于点歌反馈等场景，例如「已加入队列，前面还有 N 首」。
 *
 * Global lightweight toast composable.
 * Used for song-queue feedback such as "Added to queue, N songs ahead."
 *
 * @module useToast
 */
import { ref } from 'vue'

// 全局轻量 toast（详设：点歌反馈「已加入队列，前面还有 N 首」）
// Global lightweight toast (spec: song-queue feedback "Added to queue, N songs ahead")
const message = ref('')
let timer = null

/**
 * 创建并返回 toast 实例。
 *
 * Creates and returns a toast instance.
 *
 * @returns {{ message: import('vue').Ref<string>, toast: (msg: string, ms?: number) => void }}
 *          message — 当前显示的提示文本 / current toast text;
 *          toast   — 显示提示的函数 / function to show a toast.
 */
export function useToast() {
  /**
   * 显示一条 toast 提示，并在指定毫秒后自动消失。
   *
   * Shows a toast message that auto-dismisses after the given milliseconds.
   *
   * @param {string} msg  - 提示文本 / toast text
   * @param {number} [ms=1800] - 显示时长（毫秒）/ display duration in milliseconds
   */
  function toast(msg, ms = 1800) {
    message.value = msg
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { message.value = '' }, ms)
  }
  return { message, toast }
}
