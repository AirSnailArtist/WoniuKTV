import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import BilibiliView from './BilibiliView.vue'
import {biliRequest} from '../api/bilibili'
vi.mock('../api/bilibili',async importOriginal=>({...await importOriginal(),biliRequest:vi.fn()}))
vi.mock('../stores/player',()=>({usePlayerStore:()=>({queue:[],nowPlaying:null})}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{}})}))
vi.mock('../components/SearchKeyboard.vue',()=>({default:{template:'<div />'}}))
let app,root
const flush=async()=>{await Promise.resolve();await nextTick();await nextTick()}
beforeEach(()=>{root=document.createElement('div');document.body.append(root);biliRequest.mockReset().mockResolvedValue({loggedIn:false})})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks();vi.useRealTimers()})
async function mount(){app=createApp(BilibiliView);app.mount(root);await flush()}
function button(text){return [...root.querySelectorAll('button')].find(b=>b.textContent===text)}
function input(value){const e=root.querySelector('input');e.value=value;e.dispatchEvent(new Event('input'))}
it('searches only on submit, requests one page, and orders a whole card once while pending',async()=>{
 await mount();input('索尼 MV');await flush();expect(biliRequest).toHaveBeenCalledTimes(1)
 biliRequest.mockResolvedValueOnce({items:[{bvid:'BV1GJ411x7h7',title:'测试 MV',owner:'官方音乐',available:true}],hasMore:true})
 root.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await flush()
 expect(biliRequest).toHaveBeenLastCalledWith('search?keyword=%E7%B4%A2%E5%B0%BC+MV&page=1')
 expect(root.querySelectorAll('.bili-card')).toHaveLength(1)
 let done;biliRequest.mockImplementationOnce(()=>new Promise(resolve=>{done=resolve}))
 const card=root.querySelector('.bili-card');card.click();card.click();await flush()
 expect(biliRequest).toHaveBeenCalledTimes(3);expect(biliRequest).toHaveBeenLastCalledWith('order','POST',{bvid:'BV1GJ411x7h7'})
 done({ordered:true});await flush()
})
it('shows private-folder access failure instead of silently presenting an empty collection',async()=>{
 await mount();button('用户收藏夹').click();await flush()
 biliRequest.mockRejectedValueOnce(new Error('请先登录B站'))
 root.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await flush()
 expect(root.querySelector('[role=alert]').textContent).toContain('请先登录B站')
 expect(root.querySelectorAll('.bili-card')).toHaveLength(0)
})
it('stops QR polling when the login dialog closes',async()=>{
 vi.useFakeTimers();await mount()
 biliRequest.mockResolvedValueOnce({image:'data:image/png;base64,test'})
 button('扫码登录').click();await flush()
 expect(root.querySelector('.phone-qr img')).not.toBeNull()
 button('关闭').click();await flush();await vi.advanceTimersByTimeAsync(15000)
 expect(biliRequest).toHaveBeenCalledTimes(2)
})

it('removes one favorite from an owned folder without ordering a song',async()=>{
 biliRequest.mockResolvedValueOnce({loggedIn:true});await mount();biliRequest.mockResolvedValueOnce({items:[{id:'99',title:'窝牛KTV',count:1}],hasMore:false})
 button('用户收藏夹').click();await flush();biliRequest.mockResolvedValueOnce({items:[{aid:'42',bvid:'BV1GJ411x7h7',title:'测试MV',available:true}],canManage:true,hasMore:false})
 root.querySelector('.bili-card').click();await flush();biliRequest.mockResolvedValueOnce({removed:true}).mockResolvedValueOnce({items:[],canManage:true,hasMore:false})
 root.querySelector('.bili-remove').click();await flush();await flush()
 expect(biliRequest).toHaveBeenCalledWith('favorite/remove','POST',{folder:'99',aid:'42'})
 expect(biliRequest.mock.calls.some(x=>x[0]==='order')).toBe(false)
})
