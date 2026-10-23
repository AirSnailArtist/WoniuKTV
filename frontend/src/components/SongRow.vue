<template>
  <div class="songrow">
    <!-- 排名区域 / Rank area -->
    <span v-if="rank" class="rank" :class="{ top: rank <= 3 }">{{ rank }}</span>
    <div class="cover" :class="{ empty: !song.coverUrl }">
      <img v-if="song.coverUrl" :src="song.coverUrl" :alt="`${song.title || '歌曲'}封面`" loading="lazy" referrerpolicy="no-referrer" />
      <Music2 v-else :size="18" />
    </div>
    <!-- 歌曲信息 / Song info -->
    <div class="grow info">
      <div class="t">
        <span v-html="highlightedTitle"></span>
        <span class="tag" :class="tagClass">{{ tagText }}</span>
      </div>
      <div class="s">{{ song.artist }}<span v-if="song.language"> · {{song.language}}</span><span v-if="song.edition"> · {{song.edition}}</span><span v-if="extra"> · {{ extra }}</span></div>
    </div>
    <!-- 收藏按钮 / Favorite button -->
    <button class="favorite-btn" :class="{ on: favorites.has(song.id) }" :disabled="favoriteBusy"
            :aria-label="favorites.has(song.id) ? '取消收藏' : '收藏'" @click="toggleFavorite">
      <Heart :size="19" :fill="favorites.has(song.id) ? 'currentColor' : 'none'" />
    </button>
    <!-- 点歌按钮 / Order song button -->
    <button class="order-btn" :class="{done:isOrdered}" :aria-disabled="isOrdered" :aria-label="isOrdered?'已点':'点歌'" @click="isOrdered?toast('这首歌已点'):$emit('order',song)"><Check v-if="isOrdered" :size="18"/><Plus v-else :size="22"/><span v-if="isOrdered" class="ordered-label">已点</span></button>
  </div>
  <Teleport to="body">
    <div v-if="playlistOpen" class="playlist-mask" @click.self="closePlaylistPicker">
      <section class="playlist-dialog" role="dialog" aria-modal="true" aria-label="加入歌单">
        <header class="playlist-dialog-head"><div><strong>加入歌单</strong><small>{{ song.title }} · {{ song.artist || '未知歌手' }}</small></div><button aria-label="关闭" @click="closePlaylistPicker">×</button></header>
        <div v-if="playlistLoading" class="playlist-dialog-empty">正在加载歌单…</div>
        <div v-else class="playlist-options">
          <button v-for="playlist in playlists" :key="playlist.id" :disabled="addingPlaylistId === playlist.id" @click="addToPlaylist(playlist)"><span><strong>{{ playlist.name }}</strong><small>{{ playlist.theme || '未设置主题' }} · {{ playlist.songCount || 0 }} 首</small></span><em>{{ addingPlaylistId === playlist.id ? '加入中…' : '加入' }}</em></button>
          <div v-if="!playlists.length" class="playlist-dialog-empty">暂无公开歌单</div>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
/**
 * SongRow 组件 —— 歌单列表行。
 * 支持排名展示、关键词高亮、媒体类型标签、收藏切换和点歌操作。
 *
 * SongRow component — a single row in a song list.
 * Supports rank display, keyword highlighting, media type tags,
 * favorite toggling, and song ordering.
 */
import { computed, ref } from 'vue'
import api from '../api/client'
import {usePlayerStore} from '../stores/player'
import { useFavoritesStore } from '../stores/favorites'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import { Check, Heart, ListPlus, Music2, Plus } from 'lucide-vue-next'

const props = defineProps({
  /** 歌曲对象，必传 / Song object, required */
  song: { type: Object, required: true },
  /** 排名序号，<=3 时高亮 / Rank number, highlighted when <= 3 */
  rank: { type: Number, default: 0 },
  /** 搜索关键词，用于标题高亮 / Search keyword for title highlighting */
  keyword: { type: String, default: '' },
  /** 附加信息文本，显示在歌手名后 / Extra text shown after artist name */
  extra: { type: String, default: '' },
  /** 是否已点歌，控制按钮状态 / Whether the song has already been ordered */
  ordered: { type: Boolean, default: false }
})

/** 触发点歌事件 / Emits order song event */
defineEmits(['order'])
const favorites = useFavoritesStore()
const player=usePlayerStore()
const isOrdered=computed(()=>player.orderedSongIds.has(String(props.song.id)))
const user = useUserStore()
const { toast } = useToast()
const favoriteBusy = ref(false)
const playlistOpen = ref(false)
const playlistLoading = ref(false)
const playlists = ref([])
const addingPlaylistId = ref(null)

/**
 * 切换当前歌曲的收藏状态，并弹出提示。
 *
 * Toggle the favorite state of the current song and show a toast notification.
 */
async function toggleFavorite() {
  favoriteBusy.value = true
  try {
    const added = await favorites.toggle(props.song.id, user.clientToken)
    toast(added ? '已加入收藏' : '已取消收藏')
  } catch (error) {
    toast(error.message || '收藏操作失败')
  } finally {
    favoriteBusy.value = false
  }
}

