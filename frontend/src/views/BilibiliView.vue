<template>
 <section class="bili-page">
  <header class="bili-heading"><button v-if="folder" class="bili-back" @click="loadFolders">‹ 收藏夹</button><div><h1>{{folder?folderName:tab==='favorites'?'B站收藏夹':'B站点歌'}}</h1></div><span v-if="busy">加载中…</span><span>{{account.loggedIn ? account.name || '已登录' : '未登录B站'}}</span><button v-if="account.loggedIn" @click="logout">退出B站</button><button v-else @click="openLogin">扫码登录</button></header>
  <div class="bili-toolbar"><button :class="{on:tab==='search'}" @click="switchTab('search')">搜索歌曲</button><button :class="{on:tab==='favorites'}" @click="switchTab('favorites')">用户收藏夹</button>
   <form v-if="tab==='search'" @submit.prevent="search"><input ref="input" v-model="keyword" maxlength="80" placeholder="歌名、歌手、MV，或BV号" aria-label="搜索B站点歌"/><button :disabled="busy">搜索</button><button type="button" @click="keyboard=!keyboard">键盘</button></form>
   <form v-else @submit.prevent="loadFolders"><input v-model="uid" maxlength="500" placeholder="用户UID / 收藏夹链接，留空查看我的" aria-label="收藏夹用户UID或分享链接"/><button :disabled="busy">载入收藏夹</button></form>
  </div>
  <div v-if="keyboard&&tab==='search'" class="bili-keyboard"><SearchKeyboard v-model="keyword" @submit="search" @system="input?.focus()"/><button @click="keyboard=false">收起键盘</button></div>

  <p v-if="matchTitle" class="bili-match">正在为《{{matchTitle}}》选择视频版本，点选后保存关联，下次可直接点播。</p>
  <p v-if="error" class="bili-error" role="alert">{{error}} <button @click="loadPage(page)">重试</button></p>
  <div ref="resultsRoot" class="bili-results" :aria-busy="busy">
   <p v-if="!items.length&&!busy&&!error" class="bili-empty">{{tab==='search'?'输入歌名或歌手开始搜索，也可以直接输入BV号播放。':folder?'这个收藏夹还没有视频，可以先搜索歌曲点播。':'登录后查看自己的收藏夹，或输入其他用户UID浏览公开收藏夹。'}}</p>
   <div v-for="item in items" :key="folderMode?item.id:item.aid||item.bvid" class="bili-entry"><button class="bili-card" :disabled="!folderMode&&(!item.available||ordering===item.bvid)" @click="folderMode?openFolder(item):order(item)"><span v-if="folderMode" class="bili-card-icon">收藏夹</span><span class="bili-card-copy"><b>{{item.title}}</b><small>{{folderMode?item.count+' 个视频':'UP主 · '+(item.owner||'未知')+' · '+formatVideoViews(item.views)}}</small></span><span class="bili-card-action">{{folderMode?'打开 ›':!item.available?'已失效':ordering===item.bvid?'正在点选':ordered.has(item.bvid)?'已点 · 收藏':'+ 点播'}}</span></button><button v-if="canManage&&!folderMode&&item.aid" class="bili-remove" :disabled="!!removing" :aria-label="'取消收藏 '+item.title" @click="removeFavorite(item)">{{removing===item.aid?'正在取消':'取消收藏'}}</button></div>
  </div>
  <footer class="bili-pages"><button :disabled="busy||page<=1" @click="loadPage(page-1)">上一页</button><span>第 {{page}} 页 · 点播自动收藏到「窝牛KTV」</span><button :disabled="busy||!hasMore" @click="loadPage(page+1)">下一页</button></footer>
  <div v-if="loginOpen" class="fit-mask" @click.self="closeLogin" @keydown.esc.stop="closeLogin"><section class="fit-dialog phone-dialog" role="dialog" aria-modal="true" aria-label="B站扫码登录"><h2>使用哔哩哔哩 App 扫码登录</h2><div class="phone-content"><div class="phone-qr"><img v-if="loginImage" :src="loginImage" alt="B站登录二维码"/><span v-else>正在获取二维码…</span></div><div><p role="status">{{loginMessage}}</p><p class="phone-hint">登录信息仅保存在当前设备。</p><div class="fit-actions"><button @click="openLogin">刷新二维码</button><button ref="loginClose" aria-label="关闭" @click="closeLogin">关闭</button></div></div></div></section></div>
 </section>
