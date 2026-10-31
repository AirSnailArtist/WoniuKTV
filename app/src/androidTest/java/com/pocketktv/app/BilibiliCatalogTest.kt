package com.pocketktv.app

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated databases only: selected B站 metadata must round-trip without touching the 115 library. */
class BilibiliCatalogTest {
    @Test fun selectedVideoAndExistingCatalogRoundTrip()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val names=listOf("bili-fixture-${UUID.randomUUID()}.db","bili-fixture-${UUID.randomUUID()}.db")
        val db=LibraryDb(context,names[0]);val restored=LibraryDb(context,names[1]);val file=File(context.cacheDir,"bili-fixture-${UUID.randomUUID()}.json")
        try{
            db.setting("account","fixture-account",true);restored.setting("account","fixture-account",true)
            db.writableDatabase.insertOrThrow("songs",null,ContentValues().apply{put("id","123");put("name","本地歌.mp4");put("title","本地歌");put("artist","本地歌手");put("pick","fixture123");put("path","/fixture");put("search","本地歌")})
            db.favorite("123",true)
            val video=BiliVideo("BV1GJ411x7h7",137649199,1,"MV","官方音乐",213)
            val selected=db.rememberBilibili(video);db.rememberBilibili(video)
            assertEquals(2,db.count());assertTrue(db.song("123")!!.favorite)
            assertEquals("bilibili",selected.metadataProvider);assertEquals(video.bvid,selected.pickCode)
            db.enqueueUnique(selected.id);assertEquals(selected.id,db.queue().single().id)
            assertEquals(2,CatalogCodec.export(db,file))
            assertFalse(file.readText().contains("SESSDATA"));assertFalse(file.readText().contains("bilivideo.com"))
            file.inputStream().use{assertEquals(2,CatalogCodec.import(restored,it,"fixture-account"))}
            assertEquals(selected.path,restored.song(selected.id)!!.path);assertEquals("bilibili",restored.song(selected.id)!!.metadataProvider);assertTrue(restored.song("123")!!.favorite)
        }finally{db.close();restored.close();names.forEach{context.deleteDatabase(it)};file.delete()}
    }
}
