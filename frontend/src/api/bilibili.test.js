import {expect,it} from 'vitest'
import {folderReference} from './bilibili'
it('parses favorite links while rejecting unrelated and forged hosts',()=>{
 expect(folderReference('https://space.bilibili.com/123/favlist?fid=456')).toBe('456')
 expect(folderReference('https://www.bilibili.com/medialist/detail/ml789')).toBe('789')
 expect(folderReference('ml456')).toBe('456')
 expect(folderReference('123')).toBe('')
 expect(()=>folderReference('https://space.bilibili.com.attacker.example/?fid=456')).toThrow()
 expect(()=>folderReference('https://www.bilibili.com/video/BV1GJ411x7h7')).toThrow()
})
