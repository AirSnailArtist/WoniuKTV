package com.pocketktv.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonWriter
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

/** Local, account-scoped learning. Components merge by device+key+revision, never by adding backups. */
class SmartLibrary(private val db:LibraryDb, private val device:String=db.learningDeviceId, private val now:()->Long=System::currentTimeMillis) {
    companion object {
        private val processOwner=UUID.randomUUID().toString()
        const val MAX_COMPONENTS=50_000
        val origins=setOf("replay","unfinished","artist","style","explore")
        private val kinds=setOf("song","artist","style","arm","hide")
        private const val EPOCH=1767225600000L // Fixed epoch keeps exponential-decay ordering indexable.
        fun massClock(time:Long)=2.0.pow((time-EPOCH).toDouble()/(45*86_400_000L))
        fun workKey(song:Song):String {
            val artists=ArtistNames.members(song.artist).map(ChartMatcher::artistKey).sorted().joinToString("\u001f")
            val digest=MessageDigest.getInstance("SHA-256").digest((ChartMatcher.titleKey(song.title)+"\u0000"+artists).toByteArray())
            val alphabet="0123456789abcdef";val hex=CharArray(digest.size*2)
            digest.forEachIndexed{i,b->val value=b.toInt() and 255;hex[i*2]=alphabet[value ushr 4];hex[i*2+1]=alphabet[value and 15]}
            return String(hex)
        }
        fun create(sql:SQLiteDatabase) {
            sql.execSQL("CREATE TABLE IF NOT EXISTS smart_components(account TEXT NOT NULL,device TEXT NOT NULL,kind TEXT NOT NULL,key TEXT NOT NULL,revision INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(account,device,kind,key))")
            sql.execSQL("CREATE INDEX IF NOT EXISTS smart_component_key ON smart_components(account,kind,key,device)")
            sql.execSQL("CREATE TABLE IF NOT EXISTS smart_scores(account TEXT NOT NULL,kind TEXT NOT NULL,key TEXT NOT NULL,mass REAL NOT NULL,updated_at INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(account,kind,key))")
            sql.execSQL("CREATE INDEX IF NOT EXISTS smart_rank ON smart_scores(account,kind,mass DESC,key)")
            sql.execSQL("CREATE INDEX IF NOT EXISTS smart_recent ON smart_scores(account,kind,updated_at DESC,key)")
            sql.execSQL("CREATE TABLE IF NOT EXISTS smart_sessions(seq INTEGER PRIMARY KEY AUTOINCREMENT,token TEXT UNIQUE NOT NULL,account TEXT NOT NULL,work_key TEXT NOT NULL,song_id TEXT NOT NULL,started_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,outcome TEXT NOT NULL,played_ms INTEGER NOT NULL DEFAULT 0,accompaniment_ms INTEGER NOT NULL DEFAULT 0,coverage_ms INTEGER NOT NULL DEFAULT 0,duration_ms INTEGER NOT NULL DEFAULT 0,origin TEXT NOT NULL DEFAULT '',owner TEXT NOT NULL DEFAULT '')")
            sql.execSQL("CREATE INDEX IF NOT EXISTS smart_pending ON smart_sessions(account,outcome,seq)")
            sql.execSQL("CREATE INDEX IF NOT EXISTS smart_session_history ON smart_sessions(account,started_at DESC,seq DESC)")
            sql.execSQL("CREATE TABLE IF NOT EXISTS smart_intents(account TEXT NOT NULL,work_key TEXT NOT NULL,origin TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(account,work_key))")
            sql.execSQL("CREATE INDEX IF NOT EXISTS smart_catalog_style ON songs(language,genre,id)")
            sql.execSQL("CREATE INDEX IF NOT EXISTS smart_favorites ON songs(favorite,id)")
            sql.execSQL("CREATE INDEX IF NOT EXISTS queue_song_idx ON queue(song_id)")
        }
    }
    private val sql get()=db.writableDatabase
    private fun account()=db.setting("account").also{require(it.isNotBlank()){ "请先登录并恢复曲库" }}
    private fun <T> transaction(action:()->T):T { val d=sql;d.beginTransactionNonExclusive();return try{action().also{d.setTransactionSuccessful()}}finally{d.endTransaction()} }
    private fun score(account:String,kind:String,key:String):JSONObject = sql.rawQuery("SELECT data FROM smart_scores WHERE account=? AND kind=? AND key=?",arrayOf(account,kind,key)).use{if(it.moveToFirst())JSONObject(it.getString(0)) else JSONObject()}
    private fun local(account:String,kind:String,key:String):Pair<Long,JSONObject> = sql.rawQuery("SELECT revision,data FROM smart_components WHERE account=? AND device=? AND kind=? AND key=?",arrayOf(account,device,kind,key)).use{if(it.moveToFirst())it.getLong(0) to JSONObject(it.getString(1)) else 0L to JSONObject()}
    private fun aggregate(account:String,kind:String,key:String) {
        val parts=sql.rawQuery("SELECT data FROM smart_components WHERE account=? AND kind=? AND key=? LIMIT 33",arrayOf(account,kind,key)).use{c->buildList{while(c.moveToNext())add(JSONObject(c.getString(0)))}}
        require(parts.size<=32){"同一偏好的来源设备超过32台，请整理备份"}
        val result=JSONObject(parts.maxByOrNull{it.optLong("updated")}?.toString()?:"{}")
        for(field in listOf("mass","orders","plays","complete","accomp","skips","success","trials","seconds","accomp_seconds"))result.put(field,parts.sumOf{it.optDouble(field,0.0)})
        result.put("last_order",parts.maxOfOrNull{it.optLong("last_order")}?:0L).put("last_play",parts.maxOfOrNull{it.optLong("last_play")}?:0L)
        result.put("until",parts.maxOfOrNull{it.optLong("until")}?:0L)
        val values=ContentValues().apply{put("account",account);put("kind",kind);put("key",key);put("mass",result.optDouble("mass",0.0));put("updated_at",result.optLong("updated"));put("data",result.toString())}
        sql.insertWithOnConflict("smart_scores",null,values,SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun mutate(account:String,kind:String,key:String,change:(JSONObject)->Unit) {
        val (revision,data)=local(account,kind,key);change(data);data.put("updated",now())
        require(data.toString().length<=8192)
        val values=ContentValues().apply{put("account",account);put("device",device);put("kind",kind);put("key",key);put("revision",revision+1);put("data",data.toString())}
        sql.insertWithOnConflict("smart_components",null,values,SQLiteDatabase.CONFLICT_REPLACE);aggregate(account,kind,key)
    }
    private fun JSONObject.increment(key:String,delta:Double=1.0){put(key,optDouble(key,0.0)+delta)}
    private fun reward(data:JSONObject,points:Double,cap:Double=12.0) {
        val day=now()/86_400_000L
        if(data.optLong("day",-1)!=day){data.put("day",day);data.put("day_points",0.0)}
        val earned=if(points>0)minOf(points,(cap-data.optDouble("day_points",0.0)).coerceAtLeast(0.0)) else maxOf(points,-1.0)
        data.put("mass",data.optDouble("mass",0.0)+earned*massClock(now()))
        if(earned>0)data.put("day_points",data.optDouble("day_points",0.0)+earned)
    }
    private fun songFacts(data:JSONObject,song:Song){data.put("song_id",song.id).put("title",song.title).put("artist",song.artist).put("language",song.language).put("genre",song.genre)}
    fun ordered(song:Song,origin:String="")=transaction {
        val a=account();val key=workKey(song)
        mutate(a,"song",key){j->songFacts(j,song);j.increment("orders");if(now()-j.optLong("last_order")>60_000)reward(j,1.0);j.put("last_order",now())}
        sql.insertWithOnConflict("smart_intents",null,ContentValues().apply{put("account",a);put("work_key",key);put("origin",origin.takeIf{it in origins}?:"");put("created_at",now())},SQLiteDatabase.CONFLICT_REPLACE)
        invalidate()
    }
    fun start(song:Song):String=transaction {
        val a=account();val key=workKey(song);val token=UUID.randomUUID().toString()
        val origin=sql.rawQuery("SELECT origin,created_at FROM smart_intents WHERE account=? AND work_key=?",arrayOf(a,key)).use{if(it.moveToFirst()&&now()-it.getLong(1)<12*3_600_000L)it.getString(0) else ""}
        sql.delete("smart_intents","account=? AND work_key=?",arrayOf(a,key))
        val seq=sql.insertOrThrow("smart_sessions",null,ContentValues().apply{put("token",token);put("account",a);put("work_key",key);put("song_id",song.id);put("started_at",now());put("updated_at",now());put("outcome","open");put("origin",origin);put("owner",processOwner)})
        // At most 10,000 recent raw sessions; preference components survive this pruning.
        sql.execSQL("DELETE FROM smart_sessions WHERE seq IN (SELECT seq FROM smart_sessions WHERE seq<? ORDER BY seq LIMIT 100)",arrayOf(seq-10_000))
        mutate(a,"song",key){j->songFacts(j,song);j.increment("plays");j.put("last_play",now())}
        invalidate();token
    }
    fun checkpoint(token:String,progress:SingingProgress,duration:Long){sql.update("smart_sessions",ContentValues().apply{put("updated_at",now());put("played_ms",progress.playedMs);put("accompaniment_ms",progress.accompanimentMs);put("coverage_ms",progress.coverageMs);put("duration_ms",duration)},"token=? AND outcome='open'",arrayOf(token))}
    fun finish(token:String,song:Song,progress:SingingProgress,duration:Long,outcome:String)=transaction {
        val row=sql.rawQuery("SELECT account,origin FROM smart_sessions WHERE token=? AND outcome='open'",arrayOf(token)).use{if(it.moveToFirst())it.getString(0) to it.getString(1) else null}?:return@transaction
        val (a,origin)=row;val complete=progress.completed(outcome=="natural",duration);val accompaniment=progress.accompanimentCompleted(outcome=="natural",duration)
        val skip=outcome=="skip"&&progress.playedMs in 3_000..30_000
        checkpoint(token,progress,duration)
        sql.update("smart_sessions",ContentValues().apply{put("outcome",if(accompaniment)"accompaniment_complete" else if(complete)"complete" else outcome)},"token=? AND outcome='open'",arrayOf(token))
        val points=when{accompaniment->7.0;complete->4.0;progress.playedMs>=60_000->if(progress.accompanimentMs>=progress.playedMs*80/100)3.0 else 2.0;skip->-0.5;else->0.0}
        mutate(a,"song",workKey(song)){j->songFacts(j,song);reward(j,points);j.increment("seconds",progress.playedMs/1000.0);j.increment("accomp_seconds",progress.accompanimentMs/1000.0);if(complete)j.increment("complete");if(accompaniment)j.increment("accomp");if(skip)j.increment("skips")}
        if(points>0){
            val artists=ArtistNames.members(song.artist).take(8)
            for(artist in artists)mutate(a,"artist",artist){reward(it,points/artists.size.coerceAtLeast(1),24.0)}
            if(song.language.isNotBlank())mutate(a,"style",song.language+"\u001f"+song.genre){reward(it,points/2,24.0)}
        }
        // Learn which candidate source leads to completion. Errors/interruptions provide no negative label.
        if(origin in origins&&(complete||skip))mutate(a,"arm",origin){it.increment("trials");if(complete)it.increment("success")}
        invalidate()
    }
    fun dismiss(song:Song)=transaction {val a=account();mutate(a,"hide",workKey(song)){it.put("until",now()+7*86_400_000L)};invalidate()}
    private fun ranked(account:String,kind:String,limit:Int,recent:Boolean=false):List<Pair<String,JSONObject>> = sql.rawQuery("SELECT key,data FROM smart_scores WHERE account=? AND kind=? ORDER BY "+(if(recent)"updated_at DESC,key" else "mass DESC,key")+" LIMIT ?",arrayOf(account,kind,limit.toString())).use{c->buildList{while(c.moveToNext())add(c.getString(0) to JSONObject(c.getString(1)))}}
    data class Pick(val song:Song,val origin:String,val reason:String,var rank:Double=0.0)
    @Synchronized fun recommendations(refresh:Boolean=false):JSONObject {
        val a=account();val start=SystemClock.elapsedRealtime();val clock=massClock(now())
        val queued=sql.rawQuery("SELECT song_id FROM queue ORDER BY id LIMIT 201",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        require(queued.size<=200){"待唱队列超过200首，请先整理队列后查看推荐"}
        val excluded=db.songsByIds(queued).map(::workKey).toHashSet()
        val cacheKey=a+":"+db.setting("smart_revision","0")+":"+excluded.sorted().joinToString(",")+":"+db.setting("catalog_revision","0")
        if(!refresh&&cacheKey==cachedKey&&SystemClock.elapsedRealtime()-cachedAt<60_000)return JSONObject(cached)
        val batch=if(refresh)now() else now()/3_600_000L
        val candidates=linkedMapOf<String,Pick>();val longSongs=(ranked(a,"song",80)+ranked(a,"song",40,true)).distinctBy{it.first}
        val history=longSongs.toMap();val artists=ranked(a,"artist",4);val styles=ranked(a,"style",3)
        val tonight=longSongs.filter{now()-maxOf(it.second.optLong("last_play"),it.second.optLong("last_order"))<4*3_600_000L}.sortedByDescending{maxOf(it.second.optLong("last_play"),it.second.optLong("last_order"))}.take(8).flatMap{ArtistNames.members(it.second.optString("artist"))}.take(16).toSet()
        val armCache=origins.associateWith{score(a,"arm",it)}
        val raw=mutableListOf<Triple<String,String,String>>()
        fun add(id:String,origin:String,reason:String){require(raw.size<340);raw.add(Triple(id,origin,reason))}
        for((_,j) in longSongs){val completed=j.optDouble("complete",0.0)>0;add(j.optString("song_id"),if(completed)"replay" else "unfinished",when{j.optDouble("accomp",0.0)>0->"伴奏完整播放过";completed->"完整播放过，熟悉的歌";j.optDouble("accomp_seconds",0.0)>=60->"用伴奏播放过";j.optDouble("plays",0.0)>=3&&j.optDouble("seconds",0.0)>=180->"你经常播放的歌";else->"以前点过，再唱一次"})}
        for((artist,_) in artists){
            fun artistPage(cursor:String)=sql.rawQuery("SELECT song_id FROM song_artists WHERE artist=? AND song_id>? ORDER BY song_id LIMIT 20",arrayOf(artist,cursor)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            val cursorKey="smart_artist_cursor_"+artist
            val ids=artistPage(db.setting(cursorKey,"")).ifEmpty{artistPage("")}
            if(refresh)db.setting(cursorKey,ids.lastOrNull()?:"",true)
            for(id in ids)add(id,"artist","你常唱的歌手 · $artist")
        }
        val cursor=db.setting("smart_explore_cursor","")
        for((style,_) in styles){val parts=style.split('\u001f',limit=2)
            fun stylePage(after:String)=sql.rawQuery("SELECT id FROM songs WHERE language=? AND genre=? AND id>? ORDER BY id LIMIT 20",arrayOf(parts[0],parts.getOrElse(1){""},after)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            val ids=stylePage(cursor).ifEmpty{if(cursor.isNotBlank())stylePage("") else emptyList()}
            for(id in ids)add(id,"style","相近语种与分类 · "+parts.filter{it.isNotBlank()}.joinToString(" / "))
        }
        sql.rawQuery("SELECT id FROM songs WHERE favorite=1 ORDER BY id LIMIT 40",null).use{while(it.moveToNext())add(it.getString(0),"replay","你收藏的歌")}
        fun explorePage(after:String)=sql.rawQuery("SELECT id FROM songs WHERE id>? ORDER BY id LIMIT 40",arrayOf(after)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        val explore=explorePage(cursor).ifEmpty{if(cursor.isNotBlank())explorePage("") else emptyList()}
        if(refresh)db.setting("smart_explore_cursor",explore.lastOrNull()?:"",true)
        for(id in explore)add(id,"explore","换个口味，试试这首")
        val examined=raw.size
        val songMap=raw.map{it.first}.distinct().chunked(200).flatMap{db.songsByIds(it)}.associateBy{it.id}
        val works=songMap.values.associate{it.id to workKey(it)}
        val keys=works.values.distinct()
        fun facts(kind:String):Map<String,JSONObject>{
            val result=mutableMapOf<String,JSONObject>()
            for(chunk in keys.chunked(200))sql.rawQuery("SELECT key,data FROM smart_scores WHERE account=? AND kind=? AND key IN ("+chunk.joinToString(","){"?"}+")",(listOf(a,kind)+chunk).toTypedArray()).use{c->while(c.moveToNext())result[c.getString(0)]=JSONObject(c.getString(1))}
            return result
        }
        val songFacts=history+facts("song");val hidden=facts("hide")
        for((id,origin,reason) in raw){
            check(SystemClock.elapsedRealtime()-start<1500){"推荐计算超时，请重试"}
            val song=songMap[id]?:continue;val key=works.getValue(id)
            if(key in excluded||(hidden[key]?.optLong("until")?:0)>now())continue
            val stats=songFacts[key]?:JSONObject();val members=ArtistNames.members(song.artist).take(8)
            val own=stats.optDouble("mass",0.0)/clock
            val artistTaste=members.sumOf{artist->artists.firstOrNull{it.first==artist}?.second?.optDouble("mass",0.0)?:0.0}/clock
            val styleTaste=styles.firstOrNull{it.first==song.language+"\u001f"+song.genre}?.second?.optDouble("mass",0.0)?.div(clock)?:0.0
            val arm=armCache.getValue(origin);val conversion=(arm.optDouble("success",0.0)+2)/(arm.optDouble("trials",0.0)+4)
            val last=stats.optLong("last_play");val cooldown=if(now()-last<30*60_000L)5.0 else 0.0
            val novelty=(key+batch).hashCode().toUInt().toDouble()/UInt.MAX_VALUE.toDouble()
            val tonightBoost=if(members.any{it in tonight})1.2 else 0.0
            val value=tonightBoost+ln1p(own.coerceAtLeast(0.0))*2+ln1p(artistTaste)*1.3+ln1p(styleTaste)*.5+conversion*2+novelty-cooldown+(if(song.favorite)1.2 else 0.0)
            val existing=candidates[key];if(existing==null||value>existing.rank)candidates[key]=Pick(song,origin,reason,value)
        }
        val sorted=candidates.values.sortedByDescending{it.rank};val chosen=mutableListOf<Pick>();val singerCounts=mutableMapOf<String,Int>()
        fun take(pick:Pick):Boolean{if(pick in chosen)return false;val names=ArtistNames.members(pick.song.artist);if(names.any{(singerCounts[it]?:0)>=2})return false;chosen.add(pick);for(name in names)singerCounts[name]=(singerCounts[name]?:0)+1;return true}
        // Reserve discovery space instead of endlessly reinforcing already-familiar songs.
        sorted.filter{it.origin in listOf("artist","style","explore")}.take(20).forEach{if(chosen.size<3)take(it)}
        for(pick in sorted)if(chosen.size<12)take(pick)
        val list=JSONArray();for(pick in chosen){val s=pick.song;list.put(JSONObject().put("song",JSONObject().put("id",s.id).put("title",s.title).put("artist",s.artist).put("language",s.language).put("genre",s.genre)).put("reason",pick.reason).put("origin",pick.origin))}
        val result=JSONObject().put("items",list).put("learning",history.isNotEmpty()).put("updatedAt",now()).put("examined",examined).put("elapsedMs",SystemClock.elapsedRealtime()-start).put("backupPath",db.setting("catalog_backup_path"))
        require(db.setting("account")==a){"账号已切换，请刷新推荐"}
        cachedKey=cacheKey;cachedAt=SystemClock.elapsedRealtime();cached=result.toString();return result
    }
    private fun recoverInterrupted(){
        val a=account()
        val pending=sql.rawQuery("SELECT token,song_id,played_ms,accompaniment_ms,duration_ms FROM smart_sessions WHERE account=? AND outcome='open' AND owner<>? ORDER BY seq LIMIT 20",arrayOf(a,processOwner)).use{c->buildList{while(c.moveToNext())add(arrayOf(c.getString(0),c.getString(1),c.getLong(2).toString(),c.getLong(3).toString(),c.getLong(4).toString()))}}
        for(row in pending){val song=db.song(row[1]);if(song!=null)finish(row[0],song,SingingProgress.interrupted(row[2].toLong(),row[3].toLong()),row[4].toLong(),"interrupted") else sql.update("smart_sessions",ContentValues().apply{put("outcome","interrupted")},"token=? AND outcome='open'",arrayOf(row[0]))}
    }
    private var cachedKey="";private var cachedAt=0L;private var cached="{}"
    init{if(db.setting("account").isNotBlank())recoverInterrupted()}
    fun invalidate(){db.setting("smart_revision",UUID.randomUUID().toString(),true);cachedKey=""}
    fun writeBackup(writer:JsonWriter){
        val a=account();writer.beginObject().name("version").value(1).name("components").beginArray();var count=0
        sql.rawQuery("SELECT device,kind,key,revision,data FROM smart_components WHERE account=?",arrayOf(a)).use{c->while(c.moveToNext()){require(++count<=MAX_COMPONENTS){"智能偏好超过5万项，请先整理"};writer.beginObject().name("device").value(c.getString(0)).name("kind").value(c.getString(1)).name("key").value(c.getString(2)).name("revision").value(c.getLong(3)).name("data").value(c.getString(4)).endObject()}}
        writer.endArray().endObject()
    }
    suspend fun readBackup(reader:JsonReader,account:String){
        val changed=linkedSetOf<Pair<String,String>>();var count=0;var version=0
        reader.beginObject();while(reader.hasNext())when(reader.nextName()){
            "version"->version=reader.nextInt()
            "components"->{require(version==1){"不支持的智能偏好版本"};reader.beginArray();while(reader.hasNext()){
                currentCoroutineContext().ensureActive();require(++count<=MAX_COMPONENTS){"智能偏好超过5万项"};var origin="";var kind="";var key="";var revision=0L;var data=""
                reader.beginObject();while(reader.hasNext())when(reader.nextName()){"device"->origin=reader.nextString();"kind"->kind=reader.nextString();"key"->key=reader.nextString();"revision"->revision=reader.nextLong();"data"->data=reader.nextString();else->reader.skipValue()};reader.endObject()
                require(origin.matches(Regex("[a-zA-Z0-9-]{1,64}"))&&kind in kinds&&key.length in 1..512&&revision>0&&data.length<=8192){"智能偏好数据无效"}
                val j=JSONObject(data);for(field in listOf("mass","orders","plays","complete","accomp","skips","success","trials","seconds","accomp_seconds"))require(j.optDouble(field,0.0).isFinite()&&abs(j.optDouble(field,0.0))<1e100)
                val existing=sql.rawQuery("SELECT revision FROM smart_components WHERE account=? AND device=? AND kind=? AND key=?",arrayOf(account,origin,kind,key)).use{if(it.moveToFirst())it.getLong(0) else 0L}
                if(revision>existing){sql.insertWithOnConflict("smart_components",null,ContentValues().apply{put("account",account);put("device",origin);put("kind",kind);put("key",key);put("revision",revision);put("data",data)},SQLiteDatabase.CONFLICT_REPLACE);changed.add(kind to key)}
            };reader.endArray()}
            else->reader.skipValue()
        };reader.endObject();require(version==1)
        for((kind,key) in changed){currentCoroutineContext().ensureActive();aggregate(account,kind,key)}
    }
}
