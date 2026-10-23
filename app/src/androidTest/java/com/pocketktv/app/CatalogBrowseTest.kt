package com.pocketktv.app
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
class CatalogBrowseTest {
 @Test fun liveChineseAndCollaboratorBrowse(){
  val inst=InstrumentationRegistry.getInstrumentation();assumeTrue(InstrumentationRegistry.getArguments().getString("catalog_verify")=="true")
  val ctx=ApplicationProvider.getApplicationContext<Context>();val activity=inst.startActivitySync(Intent(ctx,SongbookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SongbookActivity
  fun get(path:String)=JSONObject(activity.LocalBridge().request(path,"GET",""))
  for(scope in listOf("chinese","all","foreign")){
   val result=get("/browse/artists?scope=$scope&keyword="+android.net.Uri.encode("刘若英"));assertTrue(result.getBoolean("ok"));val rows=result.getJSONObject("data").getJSONArray("items")
   if(scope=="chinese"||scope=="all"){
    val liu=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.getString("name")=="刘若英"};assertEquals(1,liu.size);assertEquals(88,liu.first().getInt("songCount"))
   }
   assertFalse((0 until rows.length()).any{rows.getJSONObject(it).getString("name").startsWith("刘若英+")})
  }
  val songs=get("/browse/songs?artist="+android.net.Uri.encode("刘若英")+"&scope=chinese").getJSONArray("data");assertEquals(88,songs.length())
  val english=get("/browse/songs?tag="+android.net.Uri.encode("英文歌曲")).getJSONArray("data");assertTrue(english.length()>0);assertTrue((0 until english.length()).all{english.getJSONObject(it).getString("language")=="英语"})
 }
}
