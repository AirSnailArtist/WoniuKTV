package com.pocketktv.app

import okhttp3.OkHttpClient
import okhttp3.Request

/** MPEG PS files copied from DVDs can retain hour-long source timestamps. Use the span. */
object MpegTimeline {
    private const val BLOCK=512*1024
    fun timestamps(bytes:ByteArray):Map<Int,List<Long>> {
        val result=linkedMapOf<Int,MutableList<Long>>()
        fun b(i:Int)=bytes[i].toInt() and 255
        var i=0
        while(i+15<bytes.size){
            if(b(i)!=0||b(i+1)!=0||b(i+2)!=1||b(i+3) !in 0xE0..0xEF){i++;continue}
            var p=i+6
            if(b(p) and 0xC0==0x80){if(b(p+1) and 0x80==0){i++;continue};p+=3}
            else{while(p<bytes.size-5&&b(p)==255)p++;if(b(p) and 0xC0==0x40)p+=2}
            if(p+4>=bytes.size){i++;continue}
            if(b(p) and 0xF0 !in listOf(0x20,0x30)||b(p) and 1!=1||b(p+2) and 1!=1||b(p+4) and 1!=1){i++;continue}
            val pts=((b(p).toLong() and 14) shl 29) or (b(p+1).toLong() shl 22) or ((b(p+2).toLong() and 254) shl 14) or (b(p+3).toLong() shl 7) or (b(p+4).toLong() shr 1)
            result.getOrPut(b(i+3)){mutableListOf()}.add(pts);i=p+5
        };return result
    }
    fun span(first:ByteArray,last:ByteArray):Long? {
        val heads=timestamps(first);val tails=timestamps(last)
        for((stream,start) in heads){val end=tails[stream]?:continue;if(start.size<2||end.size<2)continue
            var ticks=end.max()-start.min();if(ticks<0)ticks+=(1L shl 33)
            val ms=ticks/90+40;if(ms in 1_000L..28_800_000L)return ms
        };return null
    }
    private fun readBlock(input:java.io.InputStream):ByteArray{val bytes=ByteArray(BLOCK);var size=0;while(size<BLOCK){val n=input.read(bytes,size,BLOCK-size);if(n<0)break;size+=n};return bytes.copyOf(size)}
    fun probe(client:OkHttpClient,url:String,headers:Map<String,String> = emptyMap(),diagnostic:(String)->Unit={}):Long? {
        var total=0L
        fun request(range:String)=Request.Builder().url(url).apply{headers.forEach{(k,v)->header(k,v)}}.header("Accept-Encoding","identity").header("Range",range).build()
        val first=client.newCall(request("bytes=0-${BLOCK-1}")).execute().use{r->
            if(!r.isSuccessful)return null;total=r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?:r.body?.contentLength()?:0
            diagnostic("head status=${r.code} total=$total")
            if(total<=0)return null;r.body?.byteStream()?.use{readBlock(it)}?:return null
        }
        diagnostic("head bytes=${first.size} pts=${timestamps(first).mapValues{it.value.size}}")
        if(total<=BLOCK)return span(first,first)
        val last=client.newCall(request("bytes=${(total-BLOCK).coerceAtLeast(0)}-${total-1}")).execute().use{r->diagnostic("tail status=${r.code}");if(r.code!=206)return null;r.body?.byteStream()?.use{readBlock(it)}?:return null}
        diagnostic("tail bytes=${last.size} pts=${timestamps(last).mapValues{it.value.size}}")
        return span(first,last)
    }
}
