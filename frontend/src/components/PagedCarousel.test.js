import {afterEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,ref} from 'vue'
import PagedCarousel from './PagedCarousel.vue'
vi.mock('swiper/vue',()=>({
  Swiper:{template:'<div class="swiper"><slot /></div>',mounted(){this.$emit('swiper',{activeIndex:0,update(){},slideTo(){}})}},
  SwiperSlide:{template:'<div class="swiper-slide swiper-slide-active"><slot /></div>'}
}))
let app
const flush=async()=>{await nextTick();await new Promise(r=>setTimeout(r,20));await nextTick()}
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.unstubAllGlobals()})
async function setup(){
  vi.stubGlobal('requestAnimationFrame',fn=>setTimeout(fn,0))
  const items=ref([{id:'old-a'},{id:'old-b'}]),root=document.createElement('div');document.body.append(root)
  app=createApp({render:()=>h(PagedCarousel,{items:items.value,pageSize:2,queryKey:'same'},{default:({items})=>items.map(item=>h('button',{key:item.id},item.id))})});app.mount(root);await flush()
  root.querySelectorAll('.swiper-slide button')[1].focus()
  return {items,root}
}
it('preserves the focused card position when polling replaces the songs',async()=>{
  const {items}=await setup();items.value=[{id:'new-a'},{id:'new-b'}];await flush()
  expect(document.activeElement.textContent).toBe('new-b')
})
it('does not steal focus if the user moves out while replacement cards render',async()=>{
  const {items}=await setup();const outside=document.createElement('button');document.body.append(outside)
  items.value=[{id:'new-a'},{id:'new-b'}];await nextTick();outside.focus();await flush()
  expect(document.activeElement).toBe(outside)
})
