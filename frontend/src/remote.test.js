import {beforeEach,describe,expect,it,vi} from 'vitest'
import './remote'
function button(id,x,y,width=50,height=40){
  const element=document.createElement('button');element.id=id
  element.getClientRects=()=>[{}]
  element.getBoundingClientRect=()=>({left:x,top:y,width,height})
  element.scrollIntoView=vi.fn();document.body.append(element);return element
}
function key(value){window.dispatchEvent(new KeyboardEvent('keydown',{key:value,cancelable:true}))}
describe('TV remote navigation',()=>{
  beforeEach(()=>{document.body.innerHTML=''})
  it('moves between navigation and content and confirms the focused action',()=>{
    const nav=button('nav',0,0),next=button('next',0,60),content=button('content',100,60)
    const clicked=vi.fn();content.addEventListener('click',clicked)
    nav.focus();key('ArrowDown');expect(document.activeElement).toBe(next)
    key('ArrowRight');expect(document.activeElement).toBe(content)
    key('Enter');expect(clicked).toHaveBeenCalledOnce()
  })
  it('moves up from paging into the overlapping song column before the top navigation',()=>{
    button('language',440,77,70,40)
    const song=button('song',491,326,441,40),paging=button('next-page',470,377,85,34)
    paging.focus();key('ArrowUp');expect(document.activeElement).toBe(song)
  })
  it('selects the same-row right song before a closer diagonal paging button',()=>{
    const left=button('left-song',28,326,441,40),right=button('right-song',491,326,441,40)
    button('next-page',470,377,85,34)
    left.focus();key('ArrowRight');expect(document.activeElement).toBe(right)
    key('ArrowLeft');expect(document.activeElement).toBe(left)
  })
  it('keeps focus inside a modal when navigating',()=>{
    const outside=button('outside',0,0),dialog=document.createElement('section');dialog.setAttribute('role','dialog');document.body.append(dialog)
    const confirm=button('confirm',100,0);dialog.append(confirm)
    outside.focus();key('ArrowRight');expect(document.activeElement).toBe(confirm)
    key('ArrowLeft');expect(document.activeElement).toBe(confirm)
  })
})
