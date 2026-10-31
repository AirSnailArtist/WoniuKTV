import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { JSDOM } from 'jsdom'
import { describe, expect, it, vi } from 'vitest'

const html = readFileSync(resolve(process.cwd(), '../app/src/main/assets/player-overlay.html'), 'utf8')

describe('player progress control', () => {
  function player(state) {
    const seek = vi.fn()
    const dom = new JSDOM(html, {
      runScripts: 'dangerously',
      beforeParse(window) {
        window.Player = { state: () => JSON.stringify(state), seek, action() {} }
      },
    })
    return { dom, seek, input: dom.window.document.getElementById('seek') }
  }

  it('allows seeking by fraction while total duration is unknown', () => {
    const { dom, seek, input } = player({ state: 'playing', duration: 0, seekable: true, fraction: 0.25 })
    try {
      expect(input.disabled).toBe(false)
      expect(input.value).toBe('250')
      input.value = '500'
      input.dispatchEvent(new dom.window.Event('change'))
      expect(seek).toHaveBeenCalledTimes(1)
      expect(seek).toHaveBeenCalledWith(500)
    } finally { dom.window.close() }
  })

  it('disables an input that cannot seek and displays normal song progress', () => {
    const unknown = player({ duration: 0, seekable: false })
    try { expect(unknown.input.disabled).toBe(true) } finally { unknown.dom.window.close() }
    const known = player({ duration: 280000, position: 140000, seekable: true })
    try {
      expect(known.input.disabled).toBe(false)
      expect(known.input.value).toBe('500')
    } finally { known.dom.window.close() }
  })
})
