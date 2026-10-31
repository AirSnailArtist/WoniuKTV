package com.pocketktv.app
import org.junit.Assert.*
import org.junit.Test
class MusicPlaylistsTest {
 @Test fun onlyRecognizesOfficialPlaylistLinksAndNeverTreatsSongsAsPlaylists(){
  assertEquals("netease:123",MusicPlaylists.canonical("https://music.163.com/playlist?id=123"))
  assertEquals("netease:123",MusicPlaylists.canonical("https://music.163.com/#/playlist?id=123"))
  assertEquals("qq:123",MusicPlaylists.canonical("https://y.qq.com/n/ryqq/playlist/123"))
  assertNull(MusicPlaylists.canonical("https://music.163.com/song?id=123"))
  assertNull(MusicPlaylists.canonical("https://music.163.com.attacker.example/playlist?id=123"))
  assertNull(MusicPlaylists.canonical("https://music.163.com:8443/playlist?id=123"))
  assertNull(MusicPlaylists.canonical("http://127.0.0.1/playlist?id=123"))
 }
 @Test fun videoViewsNeverInventAnUnavailableCount(){assertEquals(25000L,BilibiliClient.viewCount("2.5万"));assertEquals(123456L,BilibiliClient.viewCount(123456));assertEquals(-1L,BilibiliClient.viewCount(null));assertEquals(-1L,BilibiliClient.viewCount("暂不可用"))}
}
