package com.pocketktv.app

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Disposable databases only. Never exports the user's installed catalog. */
class PortableBackupTest {
 @Test fun roundTripKeepsPlaylistsFavoritesAndSingingAndIsIdempotent()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val names=listOf("portable-source-${UUID.randomUUID()}.db","portable-target-${UUID.randomUUID()}.db")
  val a=LibraryDb(context,names[0]);val b=LibraryDb(context,names[1]);val file=File.createTempFile("fixture-backup-",".json",context.cacheDir)
  try {
   for(db in listOf(a,b)){db.setting("account","fixture-owner",true);db.setting("scan_done","1",true)}
   val song=a.rememberBilibili(BiliVideo("BV1GJ411x7h7",6789,42,"测试歌","测试歌手",120));a.favorite(song.id,true)
   a.setting("bili_danmaku","false",true);a.setting("loudness_boost","true",true);a.setting("catalog_backup_pending","secret-receipt",true)
   a.writableDatabase.insertOrThrow("cloud_playlists",null,ContentValues().apply{put("ref","netease:123");put("title","测试收藏歌单");put("total",2);put("loaded",123L)})
   for(i in 0..1)a.writableDatabase.insertOrThrow("cloud_playlist_tracks",null,ContentValues().apply{put("ref","netease:123");put("position",i);put("song_id",(100+i).toString())})
   val learning=SmartLibrary(a,"fixture-device");learning.ordered(song)
   for(i in 0..1){val token=learning.start(song);val progress=SingingProgress();for(t in 0..70)progress.sample(t*1000L,t*1000L,true,false);learning.finish(token,song,progress,120000,"interrupted")}
   assertEquals(1,CatalogCodec.export(a,file,"fixture-owner"))
   val json=JSONObject(file.readText());assertEquals(4,json.getInt("version"));assertEquals("窝牛KTV",json.getString("app_name"));assertFalse(file.readText().contains("secret-receipt"));assertFalse(json.getJSONObject("personal_data").getJSONObject("preferences").has("loudness_boost"))
   repeat(2){file.inputStream().use{CatalogCodec.import(b,it,"fixture-owner")}}
   assertTrue(b.song(song.id)!!.favorite);assertEquals("bilibili",b.song(song.id)!!.metadataProvider);assertEquals("false",b.setting("bili_danmaku"));assertEquals("",b.setting("loudness_boost"))
   assertEquals(1,MusicPlaylists(b).recent().length());assertEquals(2,b.readableDatabase.rawQuery("SELECT song_id FROM cloud_playlist_tracks WHERE ref=?",arrayOf("netease:123")).use{it.count})
   val before=SmartCollections.page(b,"frequent","",6).getJSONArray("items").toString();assertEquals(1,SmartCollections.page(b,"sung","",6).getJSONArray("items").length())
   file.inputStream().use{CatalogCodec.import(b,it,"fixture-owner")};assertEquals(before,SmartCollections.page(b,"frequent","",6).getJSONArray("items").toString())
   // Old v3 remains readable, account mismatch leaves local data intact.
   val legacy=JSONObject(file.readText()).put("version",3);legacy.remove("personal_data");legacy.toString().byteInputStream().use{CatalogCodec.import(b,it,"fixture-owner")}
   val wrong=JSONObject(file.readText()).put("account_id","another-owner");var rejected=false
   try{wrong.toString().byteInputStream().use{CatalogCodec.import(b,it,"fixture-owner")}}catch(e:IllegalArgumentException){rejected=true}
   assertTrue(rejected);assertNotNull(b.song(song.id));assertEquals(1,MusicPlaylists(b).recent().length())
   assertTrue(CatalogRepository.backupFileName().matches(Regex("窝牛KTV-完整备份-[0-9-]+_[0-9-]+-v[0-9.]+\\.json")))
  } finally {a.close();b.close();names.forEach{context.deleteDatabase(it)};file.delete()}
 }
}
