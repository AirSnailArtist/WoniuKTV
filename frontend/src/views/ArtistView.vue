<template>
  <div class="page song-pages">
    <!-- 顶部导航栏 / Header navigation -->
    <header class="head"><button @click="$router.back()">‹</button><div><h1>{{ pageTitle }}</h1><p>{{ songs.length }} 首歌曲</p></div></header>
    <PagedCarousel class="artist-carousel" :loader="fetchSongs" :query-key="$route.fullPath" :page-size="8" :columns="2"><template #default="{items}"><SongRow v-for="song in items" :key="song.id" :song="song" :ordered="orderedIds.has(song.id)" @order="order"/></template></PagedCarousel>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 艺人/分类歌曲浏览页面。
 * 支持按热度、歌名、最新排序，可按艺人、语种、标签、演唱形式等模式筛选。
 *
 * Artist/category song browse page.
 * Supports sorting by hotness, title, or newest first, and filtering
 * by artist, language, tag, vocal form, and other modes.
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import PagedCarousel from '../components/PagedCarousel.vue'
import {createPageCache} from '../composables/pageCache'
import {useSwipePages} from '../composables/useSwipePages'
import { useRoute } from 'vue-router'
import api, { makeControls } from '../api/client'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import TabBar from '../components/TabBar.vue'
import SongRow from '../components/SongRow.vue'

const route = useRoute(), user = useUserStore(), { toast } = useToast(), controls = makeControls(user.clientToken)
const page=ref(0),hasNext=ref(false),turning=ref(false),pageCache=createPageCache();let loadSequence=0

const songs = ref([]), loading = ref(true), sort = ref(route.query.sort || 'hot'), artistGender = ref(route.query.artistGender || '')
const genderOptions = [{ value:'', label:'全部歌手' }]
// 已点歌曲 ID 集合，防止重复点歌 / Set of ordered song IDs to prevent duplicate ordering
const orderedIds = reactive(new Set())

/**
 * 当前浏览模式。
 * 可选值：all（全部）、artist（艺人）、language（语种）、tag（标签）、vocalForm（演唱形式）。
 *
 * Current browse mode.
 * Possible values: all, artist, language, tag, vocalForm.
 */
const mode = computed(() => route.query.mode || (route.params.name === 'all' ? 'all' : 'artist'))

/**
 * 当前筛选值，如艺人名、语种名等。
 *
 * Current filter value, e.g. artist name, language name, etc.
 */
const value = computed(() => route.query.value || route.params.name || '')

/**
 * 页面标题，根据当前模式动态生成。
 *
 * Page title, dynamically generated based on the current mode.
 */
const pageTitle = computed(() => mode.value === 'all' ? '全部歌曲' : mode.value === 'language' ? `${value.value}歌曲` : mode.value === 'tag' ? value.value : mode.value === 'vocalForm' ? value.value : value.value)

/**
 * 加载歌曲列表。
 *
 * Load song list.
 */
async function fetchSongs(index){const params={limit:9,offset:index*8,scope:route.query.scope||'all'};if(mode.value!=='all')params[mode.value]=value.value;return api.browseSongs(params)}
/**
 * 切换排序方式并重新加载。
 *
 * @param {'hot' | 'title' | 'new'} value - 排序方式 / sort method
 */
function setSort(value) { sort.value = value; load() }
function setGender(value) { artistGender.value = value; load() }

/**
 * 将歌曲加入播放队列。失败时弹出错误提示。
 *
 * @param {{ id: string }} song - 歌曲对象 / song object
 */
async function order(song) { try { await controls.order(song.id); orderedIds.add(song.id); toast('已加入队列') } catch (error) { toast(error.message || '点歌失败') } }

/**
 * 将毫秒时长格式化为 mm:ss 字符串。
 *
 * @param {number} ms - 毫秒 / milliseconds
 * @returns {string} 格式化后的时长 / formatted duration string
 */
function fmtDur(ms) { if (!ms) return ''; const value = Math.round(ms / 1000); return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}` }
</script>

<style scoped>
.page{min-height:100vh;padding-bottom:74px}.head{display:flex;align-items:center;gap:12px;padding:13px 16px 8px}.head button{border:0;background:none;color:var(--text);font-size:30px;width:30px}.head h1{font-size:19px;margin:0}.head p{font-size:11px;color:var(--dim);margin:3px 0 0}.sorts,.gender-filter{display:flex;gap:7px;padding:7px 16px 5px;overflow-x:auto}.gender-filter{padding-top:2px;padding-bottom:8px}.sorts button,.gender-filter button{border:1px solid var(--glass-border);background:var(--panel2);color:var(--dim);border-radius:999px;padding:6px 14px;white-space:nowrap}.sorts button.on,.gender-filter button.on{background:var(--gold-glow);border-color:rgba(240,199,66,.28);color:var(--gold)}main{padding:0 16px}.tip{text-align:center;color:var(--dim2);padding:45px 10px}
</style>

<style scoped>.paging{display:flex;gap:24px;align-items:center;justify-content:center;padding:25px}.paging button{padding:15px 30px;background:var(--panel2);color:var(--gold);border:1px solid var(--gold);border-radius:10px;font-size:17px}.paging button:disabled{opacity:.3}</style>

<style scoped>main{display:grid;grid-template-columns:1fr 1fr;column-gap:24px}.paging{padding:12px}.paging button{padding:8px 24px;font-size:13px}.head p{font-size:11px;color:var(--dim2)}.sorts{margin-bottom:5px}.gender-filter{display:none}</style>

<style scoped>.song-pages{height:100%;display:flex;flex-direction:column;overflow:hidden}.song-pages .head{height:30px;min-height:30px;flex:none;padding:0 0 5px;gap:9px}.song-pages .head h1{font-size:17px;margin:0;line-height:1.2}.song-pages .head p{display:none}.song-pages .sorts{display:none}.song-pages main{padding:0;flex:1;min-height:0;grid-template-rows:repeat(4,minmax(0,1fr));overflow:hidden;touch-action:pan-y}.song-pages :deep(.songrow){min-height:0!important;height:100%;padding-top:5px;padding-bottom:5px}.song-pages .paging{flex:none;min-height:43px;padding:5px;gap:22px}.song-pages .paging button{min-height:35px}.song-pages .tip{grid-column:1/-1;padding:15px}</style>

<style scoped>.artist-carousel{flex:1;min-height:0}.song-pages .head{height:30px}.song-pages .head h1{font-size:17px}</style>
