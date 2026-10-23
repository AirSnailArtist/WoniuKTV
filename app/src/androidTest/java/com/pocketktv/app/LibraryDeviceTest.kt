package com.pocketktv.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDeviceTest {
    @Test fun queueBoostAndNextInsertKeepStableOrder()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="queue-order-${System.nanoTime()}.db";val db=LibraryDb(context,name)
        try{
            db.startScan("10","/KTV")
            db.commitPage(db.nextFolder()!!,DrivePage((1..3).map{DriveEntry("$it","10","歌手 - 歌$it.mp4",false,"pc$it",100)},3))
            val first=db.enqueue("1");val second=db.enqueue("2")
            db.topQueue(second);assertEquals(listOf("2","1"),db.queue().map{it.id})
            db.enqueue("3");assertEquals(listOf("2","1","3"),db.queue().map{it.id})
            db.topQueue(first);assertEquals(listOf("1","2","3"),db.queue().map{it.id})
            db.removeQueue(db.queue().first().queueId);assertEquals(listOf("2","3"),db.queue().map{it.id})
            val selected=db.selectForPlayback("1");assertEquals(listOf("1","2","3"),db.queue().map{it.id});assertEquals(selected,db.queue().first().queueId)
            db.selectForPlayback("3");assertEquals(listOf("3","1","2"),db.queue().map{it.id})
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun portableCatalogAndInvalidImportRollback()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val a="export-${System.nanoTime()}.db";val b="import-${System.nanoTime()}.db"
        val source=LibraryDb(context,a);val target=LibraryDb(context,b)
        val file=java.io.File(context.cacheDir,"test-catalog-${System.nanoTime()}.json")
        try{
            source.bindAccount("account-A");source.startScan("10","/KTV")
            source.commitPage(source.nextFolder()!!,DrivePage(listOf(DriveEntry("101","10","周杰伦-晴天-国语-流行-DVD伴唱.mpg",false,"abc123",100)),1))
            source.favorite("101",true);source.audio("101","left",0);source.reparseMetadata()
            source.setting("chart_mapping:"+"a".repeat(64),"101",true)
            assertEquals(1,CatalogCodec.export(source,file))
            val exported=file.readText();assertFalse(exported.contains("cookie"));assertFalse(exported.contains("access_token"));assertFalse(exported.contains("https://"))
            target.bindAccount("account-A")
            assertEquals(1,file.inputStream().use{CatalogCodec.import(target,it,"account-A")})
            assertEquals("101",target.setting("chart_mapping:"+"a".repeat(64)))
            assertEquals("晴天",target.song("101")!!.title);assertTrue(target.song("101")!!.favorite);assertEquals("国语",target.song("101")!!.language);assertEquals("流行",target.song("101")!!.genre);assertEquals("DVD伴唱",target.song("101")!!.edition);assertEquals("left",target.song("101")!!.audioMode);assertEquals(LibraryMetadataPipeline.VERSION,target.setting("metadata_rule_version"))
            val malicious="""{"format":"pocket-ktv-catalog","version":1,"account_id":"account-A","songs":[{"id":"999","name":"新歌.mp4","title":"新歌","artist":"","pick_code":"pc9","path":"/新歌.mp4"},{"id":"bad","name":"坏数据.mp4","title":"坏数据","pick_code":"xx"}]}"""
            try{CatalogCodec.import(target,malicious.byteInputStream(),"account-A");fail("invalid row must fail")}catch(_:IllegalArgumentException){}
            assertNull(target.song("999"));assertEquals(1,target.count())
            try{file.inputStream().use{CatalogCodec.import(target,it,"account-B")};fail("wrong account must fail")}catch(_:IllegalArgumentException){}
            assertEquals(1,target.count())
        }finally{source.close();target.close();file.delete();context.deleteDatabase(a);context.deleteDatabase(b)}
    }
    @Test fun resumeDuplicateScansAndAccountIsolation()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="verification-${System.nanoTime()}.db"
        var db=LibraryDb(context,name)
        try {
            db.bindAccount("test-account-A");db.startScan("10","/测试")
            val song=DriveEntry("100","10","周杰伦 - 晴天.mp4",false,"test-pick-code",100)
            db.commitPage(db.nextFolder()!!,DrivePage(listOf(song,DriveEntry("20","10","子目录",true,"",0)),3))
            assertEquals(2,db.nextFolder()!!.offset);assertEquals(1,db.count())
            db.favorite("100",true);db.audio("100","left",0)
            val queueId=db.enqueue("100")
            db.close();db=LibraryDb(context,name)
            assertEquals(2,db.nextFolder()!!.offset);assertEquals(queueId,db.queue().first().queueId)
            db.commitPage(db.nextFolder()!!,DrivePage(listOf(song),3))
            assertEquals("20",db.nextFolder()!!.cid)
            assertEquals(1,db.count());assertTrue(db.song("100")!!.favorite);assertEquals("left",db.song("100")!!.audioMode)
            assertEquals(1,db.search("zjl").size);assertEquals(1,db.search("晴天").size)
            assertEquals(0,db.search("%' OR 1=1 --").size)
            db.bindAccount("test-account-B")
            assertEquals(0,db.count());assertTrue(db.queue().isEmpty());assertNull(db.nextFolder())
        }finally{db.close();context.deleteDatabase(name)}
    }
}
