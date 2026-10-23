package com.pocketktv.app
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import java.io.File
class CatalogMaintenanceTest {
 @Test fun organizeBackupAndRestore()=runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("maintenance")=="true")
  val ctx=ApplicationProvider.getApplicationContext<Context>();val db=LibraryDb(ctx);val drive=Drive115(SecretStore(ctx));val report=File(ctx.filesDir,"catalog-maintenance.txt")
  val isolated="restore-verify-${System.nanoTime()}.db";val restored=LibraryDb(ctx,isolated)
  val source=db.readableDatabase.rawQuery("SELECT id,name,pick,path,favorite,audio_mode,accompaniment FROM songs ORDER BY id",null).use{c->buildList{while(c.moveToNext())add((0..6).map{c.getString(it)})}}
  report.writeText("START count=${db.count()}\n")
  try{
   val service=CatalogRepository(db,drive,ctx.cacheDir)
   service.organize{done,total->report.appendText("ORGANIZE $done/$total\n")}
   val after=db.readableDatabase.rawQuery("SELECT id,name,pick,path,favorite,audio_mode,accompaniment FROM songs ORDER BY id",null).use{c->buildList{while(c.moveToNext())add((0..6).map{c.getString(it)})}}
   assertEquals(source,after)
   assertEquals(88,db.search("",artist="刘若英").size)
   assertFalse(db.artistCounts().any{it.first=="刘若英+陈升"})
   val languageCounts=db.readableDatabase.rawQuery("SELECT language,COUNT(*) FROM songs GROUP BY language ORDER BY COUNT(*) DESC",null).use{c->buildList{while(c.moveToNext())add(c.getString(0)+":"+c.getInt(1))}}
   report.appendText("SOURCES_PRESERVED count=${source.size} LIU_RUOYING=88 ARTISTS=${db.artistCounts().size} CHINESE_ARTISTS=${db.artistCounts("chinese").size} LANGUAGES=$languageCounts\n")
   report.appendText("ORGANIZED count=${db.count()} version=${db.setting("metadata_rule_version")}\n")
   val path=service.backup{report.appendText(it+"\n")};report.appendText("CLOUD $path\n")
   restored.bindAccount(drive.accountId)
   val count=CatalogRepository(restored,drive,ctx.cacheDir).restore(db.setting("catalog_backup_pick"))
   assertEquals(db.count(),count);assertEquals(db.count(),restored.count());assertEquals(LibraryMetadataPipeline.VERSION,restored.setting("metadata_rule_version"))
   for(song in db.search("").take(30)){val other=restored.song(song.id)!!;assertEquals(song.title,other.title);assertEquals(song.language,other.language);assertEquals(song.genre,other.genre);assertEquals(song.edition,other.edition)}
   assertEquals(db.artistCounts(),restored.artistCounts())
   report.appendText("RESTORE_OK count=$count\nCOMPLETE\n")
  }catch(e:Exception){report.appendText("FAILED ${e.javaClass.simpleName}: ${e.message}\n");throw e}
  finally{db.close();restored.close();ctx.deleteDatabase(isolated)}
 }
}
