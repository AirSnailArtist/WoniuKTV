<template><div class="home-screen"><header class="home-heading"><div><h1>今晚，唱你喜欢的。</h1><small class="heading-label">专属音乐空间</small></div><span>方向键选歌 · 确认点歌</span></header><ChartRecommendations/><PersonalRecommendations/><footer><span>115 私人曲库 · 本机智能学习</span><button @click="router.push('/recommendations')">查看可能想唱 ›</button><button @click="manage">曲库与备份</button></footer></div></template>
<script setup>
import ChartRecommendations from '../components/ChartRecommendations.vue'
import PersonalRecommendations from '../components/PersonalRecommendations.vue'
import {useRouter} from 'vue-router'
const router=useRouter()
function manage(){window.Woniu?.openManagement()}
</script>
<style scoped>
.home-screen{height:100%;min-height:0;display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);grid-template-rows:40px minmax(0,1fr) 22px;gap:10px 16px}.home-heading{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between}.home-heading h1{font-size:23px;letter-spacing:1px;font-weight:600;margin:0}.home-heading>div{display:flex;align-items:center;gap:15px}.home-heading .heading-label{font-size:9px;letter-spacing:2px;color:var(--gold);border-left:1px solid var(--gold);padding-left:12px}.home-heading>span{font-size:10px;color:var(--dim2)}.home-screen :deep(.chart-section){grid-column:1;grid-row:2;min-height:0}.personal-recs{grid-column:2;grid-row:2}footer{grid-column:1/-1;display:flex;align-items:center;gap:8px;font-size:9px;color:var(--dim2)}footer>span{margin-right:auto;letter-spacing:.5px}footer button{color:var(--dim);font-size:10px;min-height:22px;padding:0 9px;border-radius:6px}footer button:hover{color:var(--gold)}
</style>
