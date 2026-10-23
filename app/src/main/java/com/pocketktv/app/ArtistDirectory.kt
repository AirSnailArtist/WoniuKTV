package com.pocketktv.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Counts change with the catalog transaction; browsing never regroups the song library. */
object ArtistDirectory {
    private fun scope(language:String)="CASE WHEN ${LibraryLanguage.condition("chinese",language)} THEN 'chinese' WHEN ${LibraryLanguage.condition("foreign",language)} THEN 'foreign' ELSE 'unknown' END"
    fun create(sql:SQLiteDatabase) {
        sql.execSQL("CREATE TABLE IF NOT EXISTS artist_names(name TEXT PRIMARY KEY,initial TEXT NOT NULL DEFAULT '#',search TEXT NOT NULL DEFAULT '',ready INTEGER NOT NULL DEFAULT 0)")
        sql.execSQL("CREATE INDEX IF NOT EXISTS artist_name_pending ON artist_names(ready,name)")
        sql.execSQL("CREATE TABLE IF NOT EXISTS artist_counts(scope TEXT NOT NULL,name TEXT NOT NULL,song_count INTEGER NOT NULL,rank INTEGER NOT NULL DEFAULT 0,initial TEXT NOT NULL DEFAULT '#',search TEXT NOT NULL DEFAULT '',PRIMARY KEY(scope,name))")
        sql.execSQL("CREATE INDEX IF NOT EXISTS artist_rank ON artist_counts(scope,rank,name)")
        sql.execSQL("CREATE INDEX IF NOT EXISTS artist_initial_rank ON artist_counts(scope,initial,rank,name)")
        // Migration only. Metadata is filled in resumable 100-name batches off the UI thread.
        sql.execSQL("INSERT OR IGNORE INTO artist_counts(scope,name,song_count,rank) SELECT 'all',a.artist,COUNT(*),-COUNT(*) FROM song_artists a JOIN songs s ON s.id=a.song_id GROUP BY a.artist")
        for (group in listOf("chinese","foreign","unknown")) sql.execSQL("INSERT OR IGNORE INTO artist_counts(scope,name,song_count,rank) SELECT '$group',a.artist,COUNT(*),-COUNT(*) FROM song_artists a JOIN songs s ON s.id=a.song_id WHERE ${scope("s.language")}='$group' GROUP BY a.artist")
        sql.execSQL("INSERT OR IGNORE INTO artist_names(name) SELECT name FROM artist_counts WHERE scope='all'")
        val songScope=scope("(SELECT language FROM songs WHERE id=NEW.song_id)")
        val oldScope=scope("(SELECT language FROM songs WHERE id=OLD.song_id)")
        sql.execSQL("""CREATE TRIGGER IF NOT EXISTS artist_member_added AFTER INSERT ON song_artists BEGIN
            INSERT OR IGNORE INTO artist_names(name) VALUES(NEW.artist);
            INSERT OR IGNORE INTO artist_counts(scope,name,song_count,initial,search) SELECT 'all',name,0,initial,search FROM artist_names WHERE name=NEW.artist;
            INSERT OR IGNORE INTO artist_counts(scope,name,song_count,initial,search) SELECT $songScope,name,0,initial,search FROM artist_names WHERE name=NEW.artist;
            UPDATE artist_counts SET song_count=song_count+1,rank=rank-1 WHERE name=NEW.artist AND scope IN ('all',$songScope);
        END""")
        sql.execSQL("""CREATE TRIGGER IF NOT EXISTS artist_member_removed AFTER DELETE ON song_artists BEGIN
            UPDATE artist_counts SET song_count=song_count-1,rank=rank+1 WHERE name=OLD.artist AND scope IN ('all',$oldScope);
            DELETE FROM artist_counts WHERE name=OLD.artist AND song_count<=0;
        END""")
        sql.execSQL("""CREATE TRIGGER IF NOT EXISTS artist_song_removed BEFORE DELETE ON songs BEGIN
            DELETE FROM song_artists WHERE song_id=OLD.id;
        END""")
        sql.execSQL("""CREATE TRIGGER IF NOT EXISTS artist_language_changed AFTER UPDATE OF language ON songs WHEN OLD.language<>NEW.language BEGIN
            UPDATE artist_counts SET song_count=song_count-1,rank=rank+1 WHERE scope=${scope("OLD.language")} AND name IN (SELECT artist FROM song_artists WHERE song_id=NEW.id);
            DELETE FROM artist_counts WHERE scope=${scope("OLD.language")} AND song_count<=0 AND name IN (SELECT artist FROM song_artists WHERE song_id=NEW.id);
            INSERT OR IGNORE INTO artist_counts(scope,name,song_count,initial,search) SELECT ${scope("NEW.language")},name,0,initial,search FROM artist_names WHERE name IN (SELECT artist FROM song_artists WHERE song_id=NEW.id);
            UPDATE artist_counts SET song_count=song_count+1,rank=rank-1 WHERE scope=${scope("NEW.language")} AND name IN (SELECT artist FROM song_artists WHERE song_id=NEW.id);
        END""")
        sql.execSQL("""CREATE TRIGGER IF NOT EXISTS artist_name_prepared AFTER UPDATE OF initial,search ON artist_names BEGIN
            UPDATE artist_counts SET initial=NEW.initial,search=NEW.search WHERE name=NEW.name;
        END""")
        // The primary key starts with scope; name-keyed trigger updates need this separate index.
        sql.execSQL("CREATE INDEX IF NOT EXISTS artist_counts_name ON artist_counts(name,scope)")
    }
    fun indexName(sql:SQLiteDatabase,name:String) {
        val ready=sql.rawQuery("SELECT ready FROM artist_names WHERE name=?",arrayOf(name)).use{it.moveToFirst()&&it.getInt(0)==1}
        if(ready)return
        val py=SongNames.pinyin(name)
        val values=ContentValues().apply{put("name",name);put("initial",py.second.take(1).uppercase(Locale.ROOT).takeIf{it.matches(Regex("[A-Z]"))}?:"#");put("search",("$name ${py.first} ${py.second}").lowercase(Locale.ROOT));put("ready",1)}
        sql.insertWithOnConflict("artist_names",null,values,SQLiteDatabase.CONFLICT_IGNORE)
        sql.update("artist_names",values,"name=?",arrayOf(name))
    }
    suspend fun prepare(db:LibraryDb) {
        val deadline=android.os.SystemClock.elapsedRealtime()+120_000
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            currentCoroutineContext().ensureActive()
            val names=db.readableDatabase.rawQuery("SELECT name FROM artist_names WHERE ready=0 ORDER BY name LIMIT 100",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            if(names.isEmpty())return
            val sql=db.writableDatabase;sql.beginTransactionNonExclusive()
            try{names.forEach{indexName(sql,it)};sql.setTransactionSuccessful()}finally{sql.endTransaction()}
            kotlinx.coroutines.yield()
        }
    }
    fun page(db:LibraryDb,scope:String,after:String,keyword:String,initial:String):JSONObject = db.boundedRead { signal->
        require(scope in listOf("all","chinese","foreign"));require(keyword.length<=80&&after.length<=512)
        require(initial.isEmpty()||initial=="#"||initial.matches(Regex("[A-Z]")))
        if(keyword.isNotBlank()||initial.isNotEmpty()) {
            val pending=db.readableDatabase.rawQuery("SELECT 1 FROM artist_names WHERE ready=0 LIMIT 1",null,signal).use{it.moveToFirst()}
            if(pending)return@boundedRead JSONObject().put("indexing",true).put("items",JSONArray()).put("hasMore",false)
        }
        val where=mutableListOf("scope=?");val args=mutableListOf(scope)
        if(initial.isNotEmpty()){where.add("initial=?");args.add(initial)}
        if(keyword.isNotBlank()){where.add("search LIKE ? ESCAPE '\\'");args.add("%"+keyword.trim().lowercase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%")}
        val index=if(initial.isEmpty())"artist_rank" else "artist_initial_rank"
        if(after.isNotEmpty()) {
            val cursor=JSONObject(after);val count=cursor.getInt("count");val name=cursor.getString("name");require(count>0&&name.length<=4096)
            where.add("(rank,name)>(?,?)");args.add((-count).toString());args.add(name)
        }
        val rows=db.readableDatabase.rawQuery("SELECT name,song_count,initial FROM artist_counts INDEXED BY $index WHERE "+where.joinToString(" AND ")+" ORDER BY rank,name LIMIT 9",args.toTypedArray(),signal).use{c->buildList{while(c.moveToNext())add(Triple(c.getString(0),c.getInt(1),c.getString(2)))}}
        val items=JSONArray();rows.take(8).forEach{(name,count,letter)->items.put(JSONObject().put("name",name).put("songCount",count).put("initial",letter).put("coverUrl","https://app.woniu.local/artist-art?name="+android.net.Uri.encode(name)))}
        val cursor=rows.take(8).lastOrNull()?.let{JSONObject().put("name",it.first).put("count",it.second).toString()}.orEmpty()
        JSONObject().put("items",items).put("hasMore",rows.size>8).put("after",cursor).put("indexing",false)
    }
}
