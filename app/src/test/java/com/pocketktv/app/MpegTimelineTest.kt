package com.pocketktv.app
import org.junit.Assert.*
import org.junit.Test
class MpegTimelineTest {
 private fun packet(t:Long):ByteArray{val b=byteArrayOf(0,0,1,0xe0.toByte(),0,20,0x80.toByte(),0x80.toByte(),5,0,0,0,0,0);b[9]=(0x21 or (((t shr 29) and 14).toInt())).toByte();b[10]=(t shr 22).toByte();b[11]=(((t shr 14) and 254) or 1).toByte();b[12]=(t shr 7).toByte();b[13]=(((t shl 1) and 254) or 1).toByte();return b+ByteArray(8)}
 @Test fun offsetDoesNotBecomeDuration(){val base=7100L*90000;val first=packet(base)+packet(base+9000);val tail=packet(base+240L*90000)+packet(base+241L*90000);assertEquals(241040L,MpegTimeline.span(first,tail));assertNull(MpegTimeline.span(ByteArray(50),tail))}
}
