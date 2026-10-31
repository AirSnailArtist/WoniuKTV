package com.pocketktv.app
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** These tests only use disposable databases, never the installed user's catalog. */
class SmartCollectionsTest {
 @Test fun categoriesAreAccountScopedAndUseThePagingIndex()=runBlocking{
  val context=ApplicationProvider.getApplicationContext<Context>();val name="smart-collections-${UUID.randomUUID()}.db";val db=LibraryDb(context,name)
  try{
   db.setting("account","fixture-a",true);val song=db.rememberBilibili(BiliVideo("BV1GJ411x7h7",1234,42,"测试歌","歌手",120))
   val learning=SmartLibrary(db,"fixture-device");learning.ordered(song)
   assertEquals(1,SmartCollections.page(db,"bilibili","",6).getJSONArray("items").length())
   for(i in 0..1){val token=learning.start(song);val progress=SingingProgress();for(t in 0..70)progress.sample(t*1000L,t*1000L,true,false);learning.finish(token,song,progress,120000,"interrupted")}
   assertEquals(1,SmartCollections.page(db,"sung","",6).getJSONArray("items").length());assertEquals(1,SmartCollections.page(db,"frequent","",6).getJSONArray("items").length())
   db.favorite(song.id,true);assertEquals(1,SmartCollections.page(db,"manual","",6).getJSONArray("items").length())
   assertTrue(SmartCollections.prepare(db));db.setting("account","fixture-b",true);assertEquals(0,SmartCollections.page(db,"bilibili","",6).getJSONArray("items").length())
   val plan=db.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT song_id FROM smart_collection_items WHERE account=? AND kind=? AND sort_key>? ORDER BY sort_key LIMIT 7",arrayOf("fixture-a","sung","")).use{c->buildString{while(c.moveToNext())append(c.getString(3))}};assertTrue(plan.contains("smart_collection_page"))
  }finally{db.close();context.deleteDatabase(name)}
 }
 @Test fun migrationKeepsSongsAndCuratedPagesHaveUniqueSourcesAndRanks(){
  val context=ApplicationProvider.getApplicationContext<Context>();val name="curated-fixture-${UUID.randomUUID()}.db";var db=LibraryDb(context,name)
  try{
   val song=db.rememberBilibili(BiliVideo("BV1GJ411x7h7",2345,42,"保留的歌","歌手",120));db.writableDatabase.execSQL("DROP TABLE smart_collection_items");db.writableDatabase.execSQL("DROP TABLE cloud_playlist_tracks");db.writableDatabase.execSQL("DROP TABLE cloud_playlists");db.writableDatabase.version=6;db.close();db=LibraryDb(context,name)
   assertNotNull(db.song(song.id));assertEquals(0,MusicPlaylists(db).recent().length())
   val service=MusicCollections(context,db)
   for(id in listOf("era70","era80","era90","era00","cantonese","korean","japanese")){val page=service.page(id,8,9);assertEquals(500,page.getInt("total"));val items=page.getJSONArray("items");assertEquals(9,items.length());for(i in 0 until items.length()){assertEquals(id,items.getJSONObject(i).getString("source"));assertEquals(9+i,items.getJSONObject(i).getInt("rank"))};assertEquals(2,service.page(id,498,7).getJSONArray("items").length())}
  }finally{db.close();context.deleteDatabase(name)}
 }
}
