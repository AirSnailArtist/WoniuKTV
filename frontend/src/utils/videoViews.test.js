import {it,expect} from 'vitest'
import {formatVideoViews} from './videoViews'
it('shows real video view units and distinguishes unavailable counts from zero',()=>{expect(formatVideoViews(123456)).toBe('12.3万次播放');expect(formatVideoViews(0)).toBe('0次播放');expect(formatVideoViews(-1)).toContain('暂不可用');expect(formatVideoViews(undefined)).toContain('暂不可用')})
