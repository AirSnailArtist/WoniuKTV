import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../api/client', () => ({
  default: {
    favoriteIds: vi.fn(),
    addFavorite: vi.fn(),
    removeFavorite: vi.fn()
  }
}))

import api from '../api/client'
import { useFavoritesStore } from './favorites'

describe('useFavoritesStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads favorite ids once per client', async () => {
    api.favoriteIds.mockResolvedValue([3, 8])
    const favorites = useFavoritesStore()

    await favorites.load('token-1')
    await favorites.load('token-1')

    expect(favorites.ids).toEqual([3, 8])
    expect(api.favoriteIds).toHaveBeenCalledTimes(1)
  })

  it('adds and removes a favorite', async () => {
    api.addFavorite.mockResolvedValue({ favorite: true })
    api.removeFavorite.mockResolvedValue({ favorite: false })
    const favorites = useFavoritesStore()

    expect(await favorites.toggle(9, 'token-1')).toBe(true)
    expect(favorites.has(9)).toBe(true)
    expect(await favorites.toggle(9, 'token-1')).toBe(false)
    expect(favorites.has(9)).toBe(false)
  })
})
