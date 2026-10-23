import {afterEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import {createPinia} from 'pinia'
import PersonalRecommendations from './PersonalRecommendations.vue'
import api from '../api/client'
vi.mock('../api/client',()=>({default:{recommendations:vi.fn()}}))
vi.mock('vue-router',()=>({useRouter:()=>({push:vi.fn()})}))
vi.mock('./PagedCarousel.vue',()=>({default:{template:'<div />'}}))
let app
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks()})
const flush=async()=>{await Promise.resolve();await nextTick();await nextTick()}
it('keeps the remote focus on refresh while loading and after replacing recommendations',async()=>{
  api.recommendations.mockResolvedValueOnce({items:[]})
  const root=document.createElement('div');document.body.append(root)
  app=createApp(PersonalRecommendations);app.use(createPinia());app.mount(root);await flush()
  let finish
  api.recommendations.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
  const button=[...root.querySelectorAll('button')].find(b=>b.textContent==='换一批')
  button.focus();button.click();await nextTick()
  expect(button.disabled).toBe(false);expect(button.getAttribute('aria-busy')).toBe('true');expect(document.activeElement).toBe(button)
  button.click();expect(api.recommendations).toHaveBeenCalledTimes(2)
  finish({items:[{song:{id:'next'}}]});await flush()
  expect(document.activeElement).toBe(button);expect(button.getAttribute('aria-busy')).toBe('false')
})
