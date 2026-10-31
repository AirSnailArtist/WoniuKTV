import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import MusicPlaylistsView from './MusicPlaylistsView.vue'
import {musicRequest} from '../api/music'
import api from '../api/client'
const {push,applySnapshot}=vi.hoisted(()=>({push:vi.fn(),applySnapshot:vi.fn()}))
vi.mock('../api/music',()=>({musicRequest:vi.fn()}))
vi.mock('../api/client',()=>({default:{control:vi.fn()}}))
vi.mock('vue-router',()=>({useRouter:()=>({push})}))
vi.mock('../stores/player',()=>({usePlayerStore:()=>({orderedSongIds:new Set(),applySnapshot})}))
let app,root
const flush=async()=>{await Promise.resolve();await nextTick();await nextTick()}
beforeEach(()=>{root=document.createElement('div');document.body.append(root);musicRequest.mockReset().mockResolvedValue([])})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks()})
async function mount(){app=createApp(MusicPlaylistsView);app.mount(root);await flush()}
it('waits for an explicit playlist submission and preserves metadata-only local ordering',async()=>{
 await mount();expect(musicRequest).toHaveBeenCalledWith('playlists/recent');expect(musicRequest).toHaveBeenCalledTimes(1)
 Array.from(root.querySelectorAll('button')).find(b=>b.textContent==='手动输入链接').click();await flush();const input=root.querySelector('input');input.value='https://music.163.com/playlist?id=123';input.dispatchEvent(new Event('input'));await flush();expect(musicRequest).toHaveBeenCalledTimes(1)
 musicRequest.mockResolvedValueOnce({ref:'netease:123',title:'歌单',total:1,page:1,hasMore:false,items:[{rank:1,title:'歌',artists:['歌手'],available:true,song:{id:'456'}}]})
 root.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await flush();api.control.mockResolvedValue({state:'playing'});root.querySelector('article button').click();await flush()
 expect(api.control).toHaveBeenCalledWith('order',{song_id:'456'});expect(push).not.toHaveBeenCalled()
})
it('sends an unavailable playlist song to Bilibili with artist context',async()=>{
 await mount();musicRequest.mockResolvedValueOnce({ref:'qq:123',title:'歌单',total:1,page:1,hasMore:false,items:[{rank:1,title:'歌',artists:['歌手'],available:false}]})
 Array.from(root.querySelectorAll('button')).find(b=>b.textContent==='手动输入链接').click();await flush();const input=root.querySelector('input');input.value='https://y.qq.com/n/ryqq/playlist/123';input.dispatchEvent(new Event('input'));root.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await flush();root.querySelector('article button').click();await flush()
 expect(push).toHaveBeenCalledWith({name:'bilibili',query:{keyword:'歌 歌手 MV',matchTitle:'歌',matchArtists:'["歌手"]'}});expect(api.control).not.toHaveBeenCalled()
})

it('shows names and sources and opens phone transfer without typing',async()=>{
 musicRequest.mockResolvedValueOnce([{ref:'netease:123',title:'我的音乐',total:42},{ref:'qq:456',title:'通勤歌单',total:20}]);await mount();
 expect(root.querySelector('input')).toBeNull();expect(root.textContent).toContain('我的音乐');expect(root.textContent).toContain('网易云音乐 · 42 首');expect(root.textContent).toContain('QQ音乐 · 20 首');
 const listener=vi.fn();window.addEventListener('woniu-open-phone',listener);Array.from(root.querySelectorAll('button')).find(b=>b.textContent==='手机扫码传歌单').click();expect(listener).toHaveBeenCalledTimes(1);window.removeEventListener('woniu-open-phone',listener)
})
