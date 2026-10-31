import {it,expect,vi} from 'vitest'
import {JSDOM} from 'jsdom'
import {readFileSync} from 'node:fs'
it('keeps comment animation continuous across progress samples and stops immediately when disabled',()=>{
 const d=new JSDOM('<div id="dm"></div>',{runScripts:'outside-only'}),w=d.window;Object.defineProperty(w.document,'hidden',{value:false});w.requestAnimationFrame=vi.fn()
 try{w.eval(readFileSync('../app/src/main/assets/bili-danmaku.js','utf8'));const root=w.document.getElementById('dm'),renderer=w.createWoniuDanmaku(root),state={source:'bilibili',cid:'1',danmakuEnabled:true,videoVisible:true,position:1000,state:'playing'},data={cid:'1',items:[{id:'x',at:1000,mode:1,color:16777215,text:'<b>弹幕</b>'}]};renderer.update(state,data);const span=root.firstChild;expect(span.textContent).toBe('<b>弹幕</b>');expect(root.querySelector('b')).toBeNull();const delay=span.style.animationDelay;renderer.update({...state,position:1200},data);expect(root.firstChild).toBe(span);expect(span.style.animationDelay).toBe(delay);renderer.update({...state,state:'paused',position:1300},data);expect(span.style.animationPlayState).toBe('paused');renderer.update({...state,danmakuEnabled:false},data);expect(root.children).toHaveLength(0);expect(w.requestAnimationFrame).not.toHaveBeenCalled()}finally{w.close()}
})
