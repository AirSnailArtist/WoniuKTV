<template>
  <div class="page">
    <header class="top"><button @click="$router.back()">‹</button><strong>分类浏览</strong><span></span></header>
    <!-- 标签切换栏 / Tab switcher -->
    <div class="tabs"><button v-for="item in tabs" :key="item.key" :class="{ on: tab === item.key }" @click="tab = item.key">{{ item.label }}</button></div>
    <div class="browse-body" :class="{withkeys:tab==='artists'}"><main @touchstart.passive="touchStart" @touchend="touchEnd">
      <!-- 歌手模式 / Artists mode -->
      <template v-if="tab === 'artists'">
        <div class="gender-filter"><input ref="artistInput" v-model="artistQuery" placeholder="歌手 / 拼音首字母"/><button v-for="item in scopeOptions" :key="item.value" :class="{on:artistScope===item.value}" @click="artistScope=item.value">{{ item.label }}</button></div>
        <!-- 首字母索引 / Initial letter index -->
        <div class="letters"><button v-for="letter in availableLetters" :key="letter" :class="{ on: activeLetter === letter }" @click="activeLetter = letter">{{ letter }}</button></div>
        <div v-if="loading" class="tip">加载中…</div>
        <div v-else-if="loadError" class="tip">{{ loadError }} <button @click="load">重试</button></div>
        <!-- 歌手列表 / Artist list -->
        <div v-else-if="artists.length" class="artist-list"><router-link v-for="artist in artists" :key="artist.name" :to="{ name:'artist', params:{ name:artist.name },query:{scope:artistScope} }"><span class="avatar">{{ artist.name.slice(0,1) }}<img v-if="artist.coverUrl" :src="artist.coverUrl" :alt="artist.name" loading="lazy" @error="e=>e.target.style.display='none'"/></span><span><strong>{{ artist.name }}</strong><small>{{ artist.songCount }} 首</small></span><em>›</em></router-link></div>
        <div v-else class="tip">暂无符合条件的{{ activeScopeLabel }}歌手</div>
        <div class="artist-paging"><button :aria-disabled="artistPage===0||loading" @click="previousArtists">上一页</button><span>第 {{artistPage+1}} 页</span><button :aria-disabled="!hasMoreArtists||loading" @click="nextArtists">下一页</button></div>
      </template>
      <!-- 语种/分类模式 / Languages & tags mode -->
      <template v-else>
        <div v-if="loading" class="tip">加载中…</div>
        <div v-else-if="loadError" class="tip">{{ loadError }} <button @click="load">重试</button></div>
        <!-- 卡片网格 / Card grid -->
        <div v-else class="cards"><router-link v-for="item in currentItems" :key="item.name" :to="songLink(item)"><span>{{ iconFor(item.name) }}</span><strong>{{ item.name }}</strong><small>{{ item.songCount }} 首</small></router-link></div>
        <div class="artist-paging"><button :disabled="!cardPage" @click="cardPage--">上一页</button><span>{{cardPage+1}} / {{Math.max(1,Math.ceil(allItems.length/6))}}</span><button :disabled="(cardPage+1)*6>=allItems.length" @click="cardPage++">下一页</button></div>
      </template>
    </main><SearchKeyboard v-if="tab==='artists'" v-model="artistQuery" title="查找歌星" @system="artistInput?.focus()"/></div>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 分类浏览页 — 按歌手首字母、语种或分类标签浏览歌曲库。
 *
 * Category browse page — browse the song library by artist initial,
 * language, or category tag.
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {useSwipePages} from '../composables/useSwipePages'
import { useRoute } from 'vue-router'
import api from '../api/client'
import TabBar from '../components/TabBar.vue'
import SearchKeyboard from '../components/SearchKeyboard.vue'
const artistQuery=ref(''),artistPage=ref(0),artistInput=ref(null),hasMoreArtists=ref(false)
let artistCursors=[''],nextArtistCursor='',queryTimer


