package com.pocketktv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Shared exact/confirmed matching; never automatically replaces an original with a cover. */
class CatalogMatch(private val db:LibraryDb) {
    fun item(title:String,artists:List<String>):JSONObject {
        val key=ChartMatcher.titleKey(title);val candidates=db.titleCandidates(key,81)
        val saved=db.setting(mappingKey(title,artists)).takeIf{it.isNotEmpty()}?.let{db.song(it)}?.takeIf{ChartMatcher.titleKey(it.title)==key}
        val crowded=candidates.size>80
        val song=saved?:if(crowded)null else ChartMatcher.select(candidates,artists)
        val result=JSONObject().put("title",title).put("artists",JSONArray(artists)).put("available",song!=null).put("status",if(song!=null)"matched" else if(candidates.isNotEmpty())"confirm" else "missing")
        if(crowded&&saved==null)result.put("warning","同名版本超过80个，请在歌名点歌中缩小范围确认，或选择B站MV")
        if(song!=null)result.put("song",JSONObject().put("id",song.id).put("title",song.title).put("artist",song.artist).put("language",song.language).put("edition",song.edition).put("source",if(song.metadataProvider=="bilibili")"bilibili" else song.metadataProvider.takeIf{it in DomesticDrive.labels}?:"115").put("views",if(song.metadataProvider=="bilibili")db.setting("bili_views:"+song.id).toLongOrNull()?:-1 else -1))
        return result
    }
    companion object {
        fun mappingKey(title:String,artists:List<String>)="chart_mapping:"+java.security.MessageDigest.getInstance("SHA-256").digest((ChartMatcher.titleKey(title)+"|"+artists.map{ChartMatcher.artistKey(it)}.sorted().joinToString()).toByteArray()).joinToString(""){"%02x".format(it)}
    }
}

/** Fixed curated metadata asset: 7x500 names, read once; only the requested <=50 rows are matched. */
class MusicCollections(context:Context,private val db:LibraryDb) {
    private val collections:JSONObject
    init {
        val text=context.assets.open("music-collections.json").use{input->val bytes=input.readBytes();require(bytes.size<=500_000);String(bytes,Charsets.UTF_8)}
        collections=JSONObject(text).getJSONObject("collections");require(collections.length()==7)
        for(key in collections.keys()){require(collections.getJSONObject(key).getJSONArray("items").length()==500)}
    }
    fun page(id:String,offset:Int,limit:Int):JSONObject {
        require(collections.has(id)&&offset in 0..500&&limit in 1..50){"歌曲分类或页码无效"}
        val collection=collections.getJSONObject(id);val all=collection.getJSONArray("items");val matcher=CatalogMatch(db)
        val rows=JSONArray();for(i in offset until minOf(offset+limit,all.length())){val row=all.getJSONObject(i);val a=row.getJSONArray("artists");val item=matcher.item(row.getString("title"),(0 until a.length()).map{a.getString(it)});rows.put(item.put("source",id).put("rank",i+1).put("reason",collection.getString("label")+"回忆精选").put("origin","explore"))}
        return JSONObject().put("items",rows).put("label",collection.getString("label")).put("total",500).put("hasMore",offset+rows.length()<500)
    }
}
