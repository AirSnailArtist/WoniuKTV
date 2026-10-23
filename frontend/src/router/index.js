import { createRouter, createWebHashHistory } from 'vue-router'
export default createRouter({history:createWebHashHistory(),routes:[
{path:'/',redirect:'/home'},
{path:'/recommendations',name:'recommendations',component:()=>import('../views/RecommendationsView.vue')},
{path:'/ranking',name:'ranking',component:()=>import('../views/RankingView.vue')},
{path:'/home',name:'home',component:()=>import('../views/HomeView.vue')},
{path:'/search',name:'search',component:()=>import('../views/SearchView.vue')},
{path:'/favorites',name:'favorites',component:()=>import('../views/FavoritesView.vue')},
{path:'/browse',name:'browse',component:()=>import('../views/CategoryBrowseView.vue')},
{path:'/artist/:name',name:'artist',component:()=>import('../views/ArtistView.vue')},
{path:'/queue',name:'queue',component:()=>import('../views/QueueView.vue')},
{path:'/:pathMatch(.*)*',redirect:'/home'}
]})
