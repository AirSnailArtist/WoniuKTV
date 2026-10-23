package com.pocketktv.app
import org.junit.Assert.*
import org.junit.Test
class ChartMatcherTest {
 private fun song(id:String,artist:String,favorite:Boolean=false)=Song(id,"我爱你.mpg","我爱你",artist,"p","/",favorite,"tracks",1,language="国语")
 @Test fun sameTitleDoesNotGuessOriginalSinger(){
  val candidates=listOf(song("1","刘德华"),song("2","王菲"))
  assertNull(ChartMatcher.select(candidates,listOf("新翻唱歌手")))
  assertEquals("2",ChartMatcher.select(candidates,listOf("王菲"))!!.id)
  assertEquals("1",ChartMatcher.select(listOf(song("1","刘德华+张学友")),listOf("张学友"))!!.id)
 }
 @Test fun onlyVersionMarkersAreRemoved(){
  assertEquals(ChartMatcher.titleKey("我爱你"),ChartMatcher.titleKey("我爱你 (Live)"))
  assertEquals(ChartMatcher.titleKey("我爱你"),ChartMatcher.titleKey("我爱你 (cover 王菲)"))
  assertNotEquals(ChartMatcher.titleKey("我爱你"),ChartMatcher.titleKey("我爱你中国"))
  assertNotEquals(ChartMatcher.titleKey("我爱你"),ChartMatcher.titleKey("我爱你(中国)"))
 }
}

