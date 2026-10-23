import { reactive } from 'vue'

/**
 * 对话框状态管理模块。
 * 提供确认框和提示框的打开/关闭控制，基于 Vue reactive 状态驱动。
 *
 * Dialog state management module.
 * Provides open/close control for confirm and alert dialogs, driven by Vue reactive state.
 */

const state = reactive({
  open: false,
  mode: 'confirm',
  title: '',
  message: '',
  tone: 'default'
})

// Promise 的 resolve 引用，用于在关闭时回传结果 / Reference to the Promise resolve, used to pass result back on close
let settle = null

/**
 * 打开对话框的内部方法。
 * 会先关闭上一个未完成的对话框，然后用传入选项重置状态并返回 Promise。
 *
 * Internal method to open a dialog.
 * Closes any previously unsettled dialog first, then resets state with the given options and returns a Promise.
 *
 * @param {object} options - 对话框配置 / Dialog configuration
 * @param {string} [options.mode] - 对话框模式 / Dialog mode
 * @param {string} [options.title] - 标题 / Title
 * @param {string} [options.message] - 消息内容 / Message content
 * @param {string} [options.tone] - 色调风格 / Tone style
 * @returns {Promise<boolean>} 对话框关闭时 resolve / Resolves when the dialog closes
 */
function open(options) {
  if (settle) settle(false)
  Object.assign(state, { open: true, mode: 'confirm', title: '', message: '', tone: 'default', ...options })
  return new Promise(resolve => { settle = resolve })
}

/**
 * 关闭对话框的内部方法。
 * 如果对话框未打开则无视调用；否则关闭并触发 Promise resolve。
 *
 * Internal method to close a dialog.
 * No-ops if the dialog is not open; otherwise closes it and triggers the Promise resolve.
 *
 * @param {*} result - 关闭时回传的结果 / Result to pass back on close
 */
function close(result) {
  if (!state.open) return
  state.open = false
  const resolve = settle
  settle = null
  resolve?.(result)
}

/**
 * 打开确认对话框。
 * 默认标题为"请确认操作"，色调为 warning，等待用户确认后返回 boolean。
 *
 * Open a confirm dialog.
 * Defaults to title "请确认操作" with warning tone; returns a boolean after user confirms.
 *
 * @param {string} message - 提示消息 / Prompt message
 * @param {object} [options] - 额外配置 / Additional options
 * @returns {Promise<boolean>} 用户确认结果 / User confirmation result
 */
export function confirmDialog(message, options = {}) {
  return open({ mode: 'confirm', title: '请确认操作', message, tone: 'warning', ...options })
}

/**
 * 打开提示对话框（单按钮）。
 * 默认标题为"操作提示"，色调为 error，仅用于通知用户。
 *
 * Open an alert dialog (single button).
 * Defaults to title "操作提示" with error tone; intended for notifying the user only.
 *
 * @param {string} message - 提示消息 / Prompt message
 * @param {object} [options] - 额外配置 / Additional options
 * @returns {Promise<boolean>} 对话框关闭时 resolve / Resolves when the dialog closes
 */
export function alertDialog(message, options = {}) {
  return open({ mode: 'alert', title: '操作提示', message, tone: 'error', ...options })
}

/**
 * 对话框组合式函数。
 * 返回状态引用和便捷的 open/close/confirm/alert 方法，供 Vue 组件使用。
 *
 * Dialog composable.
 * Returns reactive state refs and convenient open/close/confirm/alert methods for Vue components.
 *
 * @returns {{ state: object, close: function, confirm: function, alert: function }}
 */
export function useDialog() {
  return { state, close, confirm: confirmDialog, alert: alertDialog }
}
