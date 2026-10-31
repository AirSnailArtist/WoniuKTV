import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import FavoritesView from './FavoritesView.vue'
import api from '../api/client'
const {push,applySnapshot,favorites}=vi.hoisted(()=>({push:vi.fn(),applySnapshot:vi.fn(),favorites:{ids:['1']}}))
vi.mock('../api/client',()=>({default:{smartFavorites:vi.fn(),control:vi.fn(),removeFavorite:vi.fn()}}))
vi.mock('vue-router',()=>({useRouter:()=>({push})}))
vi.mock('../stores/player',()=>({usePlayerStore:()=>({orderedSongIds:new Set(),applySnapshot})}))
vi.mock('../stores/favorites',()=>({useFavoritesStore:()=>favorites}))
vi.mock('../components/PagedCarousel.vue',()=>({default:{props:['loader','queryKey'],data:()=>({rows:[]}),async mounted(){this.rows=(await this.loader(0)).slice(0,6)},watch:{async queryKey(){this.rows=(await this.loader(0)).slice(0,6)}},template:'<div><slot :items="rows" /></div>'}}))
let app,root
const flush=async()=>{await Promise.resolve();await nextTick();await nextTick()}
beforeEach(()=>{root=document.createElement('div');document.body.append(root);favorites.ids=['1'];api.smartFavorites.mockReset().mockResolvedValue({items:[{song:{id:'1',title:'歌',artist:'人'},available:true}],hasMore:false})})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks()})
async function mount(){app=createApp(FavoritesView);app.mount(root);await flush()}
it('loads only the selected smart category with a bounded cursor page',async()=>{
 await mount();expect(api.smartFavorites).toHaveBeenCalledWith('manual','',6);[...root.querySelectorAll('.smart-tabs button')].find(x=>x.textContent==='经常唱').click();await flush();expect(api.smartFavorites).toHaveBeenLastCalledWith('frequent','',6)
})
it('removes a manual favorite without deleting history or Bilibili cloud favorites',async()=>{
 await mount();api.removeFavorite.mockResolvedValue({});root.querySelector('.smart-remove').click();await flush();expect(api.removeFavorite).toHaveBeenCalledWith('1');expect(favorites.ids).toEqual([]);expect(api.control).not.toHaveBeenCalled()
})
