package com.pocketktv.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.util.Locale

/** Portable catalog: stable file references only; never contains session cookies, tokens or CDN URLs. */
object CatalogCodec {
    const val MAX_BYTES=128L*1024*1024
    fun export(db:LibraryDb,file:File,expectedAccount:String?=null):Int {
        val account=db.setting("account");require(expectedAccount==null||expectedAccount==account){"账号已切换，目录未上传"};require(account.isNotBlank()){"请先登录并绑定曲库"}
        var count=0
        JsonWriter(file.writer(Charsets.UTF_8).buffered()).use{w->
            w.beginObject().name("format").value("pocket-ktv-catalog").name("version").value(3)
                .name("account_id").value(account).name("created_at").value(System.currentTimeMillis())
                .name("root_id").value(db.setting("root_id")).name("root_path").value(db.setting("root_path"))
                .name("name_rule").value(db.setting("name_rule","artist_title"))
                .name("metadata_rule_version").value(db.setting("metadata_rule_version"))
                .name("artist_index_version").value(ArtistNames.VERSION)
                .name("scan_complete").value(db.setting("scan_done")=="1")
            w.name("chart_mappings").beginArray()
            db.readableDatabase.rawQuery("SELECT key,value FROM settings WHERE key LIKE 'chart_mapping:%' LIMIT 10000",null).use{c->while(c.moveToNext())w.beginObject().name("key").value(c.getString(0)).name("song_id").value(c.getString(1)).endObject()}
            w.endArray().name("songs").beginArray()
            db.readableDatabase.rawQuery("SELECT id,name,title,artist,pick,path,favorite,language,genre,edition,source_group,metadata_provider,search,audio_mode,accompaniment FROM songs ORDER BY id",null).use{c->
                while(c.moveToNext()){
                    w.beginObject();listOf("id","name","title","artist","pick_code","path").forEachIndexed{i,key->w.name(key).value(c.getString(i))}
                    w.name("artist_members").beginArray();ArtistNames.members(c.getString(3)).forEach{w.value(it)};w.endArray()
                    w.name("favorite").value(c.getInt(6)==1)
                    listOf("language","genre","edition","source_group","metadata_provider","search","audio_mode").forEachIndexed{i,key->w.name(key).value(c.getString(i+7))}
                    w.name("accompaniment").value(c.getInt(14)).endObject();count++
                }
            }
            w.endArray().name("song_count").value(count).name("learning");SmartLibrary(db).writeBackup(w);w.endObject()
        }
        require(db.setting("account")==account){"账号已切换，目录未上传"}
        require(file.length()<=MAX_BYTES){"目录超过 128MB，请按子目录拆分"}
        return count
    }
    suspend fun import(db:LibraryDb,input:InputStream,account:String,progress:(Int)->Unit={}):Int {
        require(account.isNotBlank()){"请先登录 115"}
        require(db.setting("account")==account){"账号已切换，请重新选择备份"}
        var metadataVersion="";var count=0;var format="";var version=0;var owner="";var rootId="";var rootPath="";var rule="artist_title";var found=false;var complete=false
        val mappings=mutableMapOf<String,String>()
        val database=db.writableDatabase
        database.beginTransactionNonExclusive()
        try{
            JsonReader(BoundedInput(input).reader(Charsets.UTF_8).buffered()).use{r->
                r.beginObject()
                while(r.hasNext())when(r.nextName()){
                    "format"->format=r.nextString()
                    "version"->version=r.nextInt()
                    "account_id"->owner=r.nextString()
                    "root_id"->rootId=r.nextString()
                    "root_path"->rootPath=r.nextString()
                    "name_rule"->rule=r.nextString()
                    "metadata_rule_version"->metadataVersion=r.nextString()
                    "scan_complete"->complete=r.nextBoolean()
                    "chart_mappings"->{r.beginArray();while(r.hasNext()){require(mappings.size<10000);var key="";var id="";r.beginObject();while(r.hasNext())when(r.nextName()){"key"->key=r.nextString();"song_id"->id=r.nextString();else->r.skipValue()};r.endObject();require(key.matches(Regex("chart_mapping:[a-f0-9]{64}"))&&id.matches(Regex("[0-9]{1,30}")));mappings[key]=id};r.endArray()}
                    "learning"->{require(format=="pocket-ktv-catalog"&&version==3&&owner==account){"智能偏好账号或格式不匹配"};SmartLibrary(db).readBackup(r,account)}
                    "songs"->{
                        require(format=="pocket-ktv-catalog"&&version in 1..3){"不支持的曲库目录格式"}
                        require(owner==account){"这份目录属于另一个 115 账号，请登录对应账号"}
                        require(!found){"目录包含重复歌曲列表"};found=true
                        r.beginArray()
                        while(r.hasNext()){
                            currentCoroutineContext().ensureActive()
                            require(count<500_000){"单份目录最多支持 50 万首"}
                            val row=mutableMapOf<String,String>();var favorite=false
                            r.beginObject()
                            while(r.hasNext())when(val key=r.nextName()){
                                "id","name","title","artist","pick_code","path","language","genre","edition","source_group","metadata_provider","search","audio_mode"->{val v=r.nextString();require(v.length<=4096){"目录字段过长"};row[key]=v}
                                "favorite"->favorite=r.nextBoolean()
                                "accompaniment"->row[key]=r.nextInt().toString()
                                else->r.skipValue()
                            }
                            r.endObject()
                            val id=row["id"].orEmpty();val pick=row["pick_code"].orEmpty()
                            require(id.matches(Regex("[0-9]{1,30}"))&&pick.matches(Regex("[a-zA-Z0-9]{1,64}"))){"目录包含无效的 115 文件标识"}
                            val name=row["name"].orEmpty();val title=row["title"].orEmpty();val artist=row["artist"].orEmpty()
                            require(title.isNotBlank()&&name.isNotBlank()){"目录缺少歌名或文件名"}
                            val savedSearch=row["search"].orEmpty()
                            val search=if(savedSearch.isNotBlank())savedSearch else {val(full,initials)=SongNames.pinyin("$title $artist");"$name $title $artist $full $initials".lowercase(Locale.ROOT)}
                            val values=ContentValues().apply{
                                put("id",id);put("name",name);put("title",title);put("title_key",ChartMatcher.titleKey(title));put("artist",artist);put("pick",pick);put("path",row["path"].orEmpty())
                                put("search",search)
                                listOf("language","genre","edition","source_group","metadata_provider").forEach{put(it,row[it].orEmpty())}
                            }
                            val added=database.insertWithOnConflict("songs",null,values,SQLiteDatabase.CONFLICT_IGNORE)
                            database.update("songs",values,"id=?",arrayOf(id));db.indexArtists(id,artist)
                            if(added!=-1L)database.update("songs",ContentValues().apply{put("favorite",if(favorite)1 else 0)
                                put("audio_mode",row["audio_mode"].orEmpty().takeIf{it in listOf("tracks","track","stereo","left","right")}?:"tracks")
                                put("accompaniment",row["accompaniment"]?.toIntOrNull()?.coerceIn(0,100)?:1)},"id=?",arrayOf(id))
                            count++
                            if(count % 500 == 0)progress(count)
                        };r.endArray()
                    }
                    else->r.skipValue()
                }
                r.endObject();require(r.peek()==android.util.JsonToken.END_DOCUMENT){"目录文件包含额外内容"}
            }
            require(found){"目录中没有歌曲列表"}
            require(format=="pocket-ktv-catalog"&&version in 1..3&&owner==account){"目录元数据不一致"}
            database.delete("folders",null,null)
            db.setting("root_id",rootId,true);db.setting("root_path",rootPath,true);db.setting("name_rule",rule,true)
            db.setting("scan_done",if(complete)"1" else "0",true)
            db.setting("metadata_rule_version",if(metadataVersion==LibraryMetadataPipeline.VERSION)metadataVersion else "",true)
            db.setting("artist_index_version",ArtistNames.VERSION,true)
            for((key,id) in mappings)if(db.setting(key).isBlank()&&db.song(id)!=null)db.setting(key,id,true)
            db.setting("catalog_revision",java.util.UUID.randomUUID().toString(),true)
            SmartLibrary(db).invalidate()
            database.setTransactionSuccessful()
        }finally{database.endTransaction()}
        progress(count)
        return count
    }
    private class BoundedInput(input:InputStream):FilterInputStream(input){
        private var size=0L
        override fun read():Int=super.read().also{if(it>=0)checkSize(1)}
        override fun read(b:ByteArray,off:Int,len:Int):Int=super.read(b,off,len).also{if(it>0)checkSize(it)}
        private fun checkSize(n:Int){size+=n;require(size<=MAX_BYTES){"目录文件超过 128MB 限制"}}
    }
}

