<template>
  <div class="ktv-shell" :class="{'bili-browse':['bilibili','musicPlaylists'].includes($route.name)}">
    <header class="app-header" :class="{'home-header':$route.name==='home'}"><router-link class="brand" :to="{name:'home'}"><span class="brand-icon"><img :src="brandLogo" alt=""/></span><span><b>窝牛</b><small>PRIVATE KTV</small></span></router-link><h1 v-if="$route.name==='home'" class="home-topline">今晚，唱你喜欢的。</h1><span v-else class="library-status"><i/>{{libraryCount?libraryCount.toLocaleString()+' 首私人曲库':'你的家庭点歌台'}}</span><button class="search-entry" @click="$router.push({name:'search'})"><Search :size="20"/>搜索歌名、歌手 <kbd>拼音</kbd></button><button class="header-tool" @click="openFit">画面适配</button><button class="header-tool phone-entry" @click="phone"><QrCode :size="22"/>手机点歌</button><button class="header-tool theme-entry" @click="toggleTheme"><Sun v-if="theme==='dark'" :size="21"/><Moon v-else :size="21"/>{{theme==='dark'?'日间':'夜间'}}</button><button class="header-tool settings-entry" aria-label="曲库与账号设置" @click="manage"><Settings :size="22"/></button></header>
    <nav class="primary-nav"><router-link v-for="item in navigation" :key="item.label" :to="item.to" :class="{selected:item.active($route)}"><component :is="item.icon" :size="18" :stroke-width="1.7"/>{{item.label}}<span v-if="item.queue&&player.queueCount" class="count">{{player.queueCount}}</span></router-link></nav>
    <main class="workspace-content"><router-view/></main>
    <footer class="control-deck">
      <div class="transport-controls"><button class="play-main" @click="control(player.isPlaying?'pause':'play')"><Pause v-if="player.isPlaying" :size="27"/><Play v-else :size="27"/><span>{{player.isPlaying?'暂停':'播放'}}</span></button><button :disabled="!player.nowPlaying" @click="control('audio')"><Disc3 :size="25"/><span>原唱 / 伴奏</span></button><button :disabled="!player.nowPlaying" @click="control('restart')"><RotateCcw :size="25"/><span>重唱</span></button><button :disabled="!player.nowPlaying&&!player.queueCount" @click="control('next')"><SkipForward :size="25"/><span>切歌</span></button></div>
      <button class="now-playing" @click="control('show_player')"><span class="record"><Music2 :size="27"/></span><span><small>{{player.nowPlaying?({playing:'正在播放',paused:'已暂停',loading:'正在连接',error:'播放失败，可重试'}[player.state]||'准备播放'):'今晚的舞台，为你留着'}}</small><b>{{player.nowPlaying?.song?.title||'选一首喜欢的歌'}}</b><em>{{player.nowPlaying?.song?.artist||'点歌后即可在线播放'}}</em></span><Maximize2 :size="19"/></button>
      <div class="deck-actions"><button aria-label="减小音量" @click="control('volume',{delta:-1})"><Volume1 :size="24"/><span>音量 −</span></button><button aria-label="增大音量" @click="control('volume',{delta:1})"><Volume2 :size="24"/><span>音量 ＋</span></button><button @click="$router.push({name:'queue'})" class="queue-action"><ListMusic :size="25"/><span>已点 {{player.queueCount}}</span></button></div>
    </footer>
    <div v-if="phoneOpen" class="fit-mask" @click.self="closePhone" @keydown.esc.stop="closePhone"><section class="fit-dialog phone-dialog" role="dialog" aria-modal="true" aria-label="手机扫码点歌"><h2>手机扫码点歌</h2><div class="phone-content"><div class="phone-qr"><img v-if="phoneImage" :src="phoneImage" alt="手机点歌二维码"/><span v-else>{{phoneBusy?'正在准备二维码…':'二维码暂不可用'}}</span></div><div><p>{{phoneError||'用手机扫码，在手机上选歌。手机和播放设备需要连接同一 Wi-Fi。'}}</p><p class="phone-hint">极托中如扫码后无法打开，请检查局域网和端口转发设置。</p><div class="fit-actions"><button v-if="phoneError" @click="loadPhone">重试</button><button ref="phoneClose" aria-label="关闭" @click="closePhone">关闭</button></div></div></div></section></div>
    <div v-if="fitOpen" class="fit-mask" @click.self="closeFit" @keydown.esc.stop="closeFit"><section class="fit-dialog" role="dialog" aria-modal="true" aria-label="画面适配"><h2>画面适配</h2><p>已根据当前窗口自动调整。若文字或按钮偏小、画面显示不全，可以手动调整。</p><div class="fit-actions"><button ref="fitFirst" @click="changeFit(-0.1)">缩小 −</button><strong>{{Math.round(fitValue*100)}}%</strong><button @click="changeFit(0.1)">放大 ＋</button><button @click="resetFit">自动</button></div><button class="fit-close" aria-label="关闭" @click="closeFit">完成</button></section></div>
  </div><div v-if="queueOpen" class="queue-mask" @click.self="closeQueue" @keydown.esc.stop="closeQueue"><section class="queue-drawer" role="dialog" aria-modal="true" aria-label="已点歌曲"><header><h2>已点歌曲 · {{player.queueCount}} 首待唱</h2><button ref="queueClose" aria-label="关闭" @click="closeQueue">关闭 ×</button></header><p v-if="player.nowPlaying">正在唱 · {{player.nowPlaying.song.title}}</p><main><p v-if="!player.queue.length">还没有待唱歌曲</p><article v-for="(q,i) in player.queue" :key="q.queueId"><span>{{i+1}}</span><div><b>{{q.song.title}}</b><small>{{q.song.artist}}</small></div><button @click="control('top',{queue_id:q.queueId})">置顶</button><button @click="control('cancel',{queue_id:q.queueId})">删除</button></article></main></section></div><ToastHost/><DialogHost/>
