package com.pocketktv.app
import org.junit.Assert.*
import org.junit.Test
class BiliDanmakuTest {
 private fun number(n:Long):ByteArray{var x=n;val out=java.io.ByteArrayOutputStream();while(x>=128){out.write(((x and 127) or 128).toInt());x=x ushr 7};out.write(x.toInt());return out.toByteArray()}
 private fun str(field:Int,text:String):ByteArray{val b=text.toByteArray();return number((field*8+2).toLong())+number(b.size.toLong())+b}
 private fun row(id:Int,at:Int,mode:Int,text:String):ByteArray{val b=byteArrayOf(8)+number(id.toLong())+byteArrayOf(16)+number(at.toLong())+byteArrayOf(24)+number(mode.toLong())+str(6,"hash")+str(7,text);return byteArrayOf(10)+number(b.size.toLong())+b}
 @Test fun parsesOrdinaryCommentsSkipsUnknownFieldsAndNeverRunsAdvancedContent(){val rows=BiliDanmaku.decode(row(1,1000,1,"<script>text</script>")+row(2,2000,8,"advanced")+row(3,1500,5,"置顶"));assertEquals(2,rows.size);assertEquals("<script>text</script>",rows[0].text);assertEquals(1500L,rows[1].at)}
 @Test fun malformedAndOversizedMessagesAreRejected(){for(b in listOf(byteArrayOf(10,127,1),ByteArray(2*1024*1024+1))){try{BiliDanmaku.decode(b);fail("must reject") }catch(_:IllegalArgumentException){}}}
 @Test fun playbackWindowIsBoundedAndTracksTheSeekPosition(){val rows=(0..5999).map{BiliComment("$it",it*100L,1,0xffffff,"弹幕")};val window=BiliDanmaku.window(rows,300000);assertTrue(window.length()<=120);assertTrue(window.getJSONObject(0).getLong("at")>=299000);assertEquals(0,BiliDanmaku.window(rows,900000).length())}
}
