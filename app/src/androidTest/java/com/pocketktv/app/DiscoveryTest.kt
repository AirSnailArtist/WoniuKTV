package com.pocketktv.app
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
class DiscoveryTest {
 @Test fun publicChartsAndExactArtistPhoto()=runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("discovery_e2e")=="true")
  val ctx=ApplicationProvider.getApplicationContext<Context>();val db=LibraryDb(ctx)
  try{val before=db.count();val service=PublicCharts(ctx,db);service.refresh(true);val state=JSONObject(service.snapshot);assertFalse(state.getBoolean("loading"))
   val sources=state.getJSONArray("sources");assertEquals(3,sources.length());for(i in 0 until sources.length())assertTrue(sources.getJSONObject(i).toString(),sources.getJSONObject(i).getInt("count")>0)
   val recommended=state.getJSONArray("recommendations");assertTrue(recommended.length()>0)
   for(i in 0 until recommended.length()){val song=db.song(recommended.getJSONObject(i).getJSONObject("song").getString("id"))!!;assertTrue(song.language in LibraryLanguage.chinese)}
   assertFalse(service.snapshot.contains("pick_code"));assertFalse(service.snapshot.contains("Cookie"))
   val photo=service.artistPhoto("刘德华");assertNotNull(photo);assertTrue(photo!!.size>1000)
   val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(photo,0,photo.size,options);assertTrue(options.outWidth>0)
   assertEquals(before,db.count())
   File(ctx.filesDir,"discovery-result.txt").writeText("PASS sources=${(0 until sources.length()).map{sources.getJSONObject(it).getString("id")+":"+sources.getJSONObject(it).getInt("count")}} recommendations=${recommended.length()} liudehua_photo=${options.outWidth}x${options.outHeight} count=$before\n")
  }finally{db.close()}
 }
}
