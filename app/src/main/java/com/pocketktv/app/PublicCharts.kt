package com.pocketktv.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

data class ChartEntry(val source:String,val rank:Int,val title:String,val artists:List<String>)
data class ChartFeed(val id:String,val label:String,val url:String,val updated:String,val entries:List<ChartEntry>,val fetched:Long=System.currentTimeMillis(),val error:String="")

/** Public chart metadata only. This client never carries a 115 session or resolves music URLs. */
class PublicCharts(private val context:Context,private val db:LibraryDb) {
    private val client=OkHttpClient.Builder().connectTimeout(6,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(18,TimeUnit.SECONDS).followRedirects(false).build()
    private val feeds=linkedMapOf<String,ChartFeed>()
    @Volatile var snapshot="{\"loading\":true,\"sources\":[],\"items\":[],\"recommendations\":[]}"
        private set
    init { for(id in listOf("qq","netease","kugou"))try{val j=JSONObject(File(context.filesDir,"chart-$id.json").readText());feeds[id]=decode(j)}catch(_:Exception){} }
    private fun get(url:String,referer:String):JSONObject=client.newCall(Request.Builder().url(url).header("User-Agent","Mozilla/5.0").header("Referer",referer).build()).execute().use{r->check(r.isSuccessful){"榜单服务暂不可用"};val body=r.body?:error("榜单内容为空");require(body.contentLength()<=4_000_000);JSONObject(body.string().also{require(it.length<=4_000_000)})}
    suspend fun refresh(force:Boolean=false)=withContext(Dispatchers.IO){
        build(true)
        val jobs=listOf("qq","netease","kugou").map{id->async{
            val previous=feeds[id]
            if(!force&&previous!=null&&System.currentTimeMillis()-previous.fetched<6*60*60*1000)return@async previous
            try{when(id){"qq"->qq();"netease"->netease();else->kugou()}.also{File(context.filesDir,"chart-$id.json").writeText(encode(it).toString())}}
            catch(_:Exception){previous?.copy(error="更新失败，显示缓存")?:ChartFeed(id,when(id){"qq"->"QQ音乐 · 抖音热歌榜";"netease"->"网易云 · 热歌榜";else->"酷狗 · 短视频热歌榜"},"","",emptyList(),error="暂时无法连接")}
        }}
        val results=jobs.awaitAll();synchronized(this@PublicCharts){results.forEach{feeds[it.id]=it};build(false)}
    }
    private fun qq():ChartFeed {
        val j=get("https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=60&format=json&needNewCode=1","https://y.qq.com/");check(j.optInt("code")==0)
        val rows=j.getJSONArray("songlist");val entries=(0 until rows.length()).mapNotNull{i->val song=rows.getJSONObject(i).getJSONObject("data");val a=song.optJSONArray("singer")?:JSONArray();val title=song.optString("songname");if(title.isBlank())null else ChartEntry("qq",i+1,title,(0 until a.length()).map{a.getJSONObject(it).optString("name")})}
        return ChartFeed("qq","QQ音乐 · 抖音热歌榜","https://y.qq.com/n/ryqq/toplist/60",j.optString("update_time"),entries)
    }
    private fun netease():ChartFeed {
        val j=get("https://music.163.com/api/playlist/detail?id=3778678","https://music.163.com/");check(j.optInt("code")==200)
        val result=j.getJSONObject("result");val tracks=result.getJSONArray("tracks")
        val entries=(0 until tracks.length()).map{i->val song=tracks.getJSONObject(i);val a=song.optJSONArray("artists")?:JSONArray();ChartEntry("netease",i+1,song.getString("name"),(0 until a.length()).map{a.getJSONObject(it).optString("name")})}
        return ChartFeed("netease","网易云 · 热歌榜","https://music.163.com/discover/toplist?id=3778678",java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(java.util.Date(result.optLong("updateTime"))),entries)
    }
    private fun kugou():ChartFeed {
        val j=get("https://m.kugou.com/rank/info/52144?json=true","https://m.kugou.com/");val tracks=j.getJSONObject("songs").getJSONArray("list")
        val entries=(0 until tracks.length()).mapNotNull{i->val text=tracks.getJSONObject(i).optString("filename");val parts=text.split(" - ",limit=2);if(parts.size!=2)null else ChartEntry("kugou",i+1,parts[1],ArtistNames.members(parts[0]))}
        return ChartFeed("kugou","酷狗 · 短视频热歌榜","https://m.kugou.com/rank/info/52144","获取 "+java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(java.util.Date()),entries)
    }
    private fun mappingKey(title:String,artists:List<String>)="chart_mapping:"+java.security.MessageDigest.getInstance("SHA-256").digest((ChartMatcher.titleKey(title)+"|"+artists.map{ChartMatcher.artistKey(it)}.sorted().joinToString()).toByteArray()).joinToString(""){"%02x".format(it)}
    @Synchronized fun choose(title:String,artists:List<String>,id:String){val song=db.song(id)?:error("歌曲不存在");require(ChartMatcher.titleKey(song.title)==ChartMatcher.titleKey(title)){"歌名不匹配"};db.setting(mappingKey(title,artists),id,true);build(false)}
    @Synchronized private fun build(loading:Boolean){
        val merged=linkedMapOf<String,JSONObject>();val scores=hashMapOf<String,Double>()
        val sources=JSONArray();val all=JSONArray()
        for(feed in feeds.values){sources.put(JSONObject().put("id",feed.id).put("label",feed.label).put("url",feed.url).put("updated",feed.updated).put("fetchedAt",feed.fetched).put("count",feed.entries.size).put("error",feed.error))
            for(entry in feed.entries){val key=ChartMatcher.titleKey(entry.title);val candidates=db.titleCandidates(key)
                val original=Regex("(?i)[(（]cover[ :：]*([^）)]+)[)）]").find(entry.title)?.groupValues?.get(1)
                val saved=db.setting(mappingKey(entry.title,entry.artists)).takeIf{it.isNotBlank()}?.let{db.song(it)}?.takeIf{ChartMatcher.titleKey(it.title)==key}
                val song=saved?:ChartMatcher.select(candidates,if(original.isNullOrBlank())entry.artists else ArtistNames.members(original))
                val item=JSONObject().put("source",feed.id).put("rank",entry.rank).put("title",entry.title).put("artists",JSONArray(entry.artists)).put("available",song!=null).put("status",if(song!=null)"matched" else if(candidates.isNotEmpty())"confirm" else "missing")
                if(song!=null){item.put("song",JSONObject().put("id",song.id).put("title",song.title).put("artist",song.artist).put("language",song.language).put("edition",song.edition))
                    if(song.language in LibraryLanguage.chinese){val work=key+"|"+ArtistNames.members(song.artist).map{ChartMatcher.artistKey(it)}.sorted().joinToString();scores[work]=(scores[work]?:0.0)+1.0/(entry.rank+10)+(if(song.favorite)0.025 else 0.0);merged.putIfAbsent(work,item)}
                };all.put(item)
            }
        }
        val recommended=JSONArray();scores.entries.sortedByDescending{it.value}.take(40).forEach{recommended.put(merged[it.key])}
        snapshot=JSONObject().put("loading",loading).put("sources",sources).put("items",all).put("recommendations",recommended).toString()
    }
    private fun encode(feed:ChartFeed)=JSONObject().put("id",feed.id).put("label",feed.label).put("url",feed.url).put("updated",feed.updated).put("fetched",feed.fetched).put("entries",JSONArray().apply{feed.entries.forEach{put(JSONObject().put("rank",it.rank).put("title",it.title).put("artists",JSONArray(it.artists)))}})
    private fun decode(j:JSONObject):ChartFeed {val id=j.getString("id");val a=j.getJSONArray("entries");return ChartFeed(id,j.getString("label"),j.getString("url"),j.getString("updated"),(0 until a.length()).map{i->val row=a.getJSONObject(i);val artists=row.getJSONArray("artists");ChartEntry(id,row.getInt("rank"),row.getString("title"),(0 until artists.length()).map{artists.getString(it)})},j.getLong("fetched"))}

    /** Exact-name artist search; cache just the photo bytes, never a guessed artist. */
    fun artistPhoto(name:String):ByteArray? {
        if(name.isBlank()||name.length>80)return null
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString(""){"%02x".format(it)}
        val file=File(context.cacheDir,"artist-$key.jpg");if(file.exists()&&System.currentTimeMillis()-file.lastModified()<30L*24*60*60*1000)return file.readBytes()
        val miss=File(context.cacheDir,"artist-$key.miss");if(miss.exists()&&System.currentTimeMillis()-miss.lastModified()<24L*60*60*1000)return null
        return try{
            val j=get("https://music.163.com/api/search/get?s="+URLEncoder.encode(name,"UTF-8")+"&type=100&limit=5&offset=0","https://music.163.com/")
            val artists=j.optJSONObject("result")?.optJSONArray("artists")?:JSONArray()
            val found=(0 until artists.length()).map{artists.getJSONObject(it)}.firstOrNull{ChartMatcher.artistKey(it.optString("name"))==ChartMatcher.artistKey(name)}?:run{miss.writeText("");return null}
            val url=found.optString("img1v1Url").replace("http://","https://");val uri=android.net.Uri.parse(url);require(uri.scheme=="https"&&uri.host.orEmpty().endsWith(".music.126.net"))
            client.newCall(Request.Builder().url(url+"?param=240y240").header("Referer","https://music.163.com/").build()).execute().use{r->check(r.isSuccessful);require(r.header("Content-Type").orEmpty().startsWith("image/"));val body=r.body?:return null;require(body.contentLength() in 1..2_000_000);val bytes=body.bytes();require(bytes.size<=2_000_000);file.writeBytes(bytes);bytes}
        }catch(_:Exception){null}
    }
}
