/* Compositor animations: no per-frame JS, full-screen canvas redraw, HTML or advanced scripts. */
window.createWoniuDanmaku=function(root){
 let active=[],seen=new Set(),lanes=Array(6).fill(0),fixed=[0,0],key='',last=-1,width=0,height=0
 function clear(){root.replaceChildren();active=[];seen.clear();lanes.fill(0);fixed=[0,0]}
 return {update(state,data){
  const p=Number(state.position)||0,enabled=state.source==='bilibili'&&state.danmakuEnabled&&state.videoVisible!==false&&!document.hidden&&String(data?.cid)===String(state.cid)
  const next=String(state.cid),w=innerWidth,h=innerHeight
  if(!enabled||next!==key||w!==width||h!==height||last>=0&&Math.abs(p-last)>1800)clear()
  key=next;last=p;width=w;height=h;if(!enabled)return
  active=active.filter(c=>{if(p-c.at>c.life+500){c.node.remove();return false}c.node.style.animationPlayState=state.state==='playing'?'running':'paused';return true})
  const font=Math.max(18,Math.min(28,h/25)),speed=(w+300)/8000
  for(const c of (data?.items||[]).slice(0,120)){
   if(seen.has(c.id)||c.at>p||p-c.at>1000)continue
   seen.add(c.id);if(seen.size>512)seen.delete(seen.values().next().value)
   if(active.length>=12)continue
   const text=String(c.text||'').slice(0,120),textWidth=[...text].reduce((n,x)=>n+(x.charCodeAt(0)>255?1:.64),0)*font+20;let lane=0,life=4000
   if(c.mode===1){lane=lanes.findIndex(until=>until<=c.at);if(lane<0)continue;lanes[lane]=c.at+(textWidth+50)/speed;life=(w+textWidth)/speed}
   else if(c.mode===4||c.mode===5){const slot=c.mode===4?0:1;if(fixed[slot]>c.at)continue;fixed[slot]=c.at+4000}else continue
   const n=document.createElement('span');n.className='woniu-dm';n.textContent=text;n.style.fontSize=font+'px';n.style.color='#'+(Number(c.color)&0xffffff).toString(16).padStart(6,'0');n.style.top=(c.mode===4?h*.68:font*.45+lane*(font+6))+'px';n.style.animationDuration=life+'ms';n.style.animationDelay=-(p-c.at)+'ms';n.style.animationPlayState=state.state==='playing'?'running':'paused'
   if(c.mode===1){n.style.setProperty('--dm-from',w+'px');n.style.setProperty('--dm-to',-textWidth+'px');n.style.animationName='woniu-dm-scroll'}else{n.style.left='50%';n.style.transform='translateX(-50%)';n.style.animationName='woniu-dm-fixed'}
   const item={node:n,at:c.at,life};n.addEventListener('animationend',()=>{n.remove();active=active.filter(x=>x!==item)},{once:true});root.append(n);active.push(item)
  }
 }}
}