const cardPage=ref(0)
const {touchStart,touchEnd}=useSwipePages(()=>{if(tab.value==='artists')previousArtists();else if(cardPage.value)cardPage.value--},()=>{if(tab.value==='artists')nextArtists();else if((cardPage.value+1)*6<allItems.value.length)cardPage.value++})
const route = useRoute()
/** 顶部标签页：歌手 / 语种 / 分类。Top tabs: artists / languages / tags. */
const tabs = [{ key:'artists',label:'歌手' },{key:'languages',label:'语种'},{key:'tags',label:'曲库分类'}]
const tab = ref(route.query.tab || 'artists'), artists = ref([]), languages = ref([]), tags = ref([]), activeLetter = ref('全部'), loading = ref(true)
const artistScope = ref('chinese'), loadError = ref('')
const scopeOptions = [{value:'chinese',label:'华语'},{value:'all',label:'全部'},{value:'foreign',label:'外语'}]
let loadRequest = 0
function resetArtists(){artistPage.value=0;artistCursors=[''];nextArtistCursor='';hasMoreArtists.value=false}
function scheduleArtists(delay=0){clearTimeout(queryTimer);loadRequest++;loading.value=true;resetArtists();queryTimer=setTimeout(()=>{if(tab.value==='artists')load()},delay)}
function previousArtists(){if(loading.value||!artistPage.value)return;artistPage.value--;load()}
function nextArtists(){if(loading.value||!hasMoreArtists.value)return;artistCursors[artistPage.value+1]=nextArtistCursor;artistPage.value++;load()}
watch(artistQuery,()=>scheduleArtists(180))
watch(activeLetter,()=>scheduleArtists())
onMounted(()=>{load();window.addEventListener('artist-index-ready',indexReady)})
onUnmounted(()=>{clearTimeout(queryTimer);loadRequest++;window.removeEventListener('artist-index-ready',indexReady)})
function indexReady(){if(tab.value==='artists')load()}
watch(tab,()=>{clearTimeout(queryTimer);load()})
watch(()=>route.query.tab,value=>{tab.value=value||'artists'})
watch(artistScope, () => {
  activeLetter.value='全部';artistQuery.value='';artists.value=[];scheduleArtists()
})

/**
 * 歌手名单按当前范围重新请求，语种和分类按需缓存。
 *
 * Reload artists for the selected scope; cache languages and tags as needed.
 */
async function load() {
  const requestId = ++loadRequest
  const currentTab = tab.value
  const currentScope = artistScope.value
  loading.value = true
  loadError.value = ''
  try {
    if (currentTab === 'artists') {
      const result = await api.browseArtists({scope:currentScope,after:artistCursors[artistPage.value]||'',keyword:artistQuery.value,initial:activeLetter.value==='全部'?'':activeLetter.value})
      if (requestId === loadRequest) {
        artists.value=result.items;hasMoreArtists.value=result.hasMore;nextArtistCursor=result.after||''
        if(result.indexing)loadError.value='歌星搜索索引尚未整理完，稍等后重试；重启可继续整理'
      }
    }
    if (currentTab === 'languages' && !languages.value.length) {
      const result = await api.browseLanguages()
      if (requestId === loadRequest) languages.value = result
    }
    if (currentTab === 'tags' && !tags.value.length) {
      const result = await api.browseTags()
      if (requestId === loadRequest) tags.value = result
    }
  } catch (error) {
    if (requestId === loadRequest) loadError.value = error.message || '曲库加载失败'
  } finally { if (requestId === loadRequest) loading.value = false }
}

/** 可选首字母列表，热门置顶。Available initials, with "热门" pinned first. */
const activeScopeLabel = computed(() => artistScope.value === 'all' ? '' : scopeOptions.find(item => item.value === artistScope.value)?.label || '')
const availableLetters = ['全部',...'ABCDEFGHIJKLMNOPQRSTUVWXYZ','#']

/** 当前标签页对应的数据列表。Data list for the current tab. */
const allItems = computed(() => {
  const items = tab.value === 'languages' ? languages.value : tags.value
  const priority = tab.value === 'languages' ? ['国语','粤语','闽南语','客家语','英语'] : ['华语歌曲','英文歌曲','其他外语']
  const rank = name => { const index = priority.indexOf(name); return index < 0 ? priority.length : index }
  return [...items].sort((left, right) => rank(left.name) - rank(right.name))
})

const currentItems=computed(()=>allItems.value.slice(cardPage.value*6,cardPage.value*6+6))
watch(tab,()=>cardPage.value=0)

