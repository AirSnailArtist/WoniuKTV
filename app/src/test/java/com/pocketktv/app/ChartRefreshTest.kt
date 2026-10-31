package com.pocketktv.app
import org.junit.Assert.*
import org.junit.Test
class ChartRefreshTest {
 @Test fun successfulCacheExpiresHourlyAndForcedRefreshIsImmediate(){
  val f=ChartFeed("test","test","","",listOf(ChartEntry("test",1,"歌",listOf("人"))),fetched=1_000_000)
  assertFalse(chartRefreshDue(f,1_000_000,1_100_000));assertTrue(chartRefreshDue(f,1_000_000,4_600_000));assertTrue(chartRefreshDue(f,1_000_000,1_100_000,true))
 }
 @Test fun failedOrEmptyFeedRetriesAfterFiveMinutesInsteadOfBecomingFresh(){
  val f=ChartFeed("test","test","","",emptyList(),fetched=1_000_000,error="失败")
  assertFalse(chartRefreshDue(f,1_000_000,1_100_000));assertTrue(chartRefreshDue(f,1_000_000,1_300_000));assertTrue(chartRefreshDue(null,0,1_000_000))
 }
}
