import {afterEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import CategoryBrowseView from './CategoryBrowseView.vue'
import api from '../api/client'
vi.mock('../api/client',()=>({default:{browseArtists:vi.fn()}}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{}})}))
vi.mock('../components/TabBar.vue',()=>({default:{template:'<div />'}}))
vi.mock('../components/SearchKeyboard.vue',()=>({default:{template:'<div />'}}))
let app
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks()})
const flush=async()=>{await Promise.resolve();await nextTick();await nextTick()}
it('requests only the selected cursor page and preserves paging button focus',async()=>{
  const first=Array.from({length:8},(_,i)=>({name:'歌星'+i,songCount:100-i}))
  api.browseArtists.mockResolvedValueOnce({items:first,hasMore:true,after:'cursor-8'})
  const root=document.createElement('div');document.body.append(root)
  app=createApp(CategoryBrowseView);app.component('router-link',{props:['to'],template:'<a href="#"><slot /></a>'});app.mount(root);await flush()
  expect(api.browseArtists).toHaveBeenLastCalledWith({scope:'chinese',after:'',keyword:'',initial:''})
  expect(root.querySelectorAll('.artist-list a')).toHaveLength(8)
  api.browseArtists.mockResolvedValueOnce({items:[{name:'最后一位',songCount:1}],hasMore:false,after:'last'})
  const next=[...root.querySelectorAll('.artist-paging button')].find(b=>b.textContent==='下一页')
  next.focus();next.click();await flush()
  expect(api.browseArtists).toHaveBeenLastCalledWith({scope:'chinese',after:'cursor-8',keyword:'',initial:''})
  expect(root.querySelectorAll('.artist-list a')).toHaveLength(1)
  expect(document.activeElement).toBe(next);expect(next.disabled).toBe(false)
  next.click();expect(api.browseArtists).toHaveBeenCalledTimes(2)
  api.browseArtists.mockResolvedValueOnce({items:first,hasMore:true,after:'cursor-8'})
  root.querySelector('.artist-paging button').click();await flush()
  expect(api.browseArtists).toHaveBeenLastCalledWith({scope:'chinese',after:'',keyword:'',initial:''})
})
