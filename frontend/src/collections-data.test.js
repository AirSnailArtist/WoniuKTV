import {it,expect} from 'vitest'
import {readFileSync} from 'node:fs'
it('ships 500 sourced song names per cohort/language without private playlists or audio streams',()=>{
 const text=readFileSync('../app/src/main/assets/music-collections.json','utf8'),data=JSON.parse(text)
 expect(Object.keys(data.collections)).toHaveLength(7)
 for(const c of Object.values(data.collections)){expect(c.items).toHaveLength(500);expect(new Set(c.items.map(x=>x.title+'|'+x.artists.join('|'))).size).toBe(500);for(const item of c.items){expect(item.title).toBeTruthy();expect(item.artists.length).toBeGreaterThan(0);expect(data.sources[item.source]?.url).toMatch(/^https:\/\/music\.163\.com\/playlist\?id=/)}}
 expect(text).not.toContain('SESSDATA');expect(text).not.toMatch(/https?:[^\s"]+\.(?:mp3|mp4)/)
})
