import{e as k,_ as b,o as r,c as u,b as t,a as n,u as i,t as m,X as v,n as y,F as f,r as z,i as C,l as M,s as V}from"./index-D4gGJ2FX.js";/**
 * @license lucide-vue-next v0.577.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const g=k("arrow-right",[["path",{d:"M5 12h14",key:"1ays0h"}],["path",{d:"m12 5 7 7-7 7",key:"xquz4c"}]]);/**
 * @license lucide-vue-next v0.577.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const $=k("delete",[["path",{d:"M10 5a2 2 0 0 0-1.344.519l-6.328 5.74a1 1 0 0 0 0 1.481l6.328 5.741A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z",key:"1yo7s0"}],["path",{d:"m12 9 6 6",key:"anjzzh"}],["path",{d:"m18 9-6 6",key:"1fp51s"}]]);/**
 * @license lucide-vue-next v0.577.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const x=k("keyboard",[["path",{d:"M10 8h.01",key:"1r9ogq"}],["path",{d:"M12 12h.01",key:"1mp3jc"}],["path",{d:"M14 8h.01",key:"1primd"}],["path",{d:"M16 12h.01",key:"1l6xoz"}],["path",{d:"M18 8h.01",key:"emo2bl"}],["path",{d:"M6 8h.01",key:"x9i8wu"}],["path",{d:"M7 16h10",key:"wp8him"}],["path",{d:"M8 12h.01",key:"czm47f"}],["rect",{width:"20",height:"16",x:"2",y:"4",rx:"2",key:"18n3k1"}]]),S={class:"search-keyboard"},w={class:"keyboard-title"},B={class:"keyboard-value"},A={class:"keyboard-modes"},K={class:"key-grid"},N=["onClick"],D={class:"keyboard-bottom"},F={__name:"SearchKeyboard",props:{modelValue:{type:String,default:""},title:{type:String,default:"快速找歌"}},emits:["update:modelValue","submit","system"],setup(o,{emit:p}){const c=p,a=M(!1),h=V(()=>a.value?"1234567890".split(""):"ABCDEFGHIJKLMNOPQRSTUVWXYZ".split(""));function d(l){c("update:modelValue",l.slice(0,80))}return(l,e)=>(r(),u("aside",S,[t("div",w,[n(i(x),{size:18}),t("b",null,m(o.title),1)]),t("div",B,[t("span",null,m(o.modelValue||"输入拼音首字母"),1),t("button",{"aria-label":"退格",onClick:e[0]||(e[0]=s=>d(o.modelValue.slice(0,-1)))},[n(i($),{size:20})]),t("button",{"aria-label":"清空",onClick:e[1]||(e[1]=s=>d(""))},[n(i(v),{size:18})])]),t("div",A,[t("button",{class:y({on:!a.value}),onClick:e[2]||(e[2]=s=>a.value=!1)},"ABC 首字母",2),t("button",{class:y({on:a.value}),onClick:e[3]||(e[3]=s=>a.value=!0)},"123 数字",2)]),t("div",K,[(r(!0),u(f,null,z(h.value,s=>(r(),u("button",{key:s,onClick:I=>d(o.modelValue+s)},m(s),9,N))),128))]),t("div",D,[t("button",{onClick:e[4]||(e[4]=s=>l.$emit("system"))},"系统输入"),t("button",{onClick:e[5]||(e[5]=s=>l.$emit("submit")),class:"confirm"},[e[6]||(e[6]=C("搜索 ",-1)),n(i(g),{size:17})])]),e[7]||(e[7]=t("p",null,"支持触屏和遥控器操作",-1))]))}},j=b(F,[["__scopeId","data-v-cd90b586"]]);export{j as S};
