package com.pocketktv.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.homektv.library.FilenameParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.sourceforge.pinyin4j.PinyinHelper
import java.util.Locale

data class Song(val id: String, val name: String, val title: String, val artist: String, val pickCode: String,
    val path: String, val favorite: Boolean, val audioMode: String, val accompaniment: Int, val queueId: Long = 0, val language:String="", val genre:String="", val edition:String="", val sourceGroup:String="", val metadataProvider:String="")
data class ScanTask(val cid: String, val path: String, val offset: Int)
internal object SongNames {
    fun metadata(name:String,path:String,rule:String="artist_title"):Pair<String,String>{
        val cleaned=name.replace(Regex("\\s+修复\\s*By.*?(?=\\.[^.]+$)",RegexOption.IGNORE_CASE),"")
            .replace(Regex("^\\d{1,3}(?!\\d)(?:[.、_\\s-]+|(?=[\\p{IsHan}]))"),"")
        val meta=FilenameParser.parse(cleaned,rule)
        val inferred=path.split('/').asReversed().asSequence().map{it.replace(Regex("【[^】]*】|\\[[^]]*]"),"").trim()}
            .mapNotNull{Regex("^(.{1,16}?)(?:全套|全集|合集|合辑|歌曲|MV|KTV)",RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim()}
            .firstOrNull{!it.contains("修复")&&!it.contains("收藏")&&!it.contains("专辑")&&!(it.contains("共")&&it.contains("个"))&&it !in listOf("国语","粤语","高清","万首","千首","百首","曲库")}.orEmpty()
        val artist=meta.artist().takeUnless{it.isBlank()||it.contains("专辑")||it.contains("共")&&it.contains("个") }?:inferred
        return meta.title() to artist
    }
    private val supported = setOf("mp4","mkv","mpg","mpeg","ts","m2ts","avi","mov","webm","mp3","flac","m4a","wav","aac","ogg")
    fun supported(name: String) = name.substringAfterLast('.',"").lowercase(Locale.ROOT) in supported
    fun pinyin(text: String): Pair<String,String> {
        val full=StringBuilder(); val initials=StringBuilder()
        for (char in text.lowercase(Locale.ROOT)) {
            val syllable=PinyinHelper.toHanyuPinyinStringArray(char)?.firstOrNull()?.replace(Regex("[1-5]$"),"")
            if (syllable != null) { full.append(syllable); initials.append(syllable.first()) }
            else if (char.isLetterOrDigit()) { full.append(char); initials.append(char) }
        }
        return full.toString() to initials.toString()
    }
}

/** Android replacement for Home KTV's PostgreSQL library + queue repositories. */
class LibraryDb(context: Context, databaseName:String="pocket-library.db"): SQLiteOpenHelper(context,databaseName,null,6) {
    val learningDeviceId:String=context.getSharedPreferences("smart-local",Context.MODE_PRIVATE).let{prefs->
        prefs.getString("device",null)?:java.util.UUID.randomUUID().toString().also{prefs.edit().putString("device",it).commit()}
    }
    init { setWriteAheadLoggingEnabled(true) }
    suspend fun reparseMetadata(onProgress:(Int,Int)->Unit={_,_->}){
        if(setting("metadata_rule_version")==LibraryMetadataPipeline.VERSION){rebuildArtistIndex(onProgress);return}
        val total=count();var offset=0;val rule=setting("name_rule","artist_title")
        while(offset<total){
            currentCoroutineContext().ensureActive()
            val entries=readableDatabase.rawQuery("SELECT * FROM songs ORDER BY id LIMIT 500 OFFSET ?",arrayOf(offset.toString())).use{c->buildList{while(c.moveToNext())add(song(c))}}
            val rows=entries.map{entry->entry.id to metadataValues(entry.name,entry.path.substringBeforeLast('/'),rule,entry)}
            val sql=writableDatabase;sql.beginTransaction()
            try{for((id,values) in rows){sql.update("songs",values,"id=?",arrayOf(id));indexArtists(id,values.getAsString("artist"))};sql.setTransactionSuccessful()}finally{sql.endTransaction()}
            offset+=entries.size;onProgress(offset,total)
        }
        setting("metadata_rule_version",LibraryMetadataPipeline.VERSION,true)
        setting("artist_index_version",ArtistNames.VERSION,true)
    }
    private suspend fun rebuildArtistIndex(onProgress:(Int,Int)->Unit){
        if(setting("artist_index_version")==ArtistNames.VERSION)return
        val total=count();var offset=0
        while(offset<total){
            currentCoroutineContext().ensureActive()
            val rows=readableDatabase.rawQuery("SELECT id,artist FROM songs ORDER BY id LIMIT 500 OFFSET ?",arrayOf(offset.toString())).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
            val sql=writableDatabase;sql.beginTransaction();try{rows.forEach{indexArtists(it.first,it.second)};sql.setTransactionSuccessful()}finally{sql.endTransaction()}
            offset+=rows.size;onProgress(offset,total)
        }
        setting("artist_index_version",ArtistNames.VERSION,true)
    }
    private fun metadataValues(name:String,path:String,rule:String,previous:Song?=null):ContentValues {
        val m=LibraryMetadataPipeline.parse(name,path,rule);val same=previous!=null&&previous.title==m.title&&previous.artist==m.artist
        val (full,initials)=if(same)"" to "" else SongNames.pinyin(m.title+" "+m.artist)
        return ContentValues().apply{put("title",m.title);put("title_key",ChartMatcher.titleKey(m.title));put("artist",m.artist);put("language",m.language);put("genre",m.genre);put("edition",m.edition)
            put("source_group",path.substringAfterLast('/'));put("metadata_provider",m.provider)
            if(!same)put("search",("$name "+m.title+" "+m.artist+" $full $initials").lowercase(Locale.ROOT))}
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE songs(id TEXT PRIMARY KEY,name TEXT NOT NULL,title TEXT NOT NULL,title_key TEXT NOT NULL DEFAULT '',artist TEXT NOT NULL,pick TEXT NOT NULL,path TEXT NOT NULL,search TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,audio_mode TEXT NOT NULL DEFAULT 'tracks',accompaniment INTEGER NOT NULL DEFAULT 1,language TEXT NOT NULL DEFAULT '',genre TEXT NOT NULL DEFAULT '',edition TEXT NOT NULL DEFAULT '',source_group TEXT NOT NULL DEFAULT '',metadata_provider TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE queue(id INTEGER PRIMARY KEY AUTOINCREMENT,song_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE folders(cid TEXT PRIMARY KEY,path TEXT NOT NULL,offset INTEGER NOT NULL DEFAULT 0,done INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        createArtistIndex(db)
        SmartLibrary.create(db)
        ArtistDirectory.create(db)
        db.execSQL("CREATE INDEX title_key_idx ON songs(title_key)")
        db.execSQL("CREATE INDEX favorite_idx ON songs(favorite)")
        db.execSQL("CREATE INDEX queue_order ON queue(id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<4){db.execSQL("ALTER TABLE songs ADD COLUMN title_key TEXT NOT NULL DEFAULT ''");val update=db.compileStatement("UPDATE songs SET title_key=? WHERE id=?");db.rawQuery("SELECT id,title FROM songs",null).use{c->while(c.moveToNext()){update.bindString(1,ChartMatcher.titleKey(c.getString(1)));update.bindString(2,c.getString(0));update.executeUpdateDelete()}};update.close();db.execSQL("CREATE INDEX title_key_idx ON songs(title_key)")}
        if(oldVersion<3){createArtistIndex(db);db.rawQuery("SELECT id,artist FROM songs",null).use{c->while(c.moveToNext())for(artist in ArtistNames.members(c.getString(1)))db.insertWithOnConflict("song_artists",null,ContentValues().apply{put("song_id",c.getString(0));put("artist",artist)},SQLiteDatabase.CONFLICT_IGNORE)}}
        if(oldVersion<2)listOf("language","genre","edition","source_group","metadata_provider").forEach{db.execSQL("ALTER TABLE songs ADD COLUMN $it TEXT NOT NULL DEFAULT ''")}
        if(oldVersion<5)SmartLibrary.create(db)
        if(oldVersion<6)ArtistDirectory.create(db)
    }
    private fun createArtistIndex(sql:SQLiteDatabase){
        sql.execSQL("CREATE TABLE IF NOT EXISTS song_artists(song_id TEXT NOT NULL,artist TEXT NOT NULL,PRIMARY KEY(song_id,artist))")
        sql.execSQL("CREATE INDEX IF NOT EXISTS artist_members_idx ON song_artists(artist,song_id)")
    }
    fun indexArtists(id:String,credits:String){
        val sql=writableDatabase;val ownTransaction=!sql.inTransaction()
        if(ownTransaction)sql.beginTransactionNonExclusive()
        try{
            sql.delete("song_artists","song_id=?",arrayOf(id))
            for(artist in ArtistNames.members(credits)){
                ArtistDirectory.indexName(sql,artist)
                sql.insertWithOnConflict("song_artists",null,ContentValues().apply{put("song_id",id);put("artist",artist)},SQLiteDatabase.CONFLICT_IGNORE)
            }
            if(ownTransaction)sql.setTransactionSuccessful()
        }finally{if(ownTransaction)sql.endTransaction()}
    }
    fun setting(key:String, fallback:String=""): String = readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else fallback }
    fun setting(key:String, value:String, persist:Boolean) { writableDatabase.insertWithOnConflict("settings",null,ContentValues().apply { put("key",key);put("value",value) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun bindAccount(id:String): Boolean {
        require(id.isNotBlank()) { "115 未返回账号标识，不能安全绑定曲库" }
        val changed = setting("account").let { it.isNotEmpty() && it != id }
        if (changed) writableDatabase.apply {
            beginTransaction(); try { delete("queue",null,null);delete("song_artists",null,null);delete("songs",null,null);delete("artist_counts",null,null);delete("artist_names",null,null);delete("folders",null,null);delete("settings",null,null);setTransactionSuccessful() } finally { endTransaction() }
        }
        setting("account",id,true)
        return changed
    }
    fun count() = readableDatabase.rawQuery("SELECT COUNT(*) FROM songs",null).use { it.moveToFirst();it.getInt(0) }
    fun search(query:String, favorites:Boolean=false, category:String="",artist:String="",language:String="",offset:Int=0,limit:Int=200,scope:String="all"): List<Song> {
        val where= mutableListOf<String>();val args= mutableListOf<String>()
        if (query.isNotBlank()) {
            where += "search LIKE ? ESCAPE '\\'"
            args += "%"+query.trim().lowercase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
        }
        if(favorites) where += "favorite=1"
        if(scope in listOf("chinese","foreign"))where+=LibraryLanguage.condition(scope)
        if(artist.isNotBlank()){where+="id IN (SELECT song_id FROM song_artists WHERE artist=?)";args+=artist}

        val audio="(lower(name) LIKE '%.mp3' OR lower(name) LIKE '%.flac' OR lower(name) LIKE '%.wav' OR lower(name) LIKE '%.aac' OR lower(name) LIKE '%.m4a' OR lower(name) LIKE '%.ogg')"
        if(category=="华语歌曲")where+=LibraryLanguage.condition("chinese")
        if(category=="其他外语")where+="(${LibraryLanguage.condition("foreign")} AND language<>'英语')"
        if(category=="待识别语种")where+="language=''"
        if(category=="英文歌曲")where+="language='英语'"
        if(category=="纯音频")where+=audio
        if(category=="视频歌曲")where+="NOT $audio"
        if(language.isNotBlank()){where+="language=?";args+=language}
        val facets=mapOf("语言" to "language","分类" to "genre","版本" to "edition","目录" to "source_group")
        val facet=facets[category.substringBefore('：')]
        if(facet!=null){where+="$facet=?";args+=category.substringAfter('：')}
        if(category=="待整理歌手")where+="(artist='' OR artist='佚名')"
        return readableDatabase.rawQuery("SELECT * FROM songs"+(if(where.isEmpty()) "" else " WHERE "+where.joinToString(" AND "))+" ORDER BY ${LibraryLanguage.order()},title COLLATE NOCASE,id LIMIT ${limit.coerceIn(1,200)} OFFSET ${offset.coerceAtLeast(0)}",args.toTypedArray()).use { cursor -> buildList { while(cursor.moveToNext()) add(song(cursor)) } }
    }
    /** Phone pages use index cursors. Broad substring searches stop explicitly after 800ms. */
    internal fun <T> boundedRead(action:(android.os.CancellationSignal)->T):T {
        val signal=android.os.CancellationSignal()
        val timeout=PhoneReadDeadline.worker.schedule({signal.cancel()},800,java.util.concurrent.TimeUnit.MILLISECONDS)
        return try{action(signal)}catch(e:android.os.OperationCanceledException){error("查询超时，请缩小搜索范围后重试")}finally{timeout.cancel(false)}
    }
    fun phoneSongs(after:String,keyword:String,artist:String):List<Song> = boundedRead{signal->
        val args=mutableListOf<String>();val where=mutableListOf<String>()
        if(artist.isNotBlank()){where.add("id IN (SELECT song_id FROM song_artists WHERE artist=?)");args.add(artist)}
        where.add("id>?");args.add(after)
        if(keyword.isNotBlank()){where.add("search LIKE ? ESCAPE '\\'");args.add("%"+keyword.lowercase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%")}
        readableDatabase.rawQuery("SELECT * FROM songs WHERE "+where.joinToString(" AND ")+" ORDER BY id LIMIT 21",args.toTypedArray(),signal).use{c->buildList{while(c.moveToNext())add(song(c))}}
    }
    fun phoneArtists(after:String,keyword:String,scope:String):List<String> = boundedRead{signal->
        val args=arrayOf(after,"%"+keyword.replace("%","\\%").replace("_","\\_")+"%")
        readableDatabase.rawQuery("SELECT a.artist FROM song_artists a JOIN songs s ON s.id=a.song_id WHERE a.artist>? AND a.artist LIKE ? ESCAPE '\\' AND "+LibraryLanguage.condition(scope,"s.language")+" GROUP BY a.artist ORDER BY a.artist LIMIT 21",args,signal).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    }
    /** Offline catalog audit only; UI pages use ArtistDirectory.page. */
    fun artistCounts(scope:String="all"):List<Pair<String,Int>> = readableDatabase.rawQuery("SELECT name,song_count FROM artist_counts WHERE scope=? ORDER BY rank,name",arrayOf(scope)).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getInt(1))}}
    fun titleCandidates(key:String):List<Song> = readableDatabase.rawQuery("SELECT * FROM songs WHERE title_key=? LIMIT 80",arrayOf(key)).use{c->buildList{while(c.moveToNext())add(song(c))}}
    fun songsByIds(ids:List<String>):List<Song>{
        require(ids.size<=200)
        if(ids.isEmpty())return emptyList()
        return readableDatabase.rawQuery("SELECT * FROM songs WHERE id IN ("+ids.joinToString(","){"?"}+")",ids.toTypedArray()).use{c->buildList{while(c.moveToNext())add(song(c))}}
    }
    fun song(id:String):Song? = readableDatabase.rawQuery("SELECT * FROM songs WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) song(it) else null }
    private fun song(c:Cursor):Song {
        fun text(name:String)=c.getString(c.getColumnIndexOrThrow(name))
        fun number(name:String)=c.getInt(c.getColumnIndexOrThrow(name))
        return Song(text("id"),text("name"),text("title"),text("artist"),text("pick"),text("path"),number("favorite")==1,text("audio_mode"),number("accompaniment"),
            c.getColumnIndex("qid").let { if(it>=0) c.getLong(it) else 0 },text("language"),text("genre"),text("edition"),text("source_group"),text("metadata_provider"))
    }
    fun counterpart(source:Song):Song? {
        val clause=if(source.edition.contains("伴"))"edition LIKE '%原唱%'" else "(edition LIKE '%伴唱%' OR edition LIKE '%伴奏%')"
        return readableDatabase.rawQuery("SELECT * FROM songs WHERE artist=? AND title=? AND id<>? AND $clause ORDER BY CASE WHEN source_group=? THEN 0 ELSE 1 END,id LIMIT 1",arrayOf(source.artist,source.title,source.id,source.sourceGroup)).use{if(it.moveToFirst())song(it) else null}
    }
    fun replaceQueueSong(queueId:Long,songId:String){require(song(songId)!=null);writableDatabase.update("queue",ContentValues().apply{put("song_id",songId)},"id=?",arrayOf(queueId.toString()))}
    fun favorite(id:String, value:Boolean) { writableDatabase.update("songs",ContentValues().apply { put("favorite",if(value)1 else 0) },"id=?",arrayOf(id)) }
    fun audio(id:String, mode:String, index:Int) { writableDatabase.update("songs",ContentValues().apply { put("audio_mode",mode);put("accompaniment",index) },"id=?",arrayOf(id)) }
    fun enqueue(id:String):Long = writableDatabase.insertOrThrow("queue",null,ContentValues().apply {put("song_id",id)})
    fun enqueueUnique(id:String):Long {
        val sql=writableDatabase;sql.beginTransaction()
        return try{val exists=sql.rawQuery("SELECT 1 FROM queue WHERE song_id=? LIMIT 1",arrayOf(id)).use{it.moveToFirst()};require(!exists){"这首歌已在队列中"};val row=enqueue(id);sql.setTransactionSuccessful();row}finally{sql.endTransaction()}
    }
    fun selectForPlayback(songId:String):Long {
        require(song(songId)!=null){"歌曲不存在"};val sql=writableDatabase;sql.beginTransaction()
        return try{val existing=sql.rawQuery("SELECT id FROM queue WHERE song_id=? LIMIT 1",arrayOf(songId)).use{if(it.moveToFirst())it.getLong(0) else null};val selected=existing?:enqueue(songId)
            val first=sql.rawQuery("SELECT COALESCE(MIN(id),0)-1 FROM queue",null).use{it.moveToFirst();it.getLong(0)}
            sql.execSQL("UPDATE queue SET id=? WHERE id=?",arrayOf(first,selected));sql.setTransactionSuccessful();first
        }finally{sql.endTransaction()}
    }
    fun removeQueue(id:Long) { writableDatabase.delete("queue","id=?",arrayOf(id.toString())) }
    fun topQueue(id:Long){
        val db=writableDatabase;db.beginTransaction()
        try{
            val first=db.rawQuery("SELECT COALESCE(MIN(id),0)-1 FROM queue",null).use{it.moveToFirst();it.getLong(0)}
            db.execSQL("UPDATE queue SET id=? WHERE id=?",arrayOf(first,id));db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun queue():List<Song> = readableDatabase.rawQuery("SELECT songs.*,queue.id AS qid FROM queue JOIN songs ON songs.id=queue.song_id ORDER BY queue.id",null).use { c -> buildList { while(c.moveToNext())add(song(c)) } }
    fun startScan(cid:String,path:String) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.delete("folders",null,null)
            db.insertOrThrow("folders",null,ContentValues().apply {put("cid",cid);put("path",path)})
            setting("root_id",cid,true);setting("root_path",path,true);setting("scan_done","0",true)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun nextFolder():ScanTask? = readableDatabase.rawQuery("SELECT cid,path,offset FROM folders WHERE done=0 ORDER BY rowid LIMIT 1",null).use { if(it.moveToFirst())ScanTask(it.getString(0),it.getString(1),it.getInt(2)) else null }
    suspend fun commitPage(task:ScanTask,page:DrivePage) {
        // Pinyin parsing happens off the UI thread, before taking the database write lock.
        val rule=setting("name_rule","artist_title")
        val rows=page.entries.filter { !it.folder && SongNames.supported(it.name) && it.pickCode.isNotBlank() }.map { entry ->
            currentCoroutineContext().ensureActive()
            entry to metadataValues(entry.name,task.path,rule).apply {
                put("id",entry.id);put("name",entry.name);put("pick",entry.pickCode);put("path",task.path.trimEnd('/')+"/"+entry.name)
            }
        }
        val db=writableDatabase;db.beginTransaction()
        try {
            for ((entry,values) in rows) {
                db.insertWithOnConflict("songs",null,values,SQLiteDatabase.CONFLICT_IGNORE)
                db.update("songs",values,"id=?",arrayOf(entry.id));indexArtists(entry.id,values.getAsString("artist")) // favorite and audio correction are intentionally preserved.
            }
            for (entry in page.entries.filter { it.folder }) db.insertWithOnConflict("folders",null,
                ContentValues().apply {put("cid",entry.id);put("path",task.path.trimEnd('/')+"/"+entry.name)},SQLiteDatabase.CONFLICT_IGNORE)
            val next=task.offset+page.entries.size
            if(page.entries.isEmpty() && next<page.total) error("115 返回空分页，已保留断点，请稍后续扫")
            db.update("folders",ContentValues().apply {put("offset",next);put("done",if(next>=page.total)1 else 0)},"cid=?",arrayOf(task.cid))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
}

private object PhoneReadDeadline { val worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task->Thread(task,"phone-query-deadline").apply{isDaemon=true} } }
