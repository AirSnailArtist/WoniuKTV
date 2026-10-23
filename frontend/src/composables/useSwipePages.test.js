import {describe,it,expect,vi} from 'vitest'
import {useSwipePages} from './useSwipePages'
describe('touch page selection',()=>{
 it('turns once for a deliberate horizontal swipe',()=>{const previous=vi.fn(),next=vi.fn(),s=useSwipePages(previous,next),end={changedTouches:[{clientX:20,clientY:12}],preventDefault:vi.fn()};s.touchStart({touches:[{clientX:150,clientY:10}]});s.touchEnd(end);expect(next).toHaveBeenCalledTimes(1);expect(previous).not.toHaveBeenCalled();expect(end.preventDefault).toHaveBeenCalledTimes(1)})
 it('leaves taps and vertical gestures alone',()=>{const next=vi.fn(),s=useSwipePages(vi.fn(),next);for(const point of [{clientX:148,clientY:12},{clientX:120,clientY:200}]){s.touchStart({touches:[{clientX:150,clientY:10}]});const e={changedTouches:[point],preventDefault:vi.fn()};s.touchEnd(e);expect(e.preventDefault).not.toHaveBeenCalled()}expect(next).not.toHaveBeenCalled()})
})
