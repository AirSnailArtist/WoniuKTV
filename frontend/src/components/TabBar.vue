<template>
  <!-- 底部导航栏：点歌/已点/遥控 / Bottom tab bar: Song Select / Queue / Remote -->
  <nav class="tabbar">
    <router-link class="tab" :class="{ on: active === 'home' }" :to="{ name: 'home' }">
      <Search class="ic" :size="19" />点歌
    </router-link>
    <router-link class="tab" :class="{ on: active === 'queue' }" :to="{ name: 'queue' }">
      <ListMusic class="ic" :size="19" />已点
      <span v-if="player.queueCount" class="badge">{{ player.queueCount }}</span>
    </router-link>
    <button class="tab" @click="openManagement"><Radio class="ic" :size="19" />曲库管理</button>
  </nav>
</template>

<script setup>
function openManagement() { window.Woniu?.openManagement() }
/**
 * TabBar - 底部标签栏组件
 * 提供点歌、已点（含排队计数徽标）、遥控三个页面的导航入口。
 *
 * TabBar - Bottom tab navigation component.
 * Provides navigation to three pages: Song Select, Queue (with count badge), and Remote Control.
 */

import { usePlayerStore } from '../stores/player'
import { Search, ListMusic, Radio } from 'lucide-vue-next'

/**
 * active - 当前激活的标签页名称（home | queue | remote）
 *         Name of the currently active tab (home | queue | remote)
 */
defineProps({ active: { type: String, default: '' } })

// 播放器状态仓库，用于获取排队歌曲数量 / Player store, used to retrieve the queue count
const player = usePlayerStore()
</script>

<style scoped>
.tabbar {
  position: fixed; bottom: 0; left: 0; right: 0;
  height: calc(62px + var(--safe-bottom)); padding-bottom: var(--safe-bottom);
  background: rgba(15,18,24,.96); backdrop-filter: blur(18px);
  border-top: 1px solid var(--line); display: flex; z-index: 40;
}
.tab {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 3px; font-size: 11px; color: var(--dim2); transition: var(--transition); position: relative;
}
.tab.on { color: var(--gold); }
.tab .ic { width:19px;height:19px; }
.badge {
  position: absolute; top: 8px; margin-left: 34px;
  background: var(--coral); color: #20110f; font-size: 10px; font-weight: 700;
  min-width: 16px; height: 16px; border-radius: 8px; padding: 0 4px;
  display: inline-flex; align-items: center; justify-content: center;
}
</style>
