import {afterEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import ChartRecommendations from './ChartRecommendations.vue'
import api from '../api/client'
import {biliRequest} from '../api/bilibili'
const {push,applySnapshot}=vi.hoisted(()=>({push:vi.fn(),applySnapshot:vi.fn()}))
vi.mock('../api/bilibili',()=>({biliRequest:vi.fn()}))
vi.mock('vue-router',()=>({useRouter:()=>({push})}))
vi.mock('../stores/player',()=>({usePlayerStore:()=>({orderedSongIds:new Set(),isPlaying:true,applySnapshot})}))
vi.mock('../api/client',()=>({default:{discovery:vi.fn(),control:vi.fn(),chartCandidates:vi.fn()}}))
vi.mock('./PagedCarousel.vue',()=>({default:{props:['items'],template:'<div><slot :items="items" /></div>'}}))
let app
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks()})
async function show(item){
 api.discovery.mockResolvedValue({loading:false,sources:[],items:[item],recommendations:[item]})
 const root=document.createElement('div');document.body.append(root);app=createApp(ChartRecommendations);app.mount(root)
 await Promise.resolve();await nextTick();await nextTick();return root
}
it('sends a missing chart song to Bilibili with explicit title and artist context',async()=>{
 const root=await show({source:'netease',rank:1,title:'测试歌',artists:['测试歌手'],available:false,status:'missing'})
 root.querySelector('.chart-song').click();await nextTick()
 expect(push).toHaveBeenCalledWith({name:'bilibili',query:{keyword:'测试歌 测试歌手 MV',matchTitle:'测试歌',matchArtists:'["测试歌手"]'}})
 expect(api.control).not.toHaveBeenCalled()
})
it('orders an existing local version without making an online search',async()=>{
 const root=await show({source:'netease',rank:1,title:'测试歌',artists:['测试歌手'],available:true,song:{id:'123',title:'测试歌'}})
 api.control.mockResolvedValue({state:'playing'});root.querySelector('.chart-song').click();await Promise.resolve()
 expect(api.control).toHaveBeenCalledWith('order',{song_id:'123'});expect(push).not.toHaveBeenCalled()
})

it('shows discovered chart channels and checks for updates while visible',async()=>{
 vi.useFakeTimers();Object.defineProperty(document,'visibilityState',{value:'visible',configurable:true})
 const root=await show({source:'netease_new',rank:1,title:'新歌',artists:['人'],available:false})
 api.discovery.mockResolvedValue({loading:false,sources:[{id:'netease_new',label:'网易 · 新歌'}],items:[],recommendations:[]})
 window.dispatchEvent(new Event('discovery-updated'));await Promise.resolve();await nextTick()
 root.querySelector('[aria-label="选择榜单"]').click();await nextTick();expect(document.querySelector('[role="dialog"]').textContent).toContain('网易 · 新歌')
 api.discovery.mockClear();await vi.advanceTimersByTimeAsync(60000);expect(api.discovery).toHaveBeenCalledTimes(1)
 Object.defineProperty(document,'visibilityState',{value:'hidden',configurable:true});api.discovery.mockClear();await vi.advanceTimersByTimeAsync(60000);expect(api.discovery).not.toHaveBeenCalled();vi.useRealTimers()
})

it('keeps remote focus inside the chart picker and restores the trigger on confirm and back',async()=>{
 const root=await show({source:'qq',rank:1,title:'歌',artists:[],available:false})
 api.discovery.mockResolvedValue({loading:false,sources:[{id:'qq',label:'QQ 抖音热歌'},{id:'netease_new',label:'网易 · 新歌'}],items:[],recommendations:[]})
 window.dispatchEvent(new Event('discovery-updated'));await Promise.resolve();await nextTick()
 const trigger=root.querySelector('[aria-label="选择榜单"]');trigger.click();await nextTick();expect(document.activeElement.dataset.chart).toBe('all')
 function key(k){document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:k,bubbles:true,cancelable:true}))}
 key('ArrowRight');expect(document.activeElement.dataset.chart).toBe('qq');key('Enter');await nextTick();expect(document.activeElement).toBe(trigger);expect(trigger.textContent).toContain('抖音热歌')
 trigger.click();await nextTick();expect(document.activeElement.dataset.chart).toBe('qq');key('Escape');await nextTick();expect(document.querySelector('[aria-label="选择音乐榜单"]')).toBeNull();expect(document.activeElement).toBe(trigger)
})

it('orders a Bilibili ranking video directly instead of searching by its title',async()=>{
 const root=await show({source:'bili_music',rank:1,title:'音乐视频',artists:['UP主'],bvid:'BV1GJ411x7h7',available:true,status:'video'})
 biliRequest.mockResolvedValueOnce({songId:'8000000000000000001'});root.querySelector('.chart-song').click();await Promise.resolve();await nextTick()
 expect(biliRequest).toHaveBeenCalledWith('order','POST',{bvid:'BV1GJ411x7h7'});expect(push).not.toHaveBeenCalled();expect(api.control).not.toHaveBeenCalled()
})
