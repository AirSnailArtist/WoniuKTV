<template>
  <div class="page search-layout">
    <!-- 搜索框 / Search bar -->
    <div class="sec sbar">
      <button class="back" aria-label="返回" @click="$router.back()"><ChevronLeft :size="24" /></button>
      <div class="search grow">
        <Search :size="17" />
        <input ref="inp" v-model="kw" placeholder="歌名 / 歌手 / 拼音首字母" @input="onInput" />
        <button v-if="kw" class="clear" aria-label="清空" @click="clear"><X :size="16" /></button>
      </div>
    </div>

    <div class="sec filters" role="group" aria-label="歌曲版本筛选">
      <button v-for="filter in filters" :key="filter.value" class="filter"
              :class="{ on: activeFilter === filter.value }"
              :aria-pressed="activeFilter === filter.value"
              @click="selectFilter(filter.value)">{{ filter.label }}</button>
    </div>

    <div class="results"><PagedCarousel v-if="submitted" :loader="fetchSongs" :query-key="submitted" :page-size="4" :columns="1"><template #default="{items}"><SongRow v-for="song in items" :key="song.id" :song="song" :keyword="submitted" :ordered="orderedIds.has(song.id)" @order="order"/></template></PagedCarousel><div v-else class="tip">输入歌名、歌手或首字母</div></div>
    <SearchKeyboard :model-value="kw" @update:model-value="value=>{kw=value;onInput()}" @submit="doSearch" @system="inp?.focus()"/>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * SearchView - 歌曲搜索页面
 *
 * 支持按歌名、歌手或拼音首字母搜索歌曲。包含 300ms 输入防抖、
 * 搜索结果展示、点歌加入队列以及心愿歌曲提交功能。
 *
 * SearchView - Song search page.
 *
 * Supports searching songs by title, artist or pinyin initials.
 * Features 300ms input debounce, search result display, song queuing,
 * and wish-song submission.
 */
import { ref, onMounted, reactive, computed } from 'vue'
import PagedCarousel from '../components/PagedCarousel.vue'
import {createPageCache} from '../composables/pageCache'
import {useSwipePages} from '../composables/useSwipePages'
import api, { makeControls } from '../api/client'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import TabBar from '../components/TabBar.vue'
import SongRow from '../components/SongRow.vue'
import SearchKeyboard from '../components/SearchKeyboard.vue'
import { ChevronLeft, Search, X } from 'lucide-vue-next'

const user = useUserStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)

/** @type {import('vue').Ref<string>} 当前搜索关键词 / Current search keyword */
const kw = ref(''),submitted=ref('')
/** @type {import('vue').Ref<Array>} 搜索结果列表 / Search result list */
const results = ref([]),page=ref(0)
const pageCache=createPageCache(),turning=ref(false)
const hasMore=ref(false),visibleResults=computed(()=>results.value)
async function previous(){if(page.value&&!turning.value){page.value--;await doSearch()}}async function next(){if(hasMore.value&&!turning.value){page.value++;await doSearch()}}
const {touchStart,touchEnd}=useSwipePages(previous,next)
/** @type {import('vue').Ref<boolean>} 搜索加载状态 / Search loading flag */
const loading = ref(false)
const activeFilter = ref('')
const filters = [
  { label: '全部', value: '' },
]
/** 已点歌 ID 集合，用于高亮标记 / Ordered song ID set, for highlight marking */
const orderedIds = reactive(new Set())
const inp = ref(null)
/** @type {number|null} 防抖定时器引用 / Debounce timer reference */
let debounce = null
let searchSequence = 0

// Focus is explicit so the large on-screen keypad stays available on TV.

/**
 * 输入事件处理，300ms 防抖触发搜索（详设 H5-03）
 *
 * Input event handler with 300ms debounce before search (spec H5-03).
 */
function onInput(){clearTimeout(debounce);debounce=setTimeout(doSearch,300)}
function doSearch(){submitted.value=kw.value.trim()}
async function fetchSongs(index){return api.searchSongs(submitted.value,'',index)}
function clear(){kw.value='';submitted.value='';inp.value?.focus()}
function selectFilter(){}

/**
 * 将歌曲加入点歌队列，并标记为已点。
 *
 * Queue a song for playback and mark it as ordered.
 *
 * @param {Object} song - 歌曲对象，需包含 id 属性 / Song object with an id property
 */
