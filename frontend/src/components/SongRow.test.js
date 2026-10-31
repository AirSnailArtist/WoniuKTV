import {afterEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick} from 'vue'
import SongRow from './SongRow.vue'
const mocks=vi.hoisted(()=>({toggle:vi.fn().mockResolvedValue(true),toast:vi.fn(),ordered:new Set()}))
vi.mock('../stores/favorites',()=>({useFavoritesStore:()=>({has:()=>false,toggle:mocks.toggle})}))
vi.mock('../stores/player',()=>({usePlayerStore:()=>({orderedSongIds:mocks.ordered})}))
vi.mock('../stores/user',()=>({useUserStore:()=>({clientToken:'local'})}))
vi.mock('../composables/useToast',()=>({useToast:()=>({toast:mocks.toast})}))
let app
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks();mocks.ordered.clear()})
it('orders by the whole card and keeps favorite and plus actions independent',async()=>{
 const order=vi.fn(),root=document.createElement('div');document.body.append(root)
 app=createApp({render:()=>h(SongRow,{song:{id:'1',title:'美人鱼',artist:'林俊杰'},onOrder:order})});app.mount(root)
 root.querySelector('.t span').click();expect(order).toHaveBeenCalledTimes(1)
 root.querySelector('.order-btn').click();expect(order).toHaveBeenCalledTimes(2)
 root.querySelector('.favorite-btn').click();await nextTick();expect(mocks.toggle).toHaveBeenCalledOnce();expect(order).toHaveBeenCalledTimes(2)
 expect(root.querySelector('.songrow').getAttribute('role')).toBe('button')
})
it('does not order an already queued song when the card is clicked',()=>{
 mocks.ordered.add('1');const order=vi.fn(),root=document.createElement('div');document.body.append(root)
 app=createApp({render:()=>h(SongRow,{song:{id:'1',title:'美人鱼'},onOrder:order})});app.mount(root)
 root.querySelector('.songrow').click();expect(order).not.toHaveBeenCalled();expect(mocks.toast).toHaveBeenCalledWith('这首歌已点')
})
