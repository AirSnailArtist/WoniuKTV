package com.pocketktv.app

import com.homektv.library.FilenameParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryRulesTest {
    @Test fun sharedCreditsAndLanguageRules(){
        assertEquals(listOf("刘若英","陈升"),ArtistNames.members(" 刘若英＋陈升 "))
        assertEquals(listOf("刘若英","陈升","徐怀钰"),ArtistNames.members("刘若英^陈升 VS 徐怀钰"))
        assertEquals(listOf("刘若英","黄韵玲"),ArtistNames.members("刘若英vs黄韵玲"))
        assertEquals(listOf("刘和刚"),ArtistNames.members("刘和刚"))
        assertEquals(listOf("神木与瞳"),ArtistNames.members("神木与瞳"))
        assertEquals(listOf("MC&HOTDOG"),ArtistNames.members("MC&HOTDOG"))
        assertEquals(listOf("X+DREAM"),ArtistNames.members("X+DREAM"))
        assertEquals(listOf("Twins"),ArtistNames.members("TWINS"))
        assertEquals(listOf("Mary J. Blige"),ArtistNames.members("Mary J. Blige"))
        assertEquals(listOf("王力宏","Selina"),ArtistNames.members("王力宏 feat. Selina"))
        assertEquals(listOf("王力宏","RAIN"),ArtistNames.members("王力宏+FEAT.RAIN"))
        assertEquals(listOf("MC&HOTDOG","张震岳"),ArtistNames.members("MC&HOTDOG+张震岳"))
        assertEquals(listOf("NELLY","JAHEIM"),ArtistNames.members("NELLY FEAT.JAHEIM"))
        assertEquals(listOf("TAYLOR","BRUNO"),ArtistNames.members("TAYLOR&BRUNO"))
        assertEquals("闽南语",LibraryMetadataPipeline.parse("歌手-测试(闽南).mpg","/KTV").language)
        assertEquals("国语",LibraryMetadataPipeline.parse("褚乔-Future(国语).mpg","/KTV").language)
        assertEquals("国语",LibraryMetadataPipeline.parse("歌手-测试-国语-.mpg","/KTV").language)
        assertEquals("国语",LibraryMetadataPipeline.parse("歌手-测试-国.mpg","/KTV").language)
    }
    @Test fun structuredKtvMetadata(){
        assertEquals("爱我还是伤害我",LibraryMetadataPipeline.parse("沈世爱--爱我还是伤害我(国语).mpg","/KTV").title)
        val a=LibraryMetadataPipeline.parse("孟庭苇-你看你看月亮的脸-国语-流行-DVD原唱.mpg","/KTV/DVD歌库--06")
        assertEquals("你看你看月亮的脸",a.title);assertEquals("孟庭苇",a.artist);assertEquals("国语",a.language);assertEquals("流行",a.genre);assertEquals("DVD原唱",a.edition)
        val prefixed=LibraryMetadataPipeline.parse("国语-陈楚生-容颜(14演)-流行.mpg","/KTV")
        assertEquals("陈楚生",prefixed.artist);assertEquals("容颜(14演)",prefixed.title);assertEquals("国语",prefixed.language)
        assertEquals("",LibraryMetadataPipeline.parse("守望那座洁白的毡房(国语).mpg","/MV/万首KTV带字幕 DVD10T蓝光原盘收藏版").artist)
        val b=LibraryMetadataPipeline.parse("王识贤-今夜的烧酒(无原唱)-台语-流行-DVD伴唱.mpg","/KTV")
        assertEquals("今夜的烧酒",b.title);assertEquals("DVD伴唱",b.edition)
        assertEquals("国语",LibraryMetadataPipeline.parse("孙楠-从头再来(国语).mpg","/KTV").language)
        val c=LibraryMetadataPipeline.parse("茜拉-最长的电影-国语-964412.mpg","/KTV")
        assertEquals("最长的电影",c.title);assertEquals("国语",c.language)
        assertEquals("i-can-love-you",LibraryMetadataPipeline.parse("MARY J.BLIGE-i-can-love-you-英语-流行-DVD原唱(1).mpg","/KTV").title)
        assertEquals("2002",LibraryMetadataPipeline.parse("2002.mp4","/MV").title)
    }

    @Test fun collectionMetadataKeepsSourceAndFindsArtist(){
        val meta=SongNames.metadata("10止战之殇 修复By 4k60帧收藏级画质馆长.mp4","/MV/【4k修复60帧】周杰伦全套MV合辑【极品收藏级画质】")
        assertEquals("止战之殇",meta.first);assertEquals("周杰伦",meta.second)
        assertEquals("周杰伦",SongNames.metadata("一路向北.mp4","/MV/【4k修复60帧】周杰伦全套MV合辑/十一月的萧邦专辑共12个").second)
        assertEquals("周杰伦",SongNames.metadata("1七里香 修复By 4k60帧收藏级画质馆长.mp4","/MV/【4k修复60帧】周杰伦全套MV合辑【极品收藏级画质】修复By 4k60帧收藏级画质馆长/七里香专辑共10个MV 修复By 4k60帧收藏级画质馆长").second)
        assertEquals("2002",SongNames.metadata("2002.mp4","/MV").first)
        assertEquals("晴天",SongNames.metadata("周杰伦 - 晴天.mp4","/MV").first)
    }
    @Test fun qrWaitingResponsesAndConfirmation(){
        assertEquals(0,qrStatus(JSONObject("""{"state":1,"data":{}}""")))
        assertEquals(0,qrStatus(JSONObject("""{"state":1}""")))
        assertEquals(2,qrStatus(JSONObject("""{"data":{"status":2}}""")))
        assertEquals(-1,qrStatus(JSONObject("""{"data":{"status":-1}}""")))
    }
    @Test fun numeric115SuccessIsNotAnError(){
        assertTrue(stateAccepted(1));assertTrue(stateAccepted(true));assertTrue(stateAccepted("1"))
        assertFalse(stateAccepted(0));assertFalse(stateAccepted(false));assertFalse(stateAccepted(null))
        assertTrue(is115Host("a.115cdn.net"));assertFalse(is115Host("115.com.attacker.example"))
    }
    @Test fun namesAndPinyin() {
        val meta=FilenameParser.parse("001. 周杰伦 - 晴天 [KTV].mp4")
        assertEquals("周杰伦",meta.artist());assertEquals("晴天",meta.title())
        val reversed=FilenameParser.parse("海阔天空 - Beyond.mkv","title_artist")
        assertEquals("Beyond",reversed.artist());assertEquals("海阔天空",reversed.title())
        val py=SongNames.pinyin("周杰伦 晴天")
        assertEquals("zhoujielunqingtian",py.first);assertEquals("zjlqt",py.second)
        assertTrue(SongNames.supported("原伴唱.MPG"));assertFalse(SongNames.supported("曲库目录.xlsx"))
    }
    @Test fun webFoldersDoNotBecomeSongs() {
        val page=parsePage(JSONObject("""{"count":2,"data":[{"cid":"88","n":"国语"},{"fid":"999","cid":"88","n":"歌手 - 歌名.mp4","pc":"abc","s":500}]}"""),"0",false)
        assertTrue(page.entries[0].folder);assertEquals("88",page.entries[0].id)
        assertFalse(page.entries[1].folder);assertEquals("999",page.entries[1].id)
    }
    @Test fun openApiStringCategoriesAndLargeIds() {
        val page=parsePage(JSONObject("""{"count":2,"data":[{"file_id":"1234567890123456789","file_name":"曲库","file_category":"0"},{"file_id":"2","file_name":"歌.mp4","file_category":"1","pick_code":"pc"}]}"""),"0",true)
        assertTrue(page.entries[0].folder);assertEquals("1234567890123456789",page.entries[0].id)
        assertFalse(page.entries[1].folder)
    }
    @Test fun cipherTransportValidatesAndKeepsBlockBoundaries() {
        val key=ByteArray(16){it.toByte()}
        val payload="""{"pickcode":"abc123"}"""
        val encoded=SessionCipher.encode(payload,key)
        assertEquals(128,java.util.Base64.getDecoder().decode(encoded).size)
        val bytes=ByteArray(23){(it*7).toByte()};val original=bytes.copyOf()
        SessionCipher.xor(bytes,SessionCipher.derive(key,4));SessionCipher.xor(bytes,SessionCipher.derive(key,4))
        assertArrayEquals(original,bytes)
        try{SessionCipher.decode("AA==",key);fail("must reject truncated ciphertext")}catch(_:IllegalArgumentException){}
    }
}
