// Scale against the actual WebView window, including split-screen and system bars.
const KEY='woniu-ui-size'
const clamp=(n,lo,hi)=>Math.max(lo,Math.min(hi,n))
export function autoScale(width,height){return clamp(Math.min(width/960,height/540),.35,4)}
export function preference(){try{const n=Number(localStorage.getItem(KEY));return Number.isFinite(n)&&n>=.7&&n<=1.3?n:1}catch{return 1}}
export function applyViewportFit(){
 const scale=autoScale(window.innerWidth,window.innerHeight)*preference()
 document.documentElement.style.setProperty('--ui-scale',String(scale))
 document.documentElement.style.setProperty('--ui-width',(window.innerWidth/scale)+'px')
 document.documentElement.style.setProperty('--ui-height',(window.innerHeight/scale)+'px')
 document.documentElement.dataset.uiCompact=String(window.innerWidth/scale<=1100)
 window.dispatchEvent(new Event('woniu-viewport-fit'))
}
export function setViewportPreference(value){const n=clamp(value,.7,1.3);try{if(n===1)localStorage.removeItem(KEY);else localStorage.setItem(KEY,String(n))}catch{}applyViewportFit();return n}
export function installViewportFit(){applyViewportFit();window.addEventListener('resize',applyViewportFit);window.visualViewport?.addEventListener('resize',applyViewportFit)}
