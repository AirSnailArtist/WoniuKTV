// Spatial focus navigation for Android DPAD, keyboards and TV remotes.
const selector='a[href],button:not(:disabled),input:not(:disabled),[tabindex="0"]'
const visible=element=>{if(!element.getClientRects().length||getComputedStyle(element).visibility==='hidden')return false;const slide=element.closest('.swiper-slide');return !slide||slide.classList.contains('swiper-slide-visible')}
window.addEventListener('keydown',event=>{
  const current=document.activeElement
  const editing=current?.matches('input,textarea')
  if(editing&&['ArrowLeft','ArrowRight','Enter',' '].includes(event.key))return
  if(['Enter',' '].includes(event.key)&&current?.matches(selector)){
    event.preventDefault();current.click();return
  }
  if(!['ArrowUp','ArrowDown','ArrowLeft','ArrowRight'].includes(event.key))return
  const modal=document.querySelector('[role="dialog"],[aria-modal="true"]')
  const candidates=[...(modal||document).querySelectorAll(selector)].filter(visible)
  if(!candidates.length)return
  event.preventDefault()
  if(!candidates.includes(current)){candidates[0].focus();return}
  const rect=current.getBoundingClientRect(),cx=rect.left+rect.width/2,cy=rect.top+rect.height/2
  let best=null,score=Infinity,bestAligned=false
  for(const item of candidates){
    if(item===current)continue
    const r=item.getBoundingClientRect(),dx=r.left+r.width/2-cx,dy=r.top+r.height/2-cy
    const horizontal=['ArrowLeft','ArrowRight'].includes(event.key)
    const primary=horizontal?dx:dy
    if((['ArrowRight','ArrowDown'].includes(event.key)?primary:-primary)<3)continue
    // Wide song cards and narrow paging buttons belong to the same column
    // when their bounds overlap, even if their centers are far apart.
    const secondaryGap=horizontal
      ? Math.max(0,rect.top-(r.top+r.height),r.top-(rect.top+rect.height))
      : Math.max(0,rect.left-(r.left+r.width),r.left-(rect.left+rect.width))
    const value=Math.abs(primary)+secondaryGap*3
    const aligned=secondaryGap===0
    if(!best||(aligned&&!bestAligned)||(aligned===bestAligned&&value<score)){score=value;best=item;bestAligned=aligned}
  }
  if(best){best.focus({preventScroll:true});best.scrollIntoView({block:'nearest',inline:'nearest'})}
})
window.addEventListener('focusin',()=>document.documentElement.classList.add('remote-focus'))
