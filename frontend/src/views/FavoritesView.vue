<template>
  <div class="page favorites-pages">
    <header class="top"><button @click="$router.back()">‹</button><strong>我的收藏</strong><span>{{ songs.length }} 首</span></header>
    <PagedCarousel :items="songs" :page-size="6" :columns="2"><template #default="{items}"><SongRow v-for="song in items" :key="song.id" :song="song" :ordered="orderedIds.has(song.id)" @order="order"/></template></PagedCarousel>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 收藏页面 — 展示用户收藏的歌曲列表，支持点歌操作。
 * 收藏数据与当前手机身份绑定，切换身份后数据不同。
 *
 * Favorites page — displays the user's favorited songs and supports queuing.
 * Favorites are tied to the current device identity and differ across identities.
 */

import { onMounted, reactive, ref, watch,computed } from 'vue'
import PagedCarousel from '../components/PagedCarousel.vue'
import {useSwipePages} from '../composables/useSwipePages'
import api, { makeControls } from '../api/client'
import SongRow from '../components/SongRow.vue'
import TabBar from '../components/TabBar.vue'
import { useFavoritesStore } from '../stores/favorites'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'

const user = useUserStore()
const favorites = useFavoritesStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)
const songs = ref([]),page=ref(0)
const pages=computed(()=>Math.max(1,Math.ceil(songs.value.length/6))),visibleSongs=computed(()=>songs.value.slice(page.value*6,page.value*6+6))
function previous(){if(page.value)page.value--}function next(){if(page.value+1<pages.value)page.value++}
const {touchStart,touchEnd}=useSwipePages(previous,next)
const loading = ref(true)
const orderedIds = reactive(new Set())

onMounted(load)
watch(() => favorites.ids.slice(), ids => {
  songs.value = songs.value.filter(song => ids.includes(song.id))
})

/**
 * 加载收藏歌曲列表，同时同步收藏 ID 到 store。
 * 失败时清空列表，避免展示过期数据。
 *
 * Loads the favorites song list and syncs favorite IDs to the store.
 * On failure, clears the list to avoid showing stale data.
 */
async function load() {
  loading.value = true
  try {
    songs.value = await api.favorites(user.clientToken)
    await favorites.load(user.clientToken, true)
  } catch {
    songs.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 将歌曲加入播放队列（点歌）。
 * 成功后将 song.id 标记为已点，避免重复操作。
 *
 * @param {Object} song - 歌曲对象，需包含 id 属性。
 *
 * Queues a song for playback.
 * On success, marks the song id as ordered to prevent duplicate operations.
 *
 * @param {Object} song - Song object, must contain an id property.
 */
async function order(song) {
  try {
    await controls.order(song.id)
    orderedIds.add(song.id)
    toast('已加入队列')
  } catch (error) {
    toast(error.code === 'SONG_IN_QUEUE' ? (error.message || '已在队列中') : (error.message || '点歌失败'))
  }
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; }
.top { height: 54px; padding: 0 16px; display: grid; grid-template-columns: 44px 1fr 44px; align-items: center; border-bottom: 1px solid var(--line); }
.top button { text-align: left; font-size: 28px; color: var(--dim); }
.top strong { text-align: center; }
.top span { color: var(--dim2); font-size: 12px; white-space: nowrap; }
main { padding: 16px; }
.intro { display: flex; gap: 14px; align-items: center; padding: 18px; margin-bottom: 10px; border-radius: var(--radius); background: linear-gradient(135deg, rgba(248,113,113,.16), var(--panel)); border: 1px solid rgba(248,113,113,.18); }
.intro > span { font-size: 34px; }
.intro h1 { font-size: 20px; }
.intro p, .empty p { color: var(--dim); font-size: 13px; margin-top: 4px; }
.tip, .empty { color: var(--dim2); text-align: center; padding: 48px 0; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 7px; }
.empty > span { color: #f87171; font-size: 48px; }
.empty strong { color: var(--text); }
</style>

<style scoped>.favorites-pages{height:100%;overflow:hidden;display:flex;flex-direction:column}.favorites-pages .intro{display:none}.favorites-pages main{padding:0;flex:1;min-height:0;overflow:hidden;touch-action:pan-y}.favorites-pages .list{height:100%;display:grid;grid-template-columns:1fr 1fr;grid-template-rows:repeat(3,minmax(0,1fr));gap:0 20px}.favorites-pages :deep(.songrow){min-height:0!important;height:100%}.favorites-pager{height:42px;flex:none;display:flex;align-items:center;justify-content:center;gap:24px}.favorites-pager button{padding:8px 20px;background:var(--panel2);border-radius:8px;color:var(--gold)}</style>

<style scoped>.favorites-pages>.paged-carousel{flex:1;min-height:0}</style>
