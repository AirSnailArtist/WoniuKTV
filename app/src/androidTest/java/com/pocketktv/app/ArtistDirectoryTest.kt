package com.pocketktv.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Isolated databases only: these checks never change the user's library. */
class ArtistDirectoryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun database()=LibraryDb(context,"artist-directory-${System.nanoTime()}.db")
    private fun cleanup(db:LibraryDb){val name=db.databaseName;db.close();context.deleteDatabase(name)}
    private fun song(db:LibraryDb,id:String,artist:String,language:String="国语") {
        db.writableDatabase.insertOrThrow("songs",null,ContentValues().apply{
            put("id",id);put("name","$artist-Song.mp4");put("title","Song");put("artist",artist);put("language",language);put("pick","pick$id");put("path","/KTV");put("search",artist)
        });db.indexArtists(id,artist)
    }
    private fun page(db:LibraryDb,scope:String="chinese",after:String="",keyword:String="",initial:String="")=ArtistDirectory.page(db,scope,after,keyword,initial)
    private fun names(result:org.json.JSONObject)=result.getJSONArray("items").let{a->(0 until a.length()).map{a.getJSONObject(it).getString("name")}}
    @Test fun countsFollowCollaborationLanguageEditsDeletesAndRollback() {
        val db=database();try {
            song(db,"1","刘若英+陈升");song(db,"2","刘若英");song(db,"3","刘若英","英语")
            assertEquals(listOf("刘若英","陈升"),names(page(db)))
            assertEquals(2,db.artistCounts("chinese").first{it.first=="刘若英"}.second)
            db.writableDatabase.execSQL("UPDATE songs SET language='英语' WHERE id='1'")
            assertEquals(1,db.artistCounts("chinese").first().second)
            assertEquals(2,db.artistCounts("foreign").first{it.first=="刘若英"}.second)
            assertEquals(listOf("刘若英"),names(page(db,keyword="lry")))
            assertEquals(listOf("刘若英"),names(page(db,initial="L")))
            db.writableDatabase.beginTransactionNonExclusive()
            try{db.writableDatabase.delete("songs","id='2'",null)}finally{db.writableDatabase.endTransaction()}
            assertEquals(listOf("刘若英"),names(page(db)))
            db.writableDatabase.delete("songs","id='2'",null)
            assertTrue(names(page(db)).isEmpty());assertTrue(names(page(db,keyword="nobody")).isEmpty())
            db.bindAccount("first");db.bindAccount("second");assertTrue(names(page(db,"all")).isEmpty())
        }finally{cleanup(db)}
    }
    @Test fun cursorPagesAreStableAndSeekTheRankIndexWithUnrelatedGrowth() {
        val db=database();try {
            val sql=db.writableDatabase;sql.beginTransactionNonExclusive()
            try{for(i in 1..80)song(db,i.toString(),"Singer ${i.toString().padStart(4,'0')}");sql.setTransactionSuccessful()}finally{sql.endTransaction()}
            val first=page(db);val second=page(db,after=first.getString("after"));assertEquals(8,names(first).size);assertTrue(names(first).intersect(names(second).toSet()).isEmpty())
            val expected=names(second)
            sql.beginTransactionNonExclusive()
            try{for(i in 81..800)song(db,i.toString(),"Singer ${i.toString().padStart(4,'0')}");sql.setTransactionSuccessful()}finally{sql.endTransaction()}
            assertEquals(expected,names(page(db,after=first.getString("after"))))
            var cursor="";val seen=mutableSetOf<String>();var pages=0
            do {val result=page(db,after=cursor);val rows=names(result);assertTrue(rows.size<=8);for(name in rows)assertTrue(seen.add(name));cursor=result.getString("after");pages++;val more=result.getBoolean("hasMore");if(!more)break;assertTrue(pages<=100)}while(true)
            assertEquals(800,seen.size)
            val plan=sql.rawQuery("EXPLAIN QUERY PLAN SELECT name,song_count FROM artist_counts INDEXED BY artist_rank WHERE scope=? AND (rank,name)>(?,?) ORDER BY rank,name LIMIT 9",arrayOf("chinese","-1","Singer 0790")).use{c->buildList{while(c.moveToNext())add(c.getString(3))}.joinToString()}
            println("ARTIST_PAGE_PLAN $plan");assertTrue(plan.contains("artist_rank"));assertTrue(plan.contains("rank>?")||plan.contains("(rank,name)>"));assertFalse(plan.contains("TEMP B-TREE"))
        }finally{cleanup(db)}
    }
    @Test fun upgradePreservesLibraryAndPreparesSearchOffTheRequestThread()=runBlocking {
        val db=database();val name=db.databaseName;song(db,"1","周杰伦");db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{sql->
            for(trigger in listOf("artist_member_added","artist_member_removed","artist_song_removed","artist_language_changed","artist_name_prepared"))sql.execSQL("DROP TRIGGER $trigger")
            sql.execSQL("DROP TABLE artist_counts");sql.execSQL("DROP TABLE artist_names");sql.version=5
        }
        val upgraded=LibraryDb(context,name);try {
            assertEquals(1,upgraded.count());assertEquals(listOf("周杰伦"),names(page(upgraded)))
            assertTrue(page(upgraded,keyword="zjl").getBoolean("indexing"))
            ArtistDirectory.prepare(upgraded)
            assertEquals(listOf("周杰伦"),names(page(upgraded,keyword="zjl")))
            assertEquals(listOf("周杰伦"),names(page(upgraded,initial="Z")))
            assertFalse(page(upgraded,keyword="%_").getBoolean("hasMore"))
        }finally{cleanup(upgraded)}
    }
}