</template>
<script setup>
import {formatVideoViews} from '../utils/videoViews'
import {computed,nextTick,onMounted,onUnmounted,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {biliRequest,folderReference} from '../api/bilibili'
import {usePlayerStore} from '../stores/player'
import {useToast} from '../composables/useToast'
import SearchKeyboard from '../components/SearchKeyboard.vue'
const player=usePlayerStore(),{toast}=useToast(),keyword=ref(''),uid=ref(''),tab=ref('search'),submitted=ref(''),folder=ref(''),folderName=ref(''),items=ref([]),page=ref(1),hasMore=ref(false),busy=ref(false),error=ref(''),ordering=ref(''),keyboard=ref(false),input=ref(null)
const canManage=ref(false),removing=ref('')
const account=ref({loggedIn:false}),loginOpen=ref(false),loginImage=ref(''),loginMessage=ref(''),loginClose=ref(null),resultsRoot=ref(null),folderMode=computed(()=>tab.value==='favorites'&&!folder.value)
const ordered=computed(()=>new Set([...(player.queue||[]),player.nowPlaying].filter(Boolean).map(q=>q.song?.bvid).filter(Boolean)))
const route=useRoute(),matchTitle=computed(()=>tab.value==='search'&&submitted.value===String(route.query.keyword||'').slice(0,80).trim()?String(route.query.matchTitle||'').slice(0,150):'')
function matchArtists(){try{const list=JSON.parse(route.query.matchArtists||'[]');return Array.isArray(list)?list.filter(x=>typeof x==='string').slice(0,10).map(x=>x.slice(0,80)):[]}catch{return []}}
let epoch=0,loginEpoch=0,pollTimer,previousFocus
function switchTab(next){epoch++;tab.value=next;items.value=[];folder.value='';submitted.value='';error.value='';page.value=1;hasMore.value=false;busy.value=false;keyboard.value=false;if(next==='favorites'&&account.value.loggedIn)loadFolders()}
function search(){submitted.value=keyword.value.trim();keyboard.value=false;if(!submitted.value)return;folder.value='';loadPage(1)}
function loadFolders(){try{folder.value=folderReference(uid.value);if(folder.value)folderName.value='分享的收藏夹';loadPage(1)}catch(e){error.value=e.message}}
function openFolder(item){folder.value=item.id;folderName.value=item.title;loadPage(1)}
async function loadPage(target){
 const token=++epoch;busy.value=true;error.value='';canManage.value=false
 try{let result
  if(tab.value==='search'&&/^BV[0-9A-Za-z]{10}$/.test(submitted.value))result={items:[{bvid:submitted.value,title:submitted.value,owner:'按BV号点播',available:true}],hasMore:false}
  else if(tab.value==='search')result=await biliRequest('search?'+new URLSearchParams({keyword:submitted.value,page:target}))
  else if(folder.value)result=await biliRequest('favorites?'+new URLSearchParams({folder:folder.value,page:target}))
  else result=await biliRequest('folders?'+new URLSearchParams({uid:uid.value.trim(),page:target}))
  if(token!==epoch)return;items.value=result.items;canManage.value=result.canManage===true;hasMore.value=result.hasMore;page.value=target;if(!items.value.length&&tab.value==='search')error.value='没有找到可显示的内容';await nextTick();if(resultsRoot.value)resultsRoot.value.scrollTop=0
 }catch(e){if(token===epoch){items.value=[];hasMore.value=false;error.value=e.message}}finally{if(token===epoch)busy.value=false}
}
async function removeFavorite(item){
 if(removing.value||!canManage.value)return
 const currentFolder=folder.value,token=epoch;removing.value=item.aid
 try{await biliRequest('favorite/remove','POST',{folder:currentFolder,aid:item.aid});toast('已取消这首歌的B站收藏');if(token===epoch&&folder.value===currentFolder){await loadPage(items.value.length===1&&page.value>1?page.value-1:page.value);await nextTick();(resultsRoot.value?.querySelector('.bili-card')||document.querySelector('.bili-back'))?.focus()}}catch(e){toast(e.message+'；请刷新收藏夹确认结果')}finally{removing.value=''}
}
async function order(item){if(ordering.value||!item.available)return;if(ordered.value.has(item.bvid)){ordering.value=item.bvid;try{await biliRequest('collect','POST',{bvid:item.bvid});toast('正在补收藏，完成后会提示')}catch(e){toast(e.message)}finally{ordering.value=''};return};ordering.value=item.bvid;try{await biliRequest('order','POST',{bvid:item.bvid,...(matchTitle.value?{matchTitle:matchTitle.value,matchArtists:matchArtists()}:{})});window.dispatchEvent(new Event('woniu-change'));toast('已加入播放队列')}catch(e){toast(e.message)}finally{ordering.value=''}}
async function status(){const token=loginEpoch;try{const result=await biliRequest('status');if(token===loginEpoch)account.value=result}catch(e){if(token===loginEpoch)error.value=e.message}}
async function logout(){try{await biliRequest('logout','POST');loginEpoch++;clearTimeout(pollTimer);account.value={loggedIn:false};if(tab.value==='favorites'){epoch++;items.value=[];folder.value='';hasMore.value=false};toast('已退出B站')}catch(e){toast(e.message)}}
function closeLogin(){loginOpen.value=false;loginEpoch++;clearTimeout(pollTimer);nextTick(()=>previousFocus?.focus?.())}
async function openLogin(){if(!loginOpen.value)previousFocus=document.activeElement;loginOpen.value=true;loginImage.value='';loginMessage.value='正在获取二维码…';clearTimeout(pollTimer);const token=++loginEpoch;nextTick(()=>loginClose.value?.focus());try{const result=await biliRequest('login/start','POST');if(token!==loginEpoch)return;loginImage.value=result.image;loginMessage.value='请扫码，并在手机上确认登录';pollTimer=setTimeout(()=>poll(token,0),2200)}catch(e){if(token===loginEpoch)loginMessage.value=e.message}}
async function poll(token,count){
 if(token!==loginEpoch||!loginOpen.value)return;if(count>=80){loginMessage.value='二维码已过期，请刷新';return}
 try{const result=await biliRequest('login/poll','POST');if(token!==loginEpoch)return
  if(result.state==='success'){closeLogin();await status();toast('B站登录成功');if(tab.value==='favorites')loadFolders();return}
  if(result.state==='expired'){loginMessage.value='二维码已过期，请刷新';return}
  loginMessage.value=result.state==='scanned'?'已扫码，请在手机上确认':'等待扫码…';pollTimer=setTimeout(()=>poll(token,count+1),2200)
 }catch(e){if(token===loginEpoch)loginMessage.value=e.message}
}
watch(()=>route.query.keyword,value=>{if(value){tab.value='search';keyword.value=String(value).slice(0,80);search()}},{immediate:true})
onMounted(status);onUnmounted(()=>{epoch++;loginEpoch++;clearTimeout(pollTimer)})
</script>
<style scoped>
.bili-entry{display:flex;min-width:0;gap:4px}.bili-entry>.bili-card{flex:1;min-width:0}.bili-page .bili-remove{flex:none;width:68px;padding:6px;font-size:12px;color:var(--dim)}.bili-page{height:100%;display:flex;flex-direction:column;gap:12px;padding:2px 0 8px;min-height:0}.bili-heading{display:flex;align-items:center;gap:12px}.bili-heading>div{flex:1}.bili-heading h1{font-size:25px;margin:0 0 4px}.bili-heading p,.bili-heading>span{opacity:.65;font-size:12px;margin:0}.bili-page button{border:1px solid var(--line,#ffffff25);background:var(--card,#ffffff0b);color:inherit;border-radius:9px;padding:8px 13px;cursor:pointer}.bili-page button:focus-visible{outline:3px solid var(--gold,#efcc77);outline-offset:2px}.bili-page button:disabled{opacity:.45;cursor:default}.bili-toolbar{display:flex;gap:9px;align-items:center}.bili-toolbar .on{color:var(--gold,#efcc77);border-color:var(--gold,#efcc77)}.bili-toolbar form{display:flex;flex:1;gap:8px}.bili-toolbar input{min-width:0;flex:1;background:var(--card,#ffffff0b);border:1px solid var(--line,#ffffff25);border-radius:9px;padding:8px 12px;color:inherit;font-size:15px}.bili-list-heading{display:flex;gap:12px;align-items:center;font-size:13px}.bili-list-heading>span{margin-left:auto}.bili-results{flex:1;min-height:0;overflow:auto;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));align-content:start;gap:10px;padding:3px}.bili-page .bili-card{display:flex;align-items:center;gap:12px;min-height:82px;text-align:left;padding:12px}.bili-card-icon{font-size:12px;color:var(--gold,#efcc77);background:#c89d4720;border-radius:8px;padding:12px 7px}.bili-card-copy{min-width:0;flex:1}.bili-card-copy b{font-size:15px;line-height:1.5;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.bili-card-copy small{display:block;font-size:11px;opacity:.65;margin-top:5px}.bili-card-action{font-size:12px;color:var(--gold,#efcc77);white-space:nowrap}.bili-pages{display:flex;align-items:center;justify-content:center;gap:12px;font-size:12px}.bili-pages small{opacity:.55}.bili-error{color:#efb990;font-size:13px;margin:0}.bili-empty{grid-column:1/-1;text-align:center;opacity:.65;padding:30px}.bili-keyboard{flex-shrink:0}
</style>

<style scoped>.bili-match{margin:0;padding:8px 12px;border-radius:8px;background:#c89d4718;color:var(--gold,#efcc77);font-size:12px}</style>

<style scoped>
.bili-page{gap:8px;padding:0}.bili-heading{min-height:32px;gap:10px}.bili-heading h1{font-size:20px;line-height:1.3;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:56vw;margin:0}.bili-heading>span{font-size:11px}.bili-toolbar{gap:7px}.bili-page .bili-card{min-height:92px;padding:10px 14px;border-radius:12px;gap:10px}.bili-card-copy b{font-size:18px;line-height:1.4}.bili-card-copy small{font-size:12px;margin-top:5px;line-height:1.4}.bili-card-action{font-size:13px}.bili-results{gap:9px;padding:3px 4px}.bili-pages{min-height:32px;flex-shrink:0}.bili-toolbar form input{font-size:14px}.bili-back{flex-shrink:0}
</style>
