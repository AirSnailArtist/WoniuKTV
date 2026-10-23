<template><div ref="root" class="paged-carousel" @keydown="remotePage"><div v-if="!pageRows.length" class="carousel-empty">{{error||'加载中…'}}</div><Swiper v-else :modules="[FreeMode,Virtual]" :slides-per-view="1" :space-between="20" :speed="320" :free-mode="{enabled:true,momentum:false,sticky:false}" :virtual="{addSlidesBefore:1,addSlidesAfter:2}" :watch-slides-progress="true" :threshold="5" :resistance-ratio="0.3" @swiper="setSwiper" @slide-change-transition-end="restoreKeyboardFocus" @active-index-change="changed" @reach-end="approach"><SwiperSlide v-for="(rows,i) in pageRows" :key="queryKey+':'+i" :virtual-index="i"><div class="carousel-cells" :style="{gridTemplateColumns:`repeat(${columns},minmax(0,1fr))`,gridTemplateRows:`repeat(${Math.ceil(pageSize/columns)},minmax(0,1fr))`}"><slot :items="rows" :index="i"/></div></SwiperSlide></Swiper><footer v-if="controls"><button :disabled="!current" @click="previous">上一页</button><span>第 {{current+1}} 页<span v-if="last!==null"> / {{last+1}}</span></span><button :disabled="last!==null&&current>=last" @click="next">下一页</button><small>拖动跟手 · 松手即停</small></footer></div></template>
<script setup>
import {ref,watch,nextTick,onBeforeUnmount} from 'vue'
import {Swiper,SwiperSlide} from 'swiper/vue';import {FreeMode,Virtual} from 'swiper/modules'
import 'swiper/css';import 'swiper/css/free-mode';import 'swiper/css/virtual'
const props=defineProps({items:{type:Array,default:null},loader:Function,queryKey:{type:String,default:''},pageSize:{type:Number,default:8},columns:{type:Number,default:2},controls:{type:Boolean,default:true}})
const root=ref(null);let keyboardFocusIndex=null,keyboardEntryRight=true
const pageRows=ref([]),current=ref(0),last=ref(null),error=ref('');let swiper,epoch=0,wanted=2,working=false,buttonBusy=false
function setSwiper(s){swiper=s;changed(s)}
function changed(s){current.value=s.activeIndex;prefetch(s.activeIndex+2)}
function approach(){prefetch(current.value+2)}
async function prefetch(index){if(!props.loader)return;wanted=Math.max(wanted,index);if(working)return;working=true;const token=epoch
 try{while(token===epoch&&pageRows.value.length<=wanted&&last.value===null){const n=pageRows.value.length,raw=await props.loader(n);if(token!==epoch)return;if(!raw.length&&n>0){last.value=n-1;break}pageRows.value.push(raw.slice(0,props.pageSize));if(raw.length<=props.pageSize)last.value=n;await nextTick();swiper?.update()}}
 catch(e){if(token===epoch)error.value=e.message||'加载失败'}finally{if(token===epoch)working=false}
}
const focusSelector='button:not(:disabled),a[href],input:not(:disabled),[tabindex="0"]'
function restoreKeyboardFocus(){
 if(keyboardFocusIndex===null)return
 const controls=[...(root.value?.querySelector('.swiper-slide-active')?.querySelectorAll(focusSelector)||[])].filter(x=>x.getClientRects().length)
 let target=controls[Math.min(keyboardFocusIndex,controls.length-1)]
 if(target){
  const rect=target.getBoundingClientRect()
  const row=controls.filter(item=>{const r=item.getBoundingClientRect();return r.top<rect.top+rect.height&&r.top+r.height>rect.top})
  row.sort((a,b)=>a.getBoundingClientRect().left-b.getBoundingClientRect().left)
  target=keyboardEntryRight?row[0]:row[row.length-1]
 }
 keyboardFocusIndex=null
 target?.focus({preventScroll:true})
}
async function remotePage(event){
 if(!props.controls)return // Home panels use spatial navigation across both columns.
 if(event.target.matches('input,textarea'))return
 if(!['ArrowLeft','ArrowRight'].includes(event.key))return
 const slide=event.target.closest('.swiper-slide')
 if(slide){
  const rect=event.target.getBoundingClientRect(),right=event.key==='ArrowRight'
  const neighbor=[...slide.querySelectorAll(focusSelector)].some(item=>{
   if(item===event.target||!item.getClientRects().length)return false
   const r=item.getBoundingClientRect()
   const dx=r.left+r.width/2-(rect.left+rect.width/2)
   return (right?dx:-dx)>=3&&r.top<rect.top+rect.height&&r.top+r.height>rect.top
  })
  if(neighbor)return // Spatial navigation selects the other column first.
 }
 event.preventDefault();event.stopPropagation()
 if(event.repeat||buttonBusy||swiper?.animating)return
 const controls=[...(event.target.closest('.swiper-slide')?.querySelectorAll(focusSelector)||[])]
 keyboardFocusIndex=Math.max(0,controls.indexOf(event.target));keyboardEntryRight=event.key==='ArrowRight'
 if(event.key==='ArrowRight')await next();else previous()
 await nextTick()
 if(!swiper?.animating)restoreKeyboardFocus()
}
function previous(){swiper?.slidePrev(320)}async function next(){if(buttonBusy)return;buttonBusy=true;try{await prefetch(current.value+1);if(current.value+1<pageRows.value.length)swiper?.slideNext(320)}finally{buttonBusy=false}}
async function reset(nextState,previousState){
 const previousFocus=document.activeElement,slide=previousFocus?.closest('.swiper-slide-active')
 const focusIndex=slide&&root.value?.contains(previousFocus)?[...slide.querySelectorAll(focusSelector)].indexOf(previousFocus):-1
 const oldPage=current.value,sameQuery=nextState?.[0]===previousState?.[0]
 keyboardFocusIndex=null;const token=++epoch;working=false;wanted=2;current.value=0;error.value='';pageRows.value=[];last.value=null
 if(props.items){
  for(let i=0;i<props.items.length;i+=props.pageSize)pageRows.value.push(props.items.slice(i,i+props.pageSize))
  if(!pageRows.value.length)pageRows.value.push([])
  last.value=pageRows.value.length-1
 }else await prefetch(2)
 await nextTick()
 if(token!==epoch)return
 swiper?.update();swiper?.slideTo(sameQuery&&focusIndex>=0?Math.min(oldPage,pageRows.value.length-1):0,0)
 if(focusIndex<0)return
 requestAnimationFrame(()=>{
  if(token!==epoch)return
  const active=document.activeElement
  // Do not take focus back if the user moved to another control while rendering.
  if(active!==previousFocus&&active!==document.body&&active!==document.documentElement)return
  if(previousFocus.isConnected&&previousFocus.closest('.swiper-slide-active'))return
  const controls=[...(root.value?.querySelector('.swiper-slide-active')?.querySelectorAll(focusSelector)||[])]
  ;(controls[Math.min(focusIndex,controls.length-1)]||root.value?.querySelector('footer button:not(:disabled)'))?.focus({preventScroll:true})
 })
}
// Polling can return a new array with identical contents. Keep the user's
// partially dragged position unless the query or actual records changed.
watch([()=>props.queryKey,()=>props.items===null?null:JSON.stringify(props.items)],reset,{immediate:true});onBeforeUnmount(()=>epoch++)
defineExpose({next,previous})
</script>
<style scoped>.paged-carousel{display:flex;flex-direction:column;min-height:0;height:100%;width:100%;overflow:hidden}.swiper{flex:1;min-height:0;width:100%;margin:0}.swiper-slide{height:100%;overflow:hidden}.carousel-cells{height:100%;display:grid;column-gap:22px;align-content:stretch;overflow:hidden}.carousel-cells:empty:after{content:'暂无歌曲';color:var(--dim);font-size:16px;padding:20px}.carousel-empty{flex:1;display:grid;place-items:center;color:var(--dim)}footer{height:41px;flex:none;display:flex;align-items:center;justify-content:center;gap:22px;font-size:12px}footer button{min-height:34px;padding:7px 22px;border-radius:9px;background:var(--panel2);color:var(--gold)}footer small{font-size:10px;color:var(--dim2)}:deep(.songrow){min-height:0!important;height:100%;padding-top:4px;padding-bottom:4px}</style>

<style scoped>
/* Keep focus inside the control so Swiper clipping cannot cut off the ring. */
.paged-carousel :deep(button:focus),.paged-carousel :deep(a:focus),.paged-carousel :deep(input:focus){outline:3px solid var(--focus);outline-offset:-3px}
</style>
