package com.pocketktv.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit backup operation only: at most 8 playlists x 5000 indexed track IDs, no network.
 * Credentials, device IDs, screen calibration, loudness and pending upload receipts stay local.
 * Restore runs inside CatalogCodec's atomic transaction; existing local playlists/settings win.
 */
object PortableState {
    private val preferences=mapOf(
        "bili_danmaku" to setOf("true","false"),
        "video_fullscreen" to setOf("true","false"),
        "video_resize" to setOf("0","1","2","3","4"),
        "bili_video_resize" to setOf("0","1","2","3","4")
    )
    fun write(db:LibraryDb,w:JsonWriter){
        w.beginObject().name("version").value(1).name("preferences").beginObject()
        for((key,allowed) in preferences){val value=db.setting(key);if(value in allowed)w.name(key).value(value)}
        w.endObject().name("playlists").beginArray()
        db.readableDatabase.rawQuery("SELECT ref,title,total FROM cloud_playlists ORDER BY loaded DESC,ref LIMIT 9",null).use{c->
            var count=0
            while(c.moveToNext()){
                require(++count<=8){"歌单备份最多8份"}
                val ref=c.getString(0)
                w.beginObject().name("ref").value(ref).name("title").value(c.getString(1)).name("total").value(c.getInt(2)).name("track_ids").beginArray()
                db.readableDatabase.rawQuery("SELECT song_id FROM cloud_playlist_tracks WHERE ref=? ORDER BY position LIMIT 5001",arrayOf(ref)).use{t->var n=0;while(t.moveToNext()){require(++n<=5000){"单个歌单最多5000首"};w.value(t.getString(0))}}
                w.endArray().endObject()
            }
        }
        w.endArray().endObject()
    }
    suspend fun read(db:LibraryDb,r:JsonReader){
        var version=0;var count=0;val seen=hashSetOf<String>();val sql=db.writableDatabase
        r.beginObject()
        while(r.hasNext())when(r.nextName()){
            "version"->version=r.nextInt()
            "preferences"->{require(version==1);r.beginObject();var n=0;while(r.hasNext()){require(++n<=16);val key=r.nextName();if(key !in preferences){r.skipValue();continue};val value=r.nextString();require(value in preferences.getValue(key)){"备份偏好值无效"};if(db.setting(key).isBlank())db.setting(key,value,true)};r.endObject()}
            "playlists"->{require(version==1);r.beginArray();while(r.hasNext()){
                currentCoroutineContext().ensureActive();require(++count<=8){"歌单备份超过8份"}
                var ref="";var title="";var total=0;val ids=mutableListOf<String>()
                r.beginObject();while(r.hasNext())when(r.nextName()){
                    "ref"->ref=r.nextString()
                    "title"->title=r.nextString().also{require(it.length<=200)}
                    "total"->total=r.nextInt().also{require(it in 0..5000)}
                    "track_ids"->{r.beginArray();while(r.hasNext()){require(ids.size<5000);ids.add(r.nextString().also{require(it.matches(Regex("[0-9]{1,20}")))})};r.endArray()}
                    else->r.skipValue()
                };r.endObject()
                require(ref.matches(Regex("(?:netease|qq):[0-9]{1,20}"))&&seen.add(ref)&&title.isNotBlank()&&ids.size<=total){"备份歌单格式无效"}
                val exists=sql.rawQuery("SELECT ref FROM cloud_playlists WHERE ref=?",arrayOf(ref)).use{it.moveToFirst()}
                if(!exists){
                    val size=sql.rawQuery("SELECT ref FROM cloud_playlists LIMIT 9",null).use{it.count};require(size<8){"合并后歌单超过8份，请先整理本机歌单"}
                    sql.insertOrThrow("cloud_playlists",null,ContentValues().apply{put("ref",ref);put("title",title);put("total",total);put("loaded",0L)})
                    val insert=sql.compileStatement("INSERT INTO cloud_playlist_tracks(ref,position,song_id) VALUES(?,?,?)")
                    try{ids.forEachIndexed{i,id->insert.bindString(1,ref);insert.bindLong(2,i.toLong());insert.bindString(3,id);insert.executeInsert()}}finally{insert.close()}
                }
            };r.endArray()}
            else->r.skipValue()
        }
        r.endObject();require(version==1){"不支持的个人数据版本"}
    }
}