</template>
<script setup>
import {onMounted,onUnmounted,ref,nextTick} from 'vue'
import brandLogo from './assets/woniu-logo-v1.png'
import {preference,setViewportPreference} from './viewportFit'
import {House,Music2,Mic2,Heart,ListMusic,Settings,Search,QrCode,Sun,Moon,Play,Pause,Disc3,RotateCcw,SkipForward,Volume1,Volume2,Maximize2,Globe2,Library} from 'lucide-vue-next'
import {usePlayerStore} from './stores/player';import {useUserStore} from './stores/user';import {useFavoritesStore} from './stores/favorites'
import ToastHost from './components/ToastHost.vue';import DialogHost from './components/DialogHost.vue';import api from './api/client';import {useToast} from './composables/useToast'
import {useRouter} from 'vue-router'
const router=useRouter(),queueOpen=ref(false),queueClose=ref(null),fitOpen=ref(false),fitFirst=ref(null),fitValue=ref(preference()),phoneOpen=ref(false),phoneBusy=ref(false),phoneImage=ref(''),phoneError=ref(''),phoneClose=ref(null);let previousFocus,fitPrevious,phonePrevious
function openFit(){fitPrevious=document.activeElement;fitOpen.value=true;nextTick(()=>fitFirst.value?.focus())}
function closeFit(){fitOpen.value=false;nextTick(()=>fitPrevious?.focus?.())}
function changeFit(delta){fitValue.value=setViewportPreference(Math.round((fitValue.value+delta)*10)/10)}
function resetFit(){fitValue.value=setViewportPreference(1)}
function closeQueue(){queueOpen.value=false;previousFocus?.focus?.()}
const removeQueueGuard=router.beforeEach(to=>{if(to.name==='queue'){if(queueOpen.value){closeQueue();return false}previousFocus=document.activeElement;queueOpen.value=true;nextTick(()=>queueClose.value?.focus());return false}})
onUnmounted(removeQueueGuard)
const player=usePlayerStore(),user=useUserStore(),favorites=useFavoritesStore(),{toast}=useToast(),libraryCount=ref(0)
const theme=ref(sessionStorage.getItem('woniu-session-theme')||'light');document.documentElement.dataset.theme=theme.value
function toggleTheme(){theme.value=theme.value==='dark'?'light':'dark';document.documentElement.dataset.theme=theme.value;sessionStorage.setItem('woniu-session-theme',theme.value);window.Woniu?.setTheme?.(theme.value)}
const navigation=[{label:'首页',icon:House,to:{name:'home'},active:r=>r.name==='home'},{label:'榜单',icon:Library,to:{name:'ranking'},active:r=>r.name==='ranking'},{label:'歌名点歌',icon:Music2,to:{name:'search'},active:r=>r.name==='search'||r.name==='artist'},{label:'歌星点歌',icon:Mic2,to:{name:'browse',query:{tab:'artists'}},active:r=>r.name==='browse'&&(!r.query.tab||r.query.tab==='artists')},{label:'语种',icon:Globe2,to:{name:'browse',query:{tab:'languages'}},active:r=>r.name==='browse'&&r.query.tab==='languages'},{label:'分类',icon:Library,to:{name:'browse',query:{tab:'tags'}},active:r=>r.name==='browse'&&r.query.tab==='tags'},{label:'B站点歌',icon:Globe2,to:{name:'bilibili'},active:r=>r.name==='bilibili'},{label:'歌单',icon:Music2,to:{name:'musicPlaylists'},active:r=>r.name==='musicPlaylists'},{label:'收藏',icon:Heart,to:{name:'favorites'},active:r=>r.name==='favorites'},{label:'已点歌曲',icon:ListMusic,to:{name:'queue'},active:r=>r.name==='queue',queue:true}]
async function control(action,params={}){try{await api.control(action,params,user.clientToken)}catch(e){toast(e.message)}}
function manage(){window.Woniu?.openManagement()}
function phone(){phonePrevious=document.activeElement;phoneOpen.value=true;phoneImage.value='';phoneError.value='';nextTick(()=>{phoneClose.value?.focus();loadPhone()})}
function closePhone(){phoneOpen.value=false;nextTick(()=>phonePrevious?.focus?.())}
function loadPhone(){
 if(phoneBusy.value)return;phoneBusy.value=true;phoneError.value='';phoneImage.value=''
 setTimeout(()=>{try{
  if(!window.Woniu?.phonePairing)throw new Error('安装到设备后，手机与设备连接同一 Wi-Fi 才能扫码点歌')
  const result=JSON.parse(window.Woniu.phonePairing());if(!result.ok)throw new Error(result.message||'二维码暂不可用')
  phoneImage.value=result.image
 }catch(e){phoneError.value=e.message||'手机点歌暂不可用'}finally{phoneBusy.value=false}},0)
}
onMounted(()=>{window.addEventListener('woniu-open-phone',phone);window.Woniu?.setTheme?.(theme.value);player.connect();favorites.load(user.clientToken).catch(()=>{});if(window.Woniu){try{libraryCount.value=JSON.parse(window.Woniu.request('/library/status','GET','')).data.songs}catch{}}})
onUnmounted(()=>{window.removeEventListener('woniu-open-phone',phone);player.disconnect()})
</script>
<style src="./styles/stage.css"></style>
