package com.pocketktv.app
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
class CounterpartTest {
 @Test fun counterpartPreservesQueueSlot()=runBlocking {
  val ctx=ApplicationProvider.getApplicationContext<Context>();val name="counterpart-${System.nanoTime()}.db";val db=LibraryDb(ctx,name)
  try{db.startScan("10","/KTV")
   db.commitPage(db.nextFolder()!!,DrivePage(listOf(DriveEntry("1","10","歌手-歌名-国语-流行-DVD原唱.mpg",false,"pc1",100),DriveEntry("2","10","歌手-歌名-国语-流行-DVD伴唱.mpg",false,"pc2",100)),2))
   assertEquals("2",db.counterpart(db.song("1")!!)!!.id);assertEquals("1",db.counterpart(db.song("2")!!)!!.id)
   val slot=db.enqueue("1");db.replaceQueueSong(slot,"2");assertEquals(slot,db.queue().first().queueId);assertEquals("2",db.queue().first().id)
  }finally{db.close();ctx.deleteDatabase(name)}
 }
}
