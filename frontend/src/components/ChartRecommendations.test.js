import {afterEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import ChartRecommendations from './ChartRecommendations.vue'
import api from '../api/client'
const {push,applySnapshot}=vi.hoisted(()=>({push:vi.fn(),applySnapshot:vi.fn()}))
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