async function openPlaylistPicker() {
  playlistOpen.value = true
  if (playlists.value.length || playlistLoading.value) return
  playlistLoading.value = true
  try { playlists.value = await api.playlists() } catch (error) { toast(error.message || '歌单加载失败') } finally { playlistLoading.value = false }
}
function closePlaylistPicker() { if (!addingPlaylistId.value) playlistOpen.value = false }
async function addToPlaylist(playlist) {
  addingPlaylistId.value = playlist.id
  try {
    const result = await api.addSongToPlaylist(playlist.id, props.song.id)
    playlist.songCount = result?.songCount ?? playlist.songCount
    toast(result?.added === false ? '歌曲已在歌单中' : `已加入「${playlist.name}」`)
    playlistOpen.value = false
  } catch (error) { toast(error.message || '加入歌单失败') } finally { addingPlaylistId.value = null }
}

const tagText = computed(() => ({
  KTV_VIDEO: 'KTV版', MV: 'MV版', AUDIO: '音频版'
}[props.song.mediaType] || ''))

const tagClass = computed(() => ({
  KTV_VIDEO: 'tag-ktv', MV: 'tag-mv', AUDIO: 'tag-audio'
}[props.song.mediaType] || 'tag-audio'))

/**
 * 关键词高亮（详设 H5-03）。
 * 将歌曲标题中匹配关键词的部分用高亮 span 包裹。
 *
 * Keyword highlighting (design spec H5-03).
 * Wraps the matching portion of the song title in a highlighted span.
 */
const highlightedTitle = computed(() => {
  const title = props.song.title || ''
  const kw = props.keyword?.trim()
  if (!kw) return escapeHtml(title)
  const idx = title.toLowerCase().indexOf(kw.toLowerCase())
  if (idx < 0) return escapeHtml(title)
  return escapeHtml(title.slice(0, idx))
    + '<span class="hl">' + escapeHtml(title.slice(idx, idx + kw.length)) + '</span>'
    + escapeHtml(title.slice(idx + kw.length))
})

/**
 * HTML 转义，防止 XSS 注入。
 * 转义 & < > " 四个字符。
 *
 * Escape HTML special characters to prevent XSS injection.
 * Escapes & < > " characters.
 * @param {string} s - 原始字符串 / Raw string
 * @returns {string} 转义后的字符串 / Escaped string
 */
function escapeHtml(s) {
  return s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))
}
</script>

<style scoped>
.songrow { display:flex;align-items:center;gap:9px;min-height:62px;padding:7px 0;border-bottom:1px solid rgba(255,255,255,.07); }
.rank { width:24px;text-align:center;font-weight:800;color:var(--dim2);font-size:12px; }
.rank.top { color: var(--gold); }
.cover { width:46px;height:46px;display:grid;place-items:center;flex:none;overflow:hidden;border-radius:8px;background:#202630 center/cover no-repeat;color:var(--dim2);border:1px solid rgba(255,255,255,.08); }
.cover img { width:100%;height:100%;object-fit:cover;display:block; }
.info { min-width: 0; }
.t { font-size:13px;font-weight:650;display:flex;align-items:center;gap:5px; }
.t :deep(.hl) { color: var(--gold); }
.s { font-size:10px;color:var(--dim);margin-top:4px; }
.order-btn {
  width:44px;height:44px;display:grid;place-items:center;background:var(--gold);color:#201a0f;
  border-radius:50%;padding:0;flex:none;transition:var(--transition);
}
.order-btn:active { transform: scale(.95); }
.order-btn.done { background:rgba(110,214,168,.12);color:var(--mint);box-shadow:none; }
.favorite-btn { display:grid;place-items:center;color:var(--dim2);padding:6px;flex:none;transition:var(--transition); }
.playlist-btn { display:grid;place-items:center;color:var(--dim2);padding:6px;flex:none;transition:var(--transition); }
.playlist-btn:active { transform: scale(.88); }
.favorite-btn.on { color:var(--coral); }
.favorite-btn:active { transform: scale(.88); }
.favorite-btn:disabled { opacity: .45; }
.playlist-mask { position:fixed;inset:0;z-index:120;display:grid;place-items:center;padding:18px;background:rgba(0,0,0,.62); }
.playlist-dialog { width:min(360px,calc(100vw - 28px));max-height:calc(100vh - 40px);overflow:hidden;border:1px solid rgba(255,255,255,.12);border-radius:14px;background:#17171b;color:#f7f3eb;box-shadow:0 20px 55px rgba(0,0,0,.4); }
.playlist-dialog-head { display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:16px;border-bottom:1px solid rgba(255,255,255,.1); }
.playlist-dialog-head strong,.playlist-dialog-head small { display:block; }.playlist-dialog-head strong { font-size:16px; }.playlist-dialog-head small { margin-top:5px;color:var(--dim);font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:270px; }.playlist-dialog-head button { color:var(--dim);font-size:25px;line-height:1;padding:0 4px; }
.playlist-options { max-height:360px;overflow:auto; }.playlist-options>button { display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%;padding:13px 16px;border-bottom:1px solid rgba(255,255,255,.08);text-align:left;color:inherit; }.playlist-options>button:hover:not(:disabled) { background:rgba(255,255,255,.06); }.playlist-options>button strong,.playlist-options>button small { display:block; }.playlist-options>button strong { font-size:13px; }.playlist-options>button small { margin-top:4px;color:var(--dim);font-size:10px; }.playlist-options>button em { flex:none;color:var(--gold);font-size:11px;font-style:normal;font-weight:700; }.playlist-dialog-empty { padding:34px 16px;text-align:center;color:var(--dim);font-size:12px; }
</style>

<style scoped>.order-btn.done{width:auto!important;min-width:52px;padding:0 6px;gap:3px}.ordered-label{font-size:11px;white-space:nowrap}</style>