async function order(song) {
  try {
    await controls.order(song.id)
    orderedIds.add(song.id)
    toast('已加入队列')
  } catch (e) {
    toast(e.code === 'SONG_IN_QUEUE' ? (e.message || '已在队列中') : (e.message || '点歌失败'))
  }
}

/**
 * 提交心愿歌曲，通知管理端补充曲库。
 *
 * Submit a wish song request to notify admin to add the song.
 */
async function addWish() {
  try {
    await api.addWish(kw.value.trim(), user.clientToken)
    toast('已记下《' + kw.value.trim() + '》，稍后补充到曲库')
  } catch (e) {
    toast(e.message || '提交失败')
  }
}

/**
 * 将毫秒时长格式化为 m:ss 字符串。
 *
 * Format duration in milliseconds to m:ss string.
 *
 * @param {number} ms - 毫秒时长 / Duration in milliseconds
 * @returns {string} 格式化后的时长，如 "3:45" / Formatted duration, e.g. "3:45"
 */
function fmtDur(ms) {
  if (!ms) return ''
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; display: flex; flex-direction: column; }
.sec { padding: 0 16px; }
.sbar { display:flex;align-items:center;gap:8px;padding-top:10px; }
.back { width:30px;height:30px;display:grid;place-items:center;color:var(--dim);padding:0; }
.search {
  height:46px;background:var(--panel);border:1px solid rgba(255,198,75,.28);border-radius:8px;
  padding:0 12px;display:flex;align-items:center;gap:8px;color:var(--gold);
}
.search input { flex: 1; background: none; border: none; outline: none; color: var(--text); font-size: 14px; }
.clear { display:grid;place-items:center;color:var(--dim2);padding:4px; }
.filters { display:flex;gap:7px;padding-top:10px;overflow-x:auto;scrollbar-width:none; }.filters::-webkit-scrollbar { display:none; }.filter { flex:none;padding:6px 10px;border:1px solid var(--line);border-radius:999px;color:var(--dim);font-size:10px;line-height:1.2; }.filter.on { border-color:var(--coral);color:var(--coral);background:rgba(255,107,97,.08); }
.results { margin-top:6px;overflow-y:auto; }
.cnt { display:flex;justify-content:space-between;padding:5px 0 8px;color:var(--dim2);font-size:10px; }.cnt b { color:var(--text);font-size:12px; }
.tip { color: var(--dim2); font-size: 13px; padding: 30px 0; text-align: center; }
.empty { text-align: center; padding: 40px 0; }
.e-title { color: var(--dim); margin-bottom: 16px; }
</style>

<style scoped>.search-layout{display:grid;grid-template-columns:minmax(0,1fr) 305px;gap:16px}.search-layout .sbar{grid-column:1;padding:0;margin-bottom:0}.search-layout .filters{display:none}.search-layout .results{grid-column:1;padding:0}.search-layout .search-keyboard{grid-column:2;grid-row:1 / span 3}.search-layout .search{background:var(--panel);border-color:var(--glass-border)}.search-layout input{font-size:16px;min-height:42px;color:var(--text)}.search-layout .cnt{font-size:13px;margin:10px 0}.search-layout .tip{font-size:16px}</style>
<style scoped>.search-layout{height:100%;grid-template-rows:44px minmax(0,1fr);overflow:hidden}.search-layout .sbar{grid-row:1}.search-layout .results{grid-row:2;overflow:auto;min-height:0}.search-layout .search-keyboard{grid-row:1 / span 2;position:static}.search-layout .results .songrow{min-height:61px}</style>

<style scoped>.search-layout{grid-template-rows:44px minmax(0,1fr) 38px;gap:9px 16px}.search-layout .results{overflow:hidden;display:flex;flex-direction:column;touch-action:pan-y}.search-layout .cnt{flex:none;margin:0 0 3px;font-size:11px}.search-layout :deep(.songrow){min-height:0!important;flex:1;padding-top:4px;padding-bottom:4px}.search-layout .search-keyboard{grid-row:1/span 3}.search-pager{grid-column:1;grid-row:3;display:flex;justify-content:center;align-items:center;gap:22px;font-size:12px}.search-pager button{padding:7px 20px;background:var(--panel2);color:var(--gold);border-radius:9px;min-height:34px}</style>

<style scoped>.search-layout{grid-template-rows:44px minmax(0,1fr)}.search-layout .results{grid-row:2;display:block;min-height:0}.search-layout .search-keyboard{grid-row:1/span 2}</style>
