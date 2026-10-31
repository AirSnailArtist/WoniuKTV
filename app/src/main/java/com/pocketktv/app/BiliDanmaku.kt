package com.pocketktv.app

import org.json.JSONArray
import org.json.JSONObject

data class BiliComment(val id:String,val at:Long,val mode:Int,val color:Int,val text:String)

/** Minimal protobuf reader for ordinary B站 comments. Never executes advanced/BAS content. */
object BiliDanmaku {
    private class Reader(val bytes:ByteArray,var at:Int=0,val end:Int=bytes.size){
        fun number():Long {var value=0L;for(i in 0..9){require(at<end){"弹幕数据不完整"};val b=bytes[at++].toInt() and 255;require(i<9||b<=1);value=value or ((b and 127).toLong() shl (i*7));if(b<128)return value};error("弹幕整数无效")}
        fun length():Int {val n=number();require(n in 0..(end-at).toLong());return n.toInt()}
        fun skip(wire:Int){when(wire){0->number();1->{require(end-at>=8);at+=8};2->{val n=length();at+=n};5->{require(end-at>=4);at+=4};else->error("弹幕格式暂不支持")}}
    }
    fun decode(bytes:ByteArray):List<BiliComment>{
        require(bytes.size<=2*1024*1024);val r=Reader(bytes);val rows=ArrayList<BiliComment>();var count=0
        while(r.at<r.end){val tag=r.number().toInt();require(tag>0);if(tag!=10){r.skip(tag and 7);continue};val n=r.length();val e=Reader(bytes,r.at,r.at+n);r.at+=n;require(++count<=6000){"弹幕分段过大"}
            var id="";var progress=0L;var mode=1;var color=0xffffff;var content=""
            while(e.at<e.end){val field=e.number().toInt();require(field>0);when(field){8->id=e.number().toString();16->progress=e.number();24->mode=e.number().toInt();40->color=e.number().toInt() and 0xffffff;58->{val size=e.length();require(size<=8192);content=String(bytes,e.at,size,Charsets.UTF_8).replace(Regex("[\\p{Cntrl}]")," ").take(160);e.at+=size};else->e.skip(field and 7)}}
            if(mode in listOf(1,4,5)&&progress in 0..360_000_000L&&content.isNotBlank())rows.add(BiliComment(id.ifBlank{"$progress:$count"},progress,mode,color,content))
        }
        return rows.sortedBy{it.at}
    }
    fun window(rows:List<BiliComment>,position:Long):JSONArray{
        fun lower(value:Long):Int{var lo=0;var hi=rows.size;while(lo<hi){val m=(lo+hi) ushr 1;if(rows[m].at<value)lo=m+1 else hi=m};return lo}
        val start=lower((position-1000).coerceAtLeast(0));val end=lower(position+1000);val stride=((end-start+119)/120).coerceAtLeast(1)
        return JSONArray().apply{var i=start;while(i<end){val c=rows[i];put(JSONObject().put("id",c.id).put("at",c.at).put("mode",c.mode).put("color",c.color).put("text",c.text));i+=stride}}
    }
}
