package com.pocketktv.app
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
class CatalogPagingTest {
 @Test fun categoriesAndPagination()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val name="paging-test-${System.nanoTime()}.db";val db=LibraryDb(context,name)
  try{
   db.startScan("10","/KTV/DVD歌库")
   db.commitPage(db.nextFolder()!!,DrivePage((1..205).map{DriveEntry("$it","10","歌手-歌曲${it.toString().padStart(3,'0')}-国语-流行-DVD伴唱.mpg",false,"pc$it",100)},205))
   db.writableDatabase.execSQL("UPDATE songs SET artist='刘若英+陈升' WHERE id='1'")
   db.writableDatabase.execSQL("UPDATE songs SET artist='刘若英' WHERE id='2'")
   db.indexArtists("1","刘若英+陈升");db.indexArtists("2","刘若英")
   assertEquals(2,db.search("",artist="刘若英").size)
   assertEquals(1,db.search("",artist="陈升").size)
   assertEquals(2,db.artistCounts().first{it.first=="刘若英"}.second)
   assertFalse(db.artistCounts().any{it.first.contains("+")})
   assertEquals(2,db.artistCounts("chinese").first{it.first=="刘若英"}.second)
   assertTrue(db.artistCounts("foreign").isEmpty())
   val first=db.search("",language="国语");val second=db.search("",language="国语",offset=200)
   assertEquals(200,first.size);assertEquals(5,second.size);assertTrue(first.map{it.id}.toSet().intersect(second.map{it.id}.toSet()).isEmpty())
   assertEquals(200,db.search("",category="版本：DVD伴唱").size);assertTrue(db.search("",language="粤语").isEmpty());assertEquals(200,db.search("",category="目录：DVD歌库").size)
   db.writableDatabase.execSQL("UPDATE songs SET language='英语' WHERE id='3'")
   assertEquals(1,db.search("",scope="foreign").size)
   assertEquals(1,db.search("",category="英文歌曲").size)
   assertTrue(db.search("",category="华语歌曲").all{it.language=="国语"})
   assertEquals(200,db.search("",scope="chinese").size)
  }finally{db.close();context.deleteDatabase(name)}
 }
}