/**
 * 根据语种/标签生成歌曲列表路由链接。
 *
 * Builds a route link to the song list filtered by language or tag.
 * @param {{ name: string }} item
 * @returns {{ name: string, params: object, query: object }}
 */
function songLink(item) { return { name:'artist', params:{ name:'all' }, query:{ mode:tab.value === 'languages' ? 'language' : 'tag', value:item.name } } }

/**
 * 根据类别名称返回对应图标。
 *
 * Returns an emoji icon for the given category name.
 * @param {string} name
 * @returns {string}
 */
function iconFor(name) { if (/国语|粤语|英语|日语|韩语/.test(name)) return '🌏'; if (/儿歌|儿童/.test(name)) return '🧸'; if (/摇滚/.test(name)) return '🎸'; if (/情歌/.test(name)) return '💞'; return '🎶' }
</script>

<style scoped>
.page{height:100%;min-height:0;display:flex;flex-direction:column}.top,.tabs{display:none}.browse-body{flex:1;min-height:0;display:flex}.browse-body.withkeys{display:grid;grid-template-columns:minmax(0,1fr) 256px;gap:18px}.browse-body main{min-width:0;min-height:0;display:flex;flex-direction:column;height:100%;overflow:hidden;touch-action:pan-y}.gender-filter,.letters{display:flex;align-items:center;gap:5px;flex:none;padding:3px 3px 8px;overflow-x:auto}.gender-filter input{min-width:0;flex:1;background:var(--recess);border:1px solid var(--glass-border);border-radius:8px;color:var(--text);font-size:12px;padding:7px 11px;height:32px}.gender-filter button,.letters button{flex:none;border:1px solid var(--glass-border);background:var(--panel);color:var(--dim2);border-radius:7px;padding:6px 10px;font-size:10px;min-height:29px}.gender-filter button.on,.letters button.on{color:var(--gold);border-color:var(--accent-line);background:var(--gold-glow)}.letters{gap:4px;padding-bottom:7px;scrollbar-width:none}.letters::-webkit-scrollbar{display:none}.letters button{padding:4px 6px;min-width:25px;min-height:25px;font-size:10px}.artist-list{flex:1;min-height:0;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));grid-template-rows:repeat(2,minmax(0,1fr));gap:9px;padding:3px}.artist-list a{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:7px;padding:8px 5px;border:1px solid var(--glass-border);background:var(--tile);border-radius:12px;box-shadow:inset 0 1px 0 var(--highlight);min-height:0}.artist-list .avatar{position:relative;overflow:hidden;width:41px;height:41px;display:grid;place-items:center;border-radius:50%;background:var(--gold-glow);border:1px solid var(--accent-line);color:var(--gold);font-size:19px;font-weight:500;flex:none}.avatar img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}.artist-list a>span:nth-child(2){display:flex;flex-direction:column;align-items:center;gap:4px;min-width:0;max-width:100%}.artist-list strong{font-size:12px;font-weight:600;white-space:nowrap;text-overflow:ellipsis;overflow:hidden;max-width:100%}.artist-list small{font-size:9px;color:var(--dim2)}.artist-list em{display:none}.artist-paging{flex:none;display:flex;align-items:center;justify-content:center;gap:18px;margin-top:8px;padding:3px;font-size:10px;color:var(--dim2)}.artist-paging button{padding:5px 15px;min-height:28px;background:var(--panel2);border:1px solid var(--glass-border);border-radius:8px;color:var(--dim);font-size:10px}.artist-paging button[aria-disabled=true]{opacity:.4}.cards{flex:1;min-height:0;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));grid-template-rows:repeat(2,minmax(0,1fr));gap:12px;padding:3px}.cards a{min-height:0;display:flex;flex-direction:column;justify-content:center;gap:9px;padding:18px;border:1px solid var(--glass-border);border-radius:13px;background:var(--tile);color:var(--text)}.cards a>span{font-size:24px}.cards strong{font-size:16px;font-weight:600}.cards small{font-size:11px;color:var(--dim2)}.tip{flex:1;display:grid;place-content:center;color:var(--dim2);font-size:13px}.tip button{border:1px solid var(--glass-border);background:var(--panel2);border-radius:8px;min-height:32px;margin-top:10px}.browse-body .search-keyboard{margin-top:3px}
</style>
