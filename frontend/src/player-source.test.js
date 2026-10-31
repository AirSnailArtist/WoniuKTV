import {it,expect} from 'vitest'
import {JSDOM} from 'jsdom'
import {readFileSync} from 'node:fs'
it('shows the selected playback provider while connecting and in playback help',()=>{
 const html=readFileSync('../app/src/main/assets/player-overlay.html','utf8'),dom=new JSDOM(html,{runScripts:'outside-only'}),w=dom.window
 let source='bilibili';w.Player={state:()=>JSON.stringify({source,state:'loading',tracks:[],channels:2}),action:()=>{},queue:()=>JSON.stringify({list:[]})}
 try{w.eval(w.document.querySelector('script').textContent);w.refresh();expect(w.document.getElementById('loadingSource').textContent).toContain('B站');expect(w.document.getElementById('loadingSource').textContent).not.toContain('115');w.openMore();expect(w.document.getElementById('popupNote').textContent).toContain('B站');source='115';w.refresh();expect(w.document.getElementById('loadingSource').textContent).toContain('115')}finally{w.close()}
})
