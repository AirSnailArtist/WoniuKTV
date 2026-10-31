<template><div class="home-screen"><ChartRecommendations/><PersonalRecommendations/></div></template>
<script setup>
import ChartRecommendations from '../components/ChartRecommendations.vue'
import PersonalRecommendations from '../components/PersonalRecommendations.vue'
</script>
<style scoped>
.home-screen{height:100%;min-height:0;display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);grid-template-rows:minmax(0,1fr);gap:16px}.home-screen :deep(.chart-section){grid-column:1;min-height:0}.personal-recs{grid-column:2}.home-screen :deep(.personal-recs>footer){display:none}
</style>
