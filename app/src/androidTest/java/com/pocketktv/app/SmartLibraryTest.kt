package com.pocketktv.app
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class SmartLibraryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun database()=LibraryDb(context,"smart-test-${System.nanoTime()}.db").also{it.bindAccount("test")}
    private fun song(db:LibraryDb,id:String="1",title:String="Test",artist:String="Singer"):Song {
        db.writableDatabase.insertOrThrow("songs",null,ContentValues().apply{put("id",id);put("name","$title.mp4");put("title",title);put("title_key",ChartMatcher.titleKey(title));put("artist",artist);put("pick","p$id");put("path","/");put("search",title);put("language","国语");put("genre","流行")})
        db.indexArtists(id,artist);return db.song(id)!!
    }
    private fun cleanup(db:LibraryDb){val name=db.databaseName;db.close();context.deleteDatabase(name)}
    private fun complete(lib:SmartLibrary,song:Song){val token=lib.start(song);val progress=SingingProgress();for(i in 0..120)progress.sample(i*1000L,i*1000L,true,true);lib.finish(token,song,progress,120000,"natural");lib.finish(token,song,progress,120000,"natural")}
    private fun total(db:LibraryDb,field:String)=db.readableDatabase.rawQuery("SELECT data FROM smart_scores WHERE account='test' AND kind='song'",null).use{c->var n=0.0;while(c.moveToNext())n+=JSONObject(c.getString(0)).optDouble(field,0.0);n}
    @Test fun completionIsIdempotentAndPairedVersionsShareIdentity(){val db=database();try{val s=song(db);val lib=SmartLibrary(db,"device-a");lib.ordered(s);complete(lib,s);assertEquals(1.0,total(db,"complete"),0.0);assertEquals(1.0,total(db,"accomp"),0.0);assertEquals(SmartLibrary.workKey(s),SmartLibrary.workKey(s.copy(id="2",edition="伴奏")))}finally{cleanup(db)}}
    @Test fun catalogBackupMergesIndependentDevicesWithoutDoubleCounting()=runBlocking {
        val a=database();val b=database();val file=File(context.cacheDir,"smart-merge-${System.nanoTime()}.json")
        try{val sa=song(a);val sb=song(b);val la=SmartLibrary(a,"device-a");val lb=SmartLibrary(b,"device-b");complete(la,sa)
            CatalogCodec.export(a,file);repeat(2){file.inputStream().use{CatalogCodec.import(b,it,"test")}};assertEquals(1.0,total(b,"complete"),0.0)
            complete(lb,sb);CatalogCodec.export(b,file);repeat(2){file.inputStream().use{CatalogCodec.import(a,it,"test")}};assertEquals(2.0,total(a,"complete"),0.0)
            assertEquals(2,a.readableDatabase.rawQuery("SELECT COUNT(*) FROM smart_components WHERE kind='song'",null).use{it.moveToFirst();it.getInt(0)})
        }finally{file.delete();cleanup(a);cleanup(b)}
    }
    @Test fun skipAndErrorCannotBecomeComplete(){val db=database();try{val s=song(db);val lib=SmartLibrary(db,"device-a");val p=SingingProgress();for(i in 0..10)p.sample(i*1000L,i*1000L,true,false);lib.finish(lib.start(s),s,p,120000,"error");assertEquals(0.0,total(db,"skips"),0.0);lib.finish(lib.start(s),s,p,120000,"skip");assertEquals(1.0,total(db,"skips"),0.0);assertEquals(0.0,total(db,"complete"),0.0)}finally{cleanup(db)}}
    @Test fun queuedWorkAndDismissedWorkAreExcluded(){val db=database();try{val a=song(db);val b=song(db,"2","Other","Other Singer");val lib=SmartLibrary(db,"device-a");lib.ordered(a);db.enqueue(a.id);lib.dismiss(b);assertEquals(0,lib.recommendations(true).getJSONArray("items").length())}finally{cleanup(db)}}
    @Test fun migrationPreservesCatalogAndUsesRankingIndexes(){val db=database();val name=db.databaseName;song(db);db.close();SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{sql->for(table in listOf("smart_components","smart_scores","smart_sessions","smart_intents"))sql.execSQL("DROP TABLE $table");sql.version=4};val upgraded=LibraryDb(context,name);try{assertEquals(1,upgraded.count());val plan=upgraded.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT key,data FROM smart_scores WHERE account='test' AND kind='song' ORDER BY mass DESC,key LIMIT 80",null).use{c->buildList{while(c.moveToNext())add(c.getString(3))}.joinToString()};assertTrue(plan.contains("smart_rank"))}finally{cleanup(upgraded)}}
    @Test fun accountSwitchDoesNotExposeOtherAccountsPreferences(){val db=database();try{val lib=SmartLibrary(db,"device-a");lib.ordered(song(db));db.bindAccount("other");song(db,"2","Other");assertFalse(lib.recommendations().getBoolean("learning"));assertEquals(1,db.readableDatabase.rawQuery("SELECT COUNT(*) FROM smart_components WHERE account='test' AND kind='song'",null).use{it.moveToFirst();it.getInt(0)})}finally{cleanup(db)}}
    @Test fun corruptBackupRollsBackBothCatalogAndLearning()=runBlocking {
        val a=database();val b=database();val file=File(context.cacheDir,"smart-corrupt-${System.nanoTime()}.json")
        try{val sa=song(a);complete(SmartLibrary(a,"device-a"),sa);CatalogCodec.export(a,file)
            try{(file.readText()+" trailing").byteInputStream().use{CatalogCodec.import(b,it,"test")};fail("Invalid backup must fail")}catch(_:Exception){}
            assertEquals(0,b.count());assertEquals(0,b.readableDatabase.rawQuery("SELECT COUNT(*) FROM smart_components",null).use{it.moveToFirst();it.getInt(0)})
        }finally{file.delete();cleanup(a);cleanup(b)}
    }
    @Test fun rawHistoryGrowthDoesNotExpandRecommendationCandidates(){
        val db=database();try{val lib=SmartLibrary(db,"device-a");for(i in 1..60)song(db,i.toString(),"Song $i","Singer $i")
            val before=lib.recommendations(true);val sql=db.writableDatabase;sql.beginTransactionNonExclusive()
            try{val statement=sql.compileStatement("INSERT INTO smart_sessions(token,account,work_key,song_id,started_at,updated_at,outcome) VALUES(?,'test','unrelated','1',1,1,'error')")
                statement.use{for(i in 1..10000){it.bindString(1,"history-$i");it.executeInsert()}};sql.setTransactionSuccessful()
            }finally{sql.endTransaction()}
            db.setting("smart_explore_cursor","",true)
            val after=lib.recommendations(true);assertTrue(before.getInt("examined")<=340);assertTrue(after.getInt("examined")<=340);assertTrue(after.getJSONArray("items").length()>0)
            println("SMART_BUDGET beforeMs=${before.getLong("elapsedMs")} afterMs=${after.getLong("elapsedMs")} beforeCandidates=${before.getInt("examined")} afterCandidates=${after.getInt("examined")}")
        }finally{cleanup(db)}
    }

    @Test fun populatedPreferencesUseBoundedBatchReads(){
        val db=database();try{val lib=SmartLibrary(db,"device-a");val sql=db.writableDatabase;sql.beginTransactionNonExclusive()
            try{for(i in 1..400){val s=song(db,i.toString().padStart(3,'0'),"Song $i","Singer ${i/100}");if(i<=120)lib.ordered(s);if(i<=40)db.favorite(s.id,true)}
                for(i in listOf(1,101,201,301)){val id=i.toString().padStart(3,'0');val original=db.song(id)!!;val language=when(i){101->"粤语";201->"英语";else->"国语"};sql.update("songs",ContentValues().apply{put("language",language)},"id=?",arrayOf(id));complete(lib,original.copy(language=language))};sql.setTransactionSuccessful()
            }finally{sql.endTransaction()}
            val result=lib.recommendations(true);assertTrue(result.getInt("examined")>200);assertTrue(result.getInt("examined")<=340);assertTrue(result.getJSONArray("items").length() in 1..12)
            println("SMART_POPULATED candidates=${result.getInt("examined")} ms=${result.getLong("elapsedMs")}")
        }finally{cleanup(db)}
    }

    @Test fun unknownDurationLearnsAccompanimentWithoutClaimingCompletion(){val db=database();try{val s=song(db);val lib=SmartLibrary(db,"device-a");val token=lib.start(s);val p=SingingProgress();for(i in 0..120)p.sample(i*1000L,i*1000L,true,true);lib.finish(token,s,p,0,"natural");assertEquals(0.0,total(db,"complete"),0.0);assertEquals(0.0,total(db,"accomp"),0.0);assertEquals(120.0,total(db,"accomp_seconds"),0.0);assertTrue(total(db,"mass")>0)}finally{cleanup(db)}}

}
