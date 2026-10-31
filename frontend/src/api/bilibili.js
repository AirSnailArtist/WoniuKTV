const pending = new Map()
let sequence = 0
const generation = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2,12)
export function folderReference(value) {
  const raw = value.trim()
  if (/^ml[0-9]{1,20}$/.test(raw)) return raw.slice(2)
  if (!/^https:\/\//.test(raw)) return ''
  let url
  try { url = new URL(raw) } catch { throw new Error('收藏夹链接格式不正确') }
  if (!['www.bilibili.com', 'space.bilibili.com'].includes(url.hostname)) throw new Error('请粘贴B站收藏夹的完整分享链接')
  const id = url.searchParams.get('fid') || url.searchParams.get('media_id') || url.pathname.match(/\/(?:medialist\/(?:detail|play)|list)\/ml([0-9]{1,20})(?:\/|$)/)?.[1]
  if (!id || !/^[0-9]{1,20}$/.test(id)) throw new Error('这个链接不是支持的收藏夹链接，请复制包含 fid 的收藏夹链接')
  return id
}
window.addEventListener('woniu-bili-response', event => {
  const data = event.detail, call = pending.get(data?.id)
  if (!call) return
  pending.delete(data.id); clearTimeout(call.timer)
  if (data.ok) call.resolve(data.data)
  else call.reject(new Error(data.message || 'B站请求未完成'))
})
export function biliRequest(route, method = 'GET', body = {}) {
  if (!window.Woniu?.biliRequest) return Promise.reject(new Error('请在新版窝牛应用中使用B站功能'))
  if (pending.size >= 2) return Promise.reject(new Error('请求处理中，请稍候'))
  return new Promise((resolve, reject) => {
    const id = 'bili-' + generation + '-' + (++sequence)
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('B站请求超时，请重试')) }, 22000)
    pending.set(id, { resolve, reject, timer })
    try { window.Woniu.biliRequest(id, '/bilibili/' + route, method, method === 'GET' ? '' : JSON.stringify(body)) }
    catch (error) { clearTimeout(timer); pending.delete(id); reject(error) }
  })
}
