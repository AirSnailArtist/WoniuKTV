package com.pocketktv.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Public playlist metadata only. No platform audio URLs, passwords or user sessions are used. */
class MusicPlaylists(private val db:LibraryDb) {
    companion object {
        private val hosts=setOf("music.163.com","y.music.163.com","163cn.tv","y.qq.com","i.y.qq.com","c.y.qq.com","c6.y.qq.com")
        fun create(sql:SQLiteDatabase){
            sql.execSQL("CREATE TABLE IF NOT EXISTS cloud_playlists(ref TEXT PRIMARY KEY,title TEXT NOT NULL,total INTEGER NOT NULL,loaded INTEGER NOT NULL)")
            sql.execSQL("CREATE TABLE IF NOT EXISTS cloud_playlist_tracks(ref TEXT NOT NULL,position INTEGER NOT NULL,song_id TEXT NOT NULL,PRIMARY KEY(ref,position))")
        }
        fun canonical(value:String):String? {
            if(value.matches(Regex("(?:netease|qq):[0-9]{1,20}")))return value
            val u=runCatching{value.toHttpUrl()}.getOrNull()?:return null
            if(u.scheme!="https"||u.host !in hosts||u.port!=443||u.username.isNotEmpty()||u.password.isNotEmpty())return null
            val route=u.encodedPath+"/"+u.fragment.orEmpty()
            if(u.host.endsWith("163.com")&&!route.contains("playlist",true))return null
            if(u.host.endsWith("qq.com")&&!route.contains("playlist",true)&&!route.contains("taoge",true))return null
            val query=u.queryParameter("id")?:u.queryParameter("disstid")?:u.fragment?.substringAfter("id=","")?.substringBefore('&')?.takeIf{it.isNotBlank()}?:u.pathSegments.lastOrNull()
            if(query?.matches(Regex("[0-9]{1,20}"))!=true)return null
            return (if(u.host.endsWith("163.com"))"netease:" else if(u.host.endsWith("qq.com"))"qq:" else return null)+query
        }
    }
    private val busy=Semaphore(1)
    private val client=OkHttpClient.Builder().connectTimeout(6,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).build()
    private fun get(url:String,referer:String):JSONObject=client.newCall(Request.Builder().url(url).header("User-Agent",BilibiliClient.UA).header("Referer",referer).build()).execute().use{r->check(r.isSuccessful){"歌单平台暂不可用"};val body=r.body?:error("歌单内容为空");check(body.contentLength()<=4_000_000){"歌单信息超过预算"};val data=body.byteStream().use{input->val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(8192);while(true){val n=input.read(bytes);if(n<0)break;check(out.size()+n<=4_000_000);out.write(bytes,0,n)};out.toString("UTF-8")};JSONObject(data)}
    private fun resolve(text:String):String {
        require(text.length<=1200){"分享链接过长"};canonical(text.trim())?.let{return it}
        val found=Regex("https://[^\\s<>]+",RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd(')','）','。','，')?:error("请粘贴网易云或QQ音乐的歌单分享链接")
        var url=found.toHttpUrl();val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        repeat(4){
            canonical(url.toString())?.let{return it}
            require(url.scheme=="https"&&url.host in hosts&&url.port==443&&url.username.isEmpty()&&url.password.isEmpty()){ "只支持网易云和QQ音乐的官方分享链接" }
            check(System.nanoTime()<deadline){"解析分享链接超时"}
            val call=client.newCall(Request.Builder().url(url).header("User-Agent",BilibiliClient.UA).build());call.timeout().timeout(8,TimeUnit.SECONDS)
            url=call.execute().use{r->check(r.code in 300..399){"这个链接不是可读取的公开歌单，请复制歌单的分享链接"};val next=r.header("Location")?:error("分享链接缺少跳转目标");url.resolve(next)?.let{if(it.scheme=="http")it.newBuilder().scheme("https").build() else it}?:error("分享链接无效")}
        }
        return canonical(url.toString())?:error("分享链接跳转过多，请使用完整歌单地址")
    }
    private fun remember(ref:String,title:String,total:Int,ids:List<String>?=null){
        val sql=db.writableDatabase;sql.beginTransactionNonExclusive()
        try{
            val previous=sql.rawQuery("SELECT ref FROM cloud_playlists WHERE ref=?",arrayOf(ref)).use{it.moveToFirst()}
            if(!previous){val old=sql.rawQuery("SELECT ref FROM cloud_playlists ORDER BY loaded,ref LIMIT 8",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}};if(old.size>=8){sql.delete("cloud_playlist_tracks","ref=?",arrayOf(old.first()));sql.delete("cloud_playlists","ref=?",arrayOf(old.first()))}}
            if(ids!=null){sql.delete("cloud_playlist_tracks","ref=?",arrayOf(ref));val insert=sql.compileStatement("INSERT INTO cloud_playlist_tracks(ref,position,song_id) VALUES(?,?,?)");try{ids.forEachIndexed{i,id->insert.bindString(1,ref);insert.bindLong(2,i.toLong());insert.bindString(3,id);insert.executeInsert()}}finally{insert.close()}}
            sql.insertWithOnConflict("cloud_playlists",null,ContentValues().apply{put("ref",ref);put("title",title.take(200));put("total",total);put("loaded",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_REPLACE);sql.setTransactionSuccessful()
        }finally{sql.endTransaction()}
    }
    fun recent():JSONArray=db.readableDatabase.rawQuery("SELECT ref,title,total FROM cloud_playlists ORDER BY loaded DESC,ref LIMIT 8",null).use{c->JSONArray().apply{while(c.moveToNext())put(JSONObject().put("ref",c.getString(0)).put("title",c.getString(1)).put("total",c.getInt(2)))}}
    fun page(link:String,page:Int,refresh:Boolean=false):JSONObject {
        require(page in 1..250);check(busy.tryAcquire()){ "另一份歌单正在载入，请稍后重试" }
        try{
            val ref=resolve(link);val id=ref.substringAfter(':');val songs:JSONArray;val title:String;val total:Int;var warning=""
            if(ref.startsWith("netease:")){
                var meta=db.readableDatabase.rawQuery("SELECT title,total,loaded FROM cloud_playlists WHERE ref=?",arrayOf(ref)).use{if(it.moveToFirst())Triple(it.getString(0),it.getInt(1),it.getLong(2)) else null}
                if(meta==null||(page==1&&(refresh||System.currentTimeMillis()-meta.third>3_600_000))){
                    val j=get("https://music.163.com/api/v6/playlist/detail?id=$id&n=0&s=0","https://music.163.com/");check(j.optInt("code")==200){"无法读取歌单，请确认歌单已公开；私密歌单登录暂未接入"}
                    val p=j.getJSONObject("playlist");val ids=p.getJSONArray("trackIds");val size=p.optInt("trackCount",ids.length());require(size<=5000){"歌单超过5000首，请拆分后载入"};check(ids.length()==size){"平台未完整公开这份歌单，未导入不完整内容"}
                    val keys=(0 until ids.length()).map{ids.getJSONObject(it).getLong("id").toString()};remember(ref,p.getString("name"),size,keys);meta=Triple(p.getString("name"),size,System.currentTimeMillis())
                }
                title=meta.first;total=meta.second
                val ids=db.readableDatabase.rawQuery("SELECT song_id FROM cloud_playlist_tracks WHERE ref=? AND position>=? AND position<? ORDER BY position LIMIT 20",arrayOf(ref,((page-1)*20).toString(),(page*20).toString())).use{c->JSONArray().apply{while(c.moveToNext())put(c.getString(0).toLong())}}
                if(ids.length()==0)songs=JSONArray() else {val url="https://music.163.com/api/song/detail".toHttpUrl().newBuilder().addQueryParameter("ids",ids.toString()).build();val j=get(url.toString(),"https://music.163.com/");check(j.optInt("code")==200);val received=j.getJSONArray("songs");check(received.length()<=20);val map=(0 until received.length()).map{received.getJSONObject(it)}.associateBy{it.getLong("id").toString()};songs=JSONArray();for(i in 0 until ids.length())map[ids.getLong(i).toString()]?.let{songs.put(it)};if(songs.length()!=ids.length())warning="平台未返回本页部分歌曲的信息"}
            }else{
                val params=JSONObject().put("disstid",id.toLong()).put("song_begin",(page-1)*20).put("song_num",20).put("userinfo",1).put("tag",1).put("orderlist",1).put("onlysonglist",0)
                val payload=JSONObject().put("comm",JSONObject().put("ct",24).put("cv",0)).put("req_0",JSONObject().put("module","music.srfDissInfo.aiDissInfo").put("method","uniform_get_Dissinfo").put("param",params))
                val url="https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder().addQueryParameter("data",payload.toString()).build();val j=get(url.toString(),"https://y.qq.com/");check(j.optInt("code")==0);val req=j.getJSONObject("req_0");check(req.optInt("code")==0){"QQ歌单暂不可读，请检查是否公开"};val d=req.getJSONObject("data");check(d.optInt("code")==0){"QQ歌单暂不可读，请检查是否公开"}
                songs=d.optJSONArray("songlist")?:JSONArray();require(songs.length()<=20){"QQ歌单分页格式已变化"};title=d.getJSONObject("dirinfo").optString("title","QQ歌单");total=d.optInt("total_song_num");require(total in 0..5000){"歌单超过5000首，请拆分后载入"};if(d.optInt("filter_song_num")>0)warning="平台有部分不可见歌曲，按平台顺序展示";remember(ref,title,total)
            }
            val matcher=CatalogMatch(db);val rows=JSONArray()
            for(i in 0 until songs.length()){val song=songs.getJSONObject(i);val artists=song.optJSONArray("artists")?:song.optJSONArray("singer")?:JSONArray();val name=song.optString("name").ifBlank{song.optString("title")}.ifBlank{song.optString("songname")}.take(300);val names=(0 until minOf(artists.length(),10)).map{artists.getJSONObject(it).optString("name").take(100)}.filter{it.isNotBlank()};if(name.isBlank()||names.isEmpty()){warning="平台有部分歌曲缺少歌名或歌手信息";continue};rows.put(matcher.item(name,names).put("rank",(page-1)*20+i+1))}
            return JSONObject().put("ref",ref).put("title",title).put("total",total).put("page",page).put("hasMore",page*20<total).put("items",rows).put("warning",warning)
        }finally{busy.release()}
    }
}
