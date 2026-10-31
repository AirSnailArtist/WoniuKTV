package com.pocketktv.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Indexed read model of account-scoped learning; no history scan in a favorite-list request. */
object SmartCollections {
    fun create(sql:SQLiteDatabase){
        sql.execSQL("CREATE TABLE IF NOT EXISTS smart_collection_items(account TEXT NOT NULL,kind TEXT NOT NULL,work_key TEXT NOT NULL,song_id TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,count INTEGER NOT NULL,completed INTEGER NOT NULL,last_at INTEGER NOT NULL,sort_key TEXT NOT NULL,PRIMARY KEY(account,kind,work_key))")
        sql.execSQL("CREATE INDEX IF NOT EXISTS smart_collection_page ON smart_collection_items(account,kind,sort_key)")
    }
    fun sync(db:LibraryDb,account:String,key:String,data:JSONObject){
        val id=data.optString("song_id");if(id.isBlank())return
        val plays=data.optLong("plays");val orders=data.optLong("orders");val completed=data.optLong("complete");val seconds=data.optDouble("seconds")
        val provider=data.optString("provider").ifBlank{db.song(id)?.metadataProvider.orEmpty()}
        val sung=seconds>=30||completed>0||data.optLong("accomp")>0
        for(kind in listOf("sung","frequent","bilibili")){
            val include=when(kind){"sung"->sung;"frequent"->sung&&plays>=2&&(seconds>=60||completed>=2);else->provider=="bilibili"&&orders>0}
            if(!include){db.writableDatabase.delete("smart_collection_items","account=? AND kind=? AND work_key=?",arrayOf(account,kind,key));continue}
            val last=(if(kind=="bilibili")data.optLong("last_order") else data.optLong("last_play")).coerceAtLeast(0)
            val count=(if(kind=="bilibili")orders else plays).coerceAtLeast(0)
            val priority=if(kind=="frequent")count else last
            val sort=String.format(Locale.ROOT,"%019d:%019d:%s",Long.MAX_VALUE-priority,Long.MAX_VALUE-last,key)
            db.writableDatabase.insertWithOnConflict("smart_collection_items",null,ContentValues().apply{put("account",account);put("kind",kind);put("work_key",key);put("song_id",id);put("title",data.optString("title"));put("artist",data.optString("artist"));put("count",count);put("completed",completed);put("last_at",last);put("sort_key",sort)},SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
    /** One migration slice <=1000 scores in 100-row transactions; durable key cursor, <=3 seconds. */
    suspend fun prepare(db:LibraryDb):Boolean {
        val account=db.setting("account");if(account.isBlank())return true
        val prefix="smart_collection_v1:"+account;if(db.setting(prefix+":ready")=="true")return true
        val started=android.os.SystemClock.elapsedRealtime()
        repeat(10){
            currentCoroutineContext().ensureActive();val sql=db.writableDatabase;sql.beginTransactionNonExclusive()
            var complete=false
            try{
                val after=db.setting(prefix+":after")
                val rows=sql.rawQuery("SELECT key,data FROM smart_scores WHERE account=? AND kind='song' AND key>? ORDER BY key LIMIT 101",arrayOf(account,after)).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
                rows.take(100).forEach{(key,text)->sync(db,account,key,JSONObject(text))}
                rows.take(100).lastOrNull()?.let{db.setting(prefix+":after",it.first,true)}
                complete=rows.size<=100;if(complete)db.setting(prefix+":ready","true",true)
                sql.setTransactionSuccessful()
            }finally{sql.endTransaction()}
            if(complete)return true
            if(android.os.SystemClock.elapsedRealtime()-started>=3000)return false
        }
        return false
    }
    fun page(db:LibraryDb,kind:String,after:String,limit:Int):JSONObject {
        require(kind in listOf("manual","sung","frequent","bilibili")&&limit in 1..50)
        val out=JSONArray();var cursor="";var more=false
        if(kind=="manual"){
            require(after.isEmpty()||after.matches(Regex("[0-9]{1,30}")))
            val rows=db.readableDatabase.rawQuery("SELECT id FROM songs WHERE favorite=1 AND id>? ORDER BY id LIMIT ?",arrayOf(after,(limit+1).toString())).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            more=rows.size>limit
            rows.take(limit).forEach{id->db.song(id)?.let{song->out.put(songItem(db,song).put("reason","手动收藏"));cursor=id}}
        }else{
            require(after.isEmpty()||after.matches(Regex("[0-9]{19}:[0-9]{19}:[a-f0-9]{64}")))
            val account=db.setting("account")
            val rows=db.readableDatabase.rawQuery("SELECT song_id,title,artist,count,completed,sort_key,work_key FROM smart_collection_items WHERE account=? AND kind=? AND sort_key>? ORDER BY sort_key LIMIT ?",arrayOf(account,kind,after,(limit+1).toString())).use{c->buildList{while(c.moveToNext())add(listOf(c.getString(0),c.getString(1),c.getString(2),c.getLong(3).toString(),c.getLong(4).toString(),c.getString(5),c.getString(6)))}}
            more=rows.size>limit
            rows.take(limit).forEach{r->val song=db.song(r[0]);val item=if(song!=null)songItem(db,song) else CatalogMatch(db).item(r[1],ArtistNames.members(r[2]));out.put(item.put("key",r[6]).put("reason",(if(kind=="bilibili")"点播" else "播放")+r[3]+"次 · 完整唱完"+r[4]+"次"));cursor=r[5]}
        }
        val account=db.setting("account");val warming=kind!="manual"&&account.isNotEmpty()&&db.setting("smart_collection_v1:$account:ready")!="true"
        return JSONObject().put("items",out).put("after",cursor).put("hasMore",more).put("warming",warming).put("warning",if(warming)"正在整理以前的演唱记录" else "")
    }
    private fun songItem(db:LibraryDb,song:Song)=JSONObject().put("title",song.title).put("artists",JSONArray(ArtistNames.members(song.artist))).put("available",true).put("song",JSONObject().put("id",song.id).put("title",song.title).put("artist",song.artist).put("source",if(song.metadataProvider=="bilibili")"bilibili" else song.metadataProvider.takeIf{it in DomesticDrive.labels}?:"115").put("views",if(song.metadataProvider=="bilibili")db.setting("bili_views:"+song.id).toLongOrNull()?:-1 else -1))
}
