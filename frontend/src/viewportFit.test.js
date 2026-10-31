import {afterEach,describe,expect,it,vi} from 'vitest'
import {autoScale,preference,setViewportPreference,applyViewportFit} from './viewportFit'
afterEach(()=>{localStorage.clear();vi.unstubAllGlobals()})
describe('automatic display fitting',()=>{
 it('normalizes standard, high-resolution and ultrawide windows to a usable landscape layout',()=>{
  expect(autoScale(960,540)).toBe(1)
  expect(autoScale(1920,1080)).toBe(2)
  expect(autoScale(1920,720)).toBeCloseTo(4/3)
  expect(autoScale(800,450)).toBeCloseTo(5/6)
 })
 it('persists a bounded manual adjustment and recalculates on window size change',()=>{
  vi.stubGlobal('innerWidth',1920);vi.stubGlobal('innerHeight',1080)
  expect(setViewportPreference(1.2)).toBe(1.2);expect(preference()).toBe(1.2)
  expect(parseFloat(document.documentElement.style.getPropertyValue('--ui-scale'))).toBeCloseTo(2.4)
  vi.stubGlobal('innerHeight',720);applyViewportFit()
  expect(parseFloat(document.documentElement.style.getPropertyValue('--ui-scale'))).toBeCloseTo(1.6)
  expect(setViewportPreference(100)).toBe(1.3);expect(setViewportPreference(1)).toBe(1)
  expect(localStorage.getItem('woniu-ui-size')).toBe(null)
 })
})
