import {describe,it,expect,afterEach} from 'vitest'
import {JSDOM} from 'jsdom'
import {readFileSync} from 'node:fs'
const html=readFileSync('../app/src/main/assets/phone-songbook.html','utf8')
let dom
const flush=()=>new Promise(r=>setTimeout(r,30))
async function setup(){
 dom=new JSDOM(html,{url:'http://localhost/?token=paired',runScripts:'outside-only'})
 const calls=[]
 dom.window.fetch=async(path,options={})=>{
  calls.push(path)
  let data={}
  if(path.startsWith('/api/phone-state'))data={list:[],queuedIds:['2'],player:{state:'paused'},hasMore:false}
  else if(path.startsWith('/api/recommendations'))data={items:[{song:{id:'2',title:'已选歌曲',artist:'歌手'},origin:'replay',reason:'以前点过'}],learning:true}
  else if(path.startsWith('/api/phone-songs')){const after=new URL('http://localhost'+path).searchParams.get('after');const base=after?20:0;data={items:Array.from({length:20},(_,i)=>({id:String(base+i+1),title:'歌曲'+(base+i+1),artist:'歌手'})),hasMore:!after,after:String(base+20)}}
  return {json:async()=>({ok:true,data})}
 }
 dom.window.eval(dom.window.document.querySelector('script').textContent)
 await flush();return {document:dom.window.document,calls}
}
afterEach(()=>dom?.window.close())
describe('phone vertical songbook',()=>{
 it('shows recommendations and marks a queued song even while paused',async()=>{
  const {document}=await setup();expect(document.querySelector('.reason').textContent).toBe('以前点过');expect(document.querySelector('[data-order-id="2"]').textContent).toBe('已点');expect(document.querySelector('[data-order-id="2"]').disabled).toBe(true);expect(document.querySelector('.swiper')).toBe(null)
 })
 it('appends a cursor page vertically without replacing earlier rows',async()=>{
  const {document,calls}=await setup();document.getElementById('library').click();await flush();expect(document.querySelectorAll('.song')).toHaveLength(20);document.querySelector('.load-more').click();await flush();expect(document.querySelectorAll('.song')).toHaveLength(40);expect(document.querySelector('.song b').textContent).toBe('歌曲1');expect(calls.some(p=>p.includes('after=20'))).toBe(true);expect(document.querySelector('.load-more')).toBe(null)
 })
})
