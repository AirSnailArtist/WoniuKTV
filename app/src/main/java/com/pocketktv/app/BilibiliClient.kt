package com.pocketktv.app

import okhttp3.FormBody
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

data class BiliVideo(val bvid:String,val cid:Long,val aid:Long,val title:String,val owner:String,val duration:Long,val views:Long=-1) {
    val id get()=(8_000_000_000_000_000_000L+cid).toString()
    fun json()=JSONObject().put("bvid",bvid).put("cid",cid).put("title",title).put("owner",owner).put("duration",duration).put("views",views)
}

/** Web integration with explicitly requested KTV favorites. No shared APP keys, credential service, or access-control bypass.
 * WBI permutation/reference: MIT aaa1115910/bv, pinned in UPSTREAM.md.
 * Search/folders: 20/page, <=2 concurrent requests, 15s/request, <=2MiB/response; no automatic retries.
 */
private class BiliApiRejection(message:String):IllegalStateException(message)

class BilibiliClient(private val load:()->String?,private val save:(String?)->Unit,
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(6,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS)
        .callTimeout(15,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).build(),
    private val readState:(String)->String={""},private val writeState:(String,String)->Unit={_,_->},
    private val apiBase:String="https://api.bilibili.com",private val passportBase:String="https://passport.bilibili.com") {
    companion object {
        const val UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        fun viewCount(value:Any?):Long {if(value==null||value==JSONObject.NULL)return -1;val text=value.toString().replace(",","").trim();text.toLongOrNull()?.let{return it.coerceAtLeast(0)};val match=Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([万亿])").matchEntire(text)?:return -1;return (match.groupValues[1].toDouble()*(if(match.groupValues[2]=="亿")100_000_000 else 10_000)).toLong()}
        fun validBvid(value:String)=value.matches(Regex("BV[0-9A-Za-z]{10}"))
        fun cleanTitle(value:String)=value.replace(Regex("<[^>]{0,200}>"),"").replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&").take(300)
        private val mixin=intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
        fun sign(params:Map<String,String>,keys:String,seconds:Long):Map<String,String>{
            require(keys.length==64)
            val values=(params+mapOf("wts" to seconds.toString())).mapValues{(_,v)->v.filterNot{it in "!'()*"}}.toSortedMap()
            fun encode(s:String)=URLEncoder.encode(s,"UTF-8").replace("+","%20").replace("%7E","~")
            val query=values.entries.joinToString("&"){encode(it.key)+"="+encode(it.value)}
            val key=mixin.take(32).map{keys[it]}.joinToString("")
            val signature=MessageDigest.getInstance("MD5").digest((query+key).toByteArray()).joinToString(""){"%02x".format(it)}
            return values+mapOf("w_rid" to signature)
        }
        fun allowedMedia(url:String):Boolean=runCatching{val u=url.toHttpUrl();u.scheme=="https"&&listOf("bilivideo.com","bilivideo.cn","bilibili.com","akamaized.net").any{u.host==it||u.host.endsWith(".$it")}}.getOrDefault(false)
    }
    private val permits=Semaphore(2)
    @Volatile private var cookie=load().orEmpty()
    @Volatile private var qrKey=""
    @Volatile private var qrExpires=0L
    private var lastPoll=0L
    @Volatile private var wbiKeys=""
    @Volatile private var keysUntil=0L
    private fun request(path:String,params:Map<String,String> = emptyMap(),passport:Boolean=false,allowGuest:Boolean=false,cookies:((List<Cookie>)->Unit)?=null,form:Map<String,String>?=null):JSONObject {
        check(permits.tryAcquire(1,TimeUnit.SECONDS)){"B站请求较多，请稍后重试"}
        try {
            val url=(if(passport)passportBase else apiBase).toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).apply{params.forEach{(k,v)->addQueryParameter(k,v)}}.build()
            val req=Request.Builder().url(url).header("User-Agent",UA).header("Referer","https://www.bilibili.com/").apply{if(cookie.isNotEmpty())header("Cookie",cookie);if(form!=null)post(FormBody.Builder().apply{form.forEach{(k,v)->add(k,v)}}.build())}.build()
            return client.newCall(req).execute().use{r->
                check(r.isSuccessful){if(r.code in listOf(403,412,429))"B站暂时限制了请求，请登录或稍后重试" else "B站请求失败（HTTP ${r.code}）"}
                cookies?.invoke(Cookie.parseAll(r.request.url,r.headers))
                val body=r.body?:error("B站返回空响应");check(body.contentLength()<=2*1024*1024){"B站响应过大，请缩小查询范围"}
                val text=body.byteStream().use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=2*1024*1024){"B站响应过大"};out.write(buffer,0,n)};out.toString("UTF-8")}
                val json=runCatching{JSONObject(text)}.getOrElse{error("B站返回非预期数据，请稍后重试")}
                val code=json.optInt("code",-1)
                if(!(code==0||(allowGuest&&code == -101)))throw BiliApiRejection(when(code){-101->"请先登录B站";-352,-412->"B站要求验证，请登录后重试";-403->"当前账号没有观看权限";-404->"视频不存在或已下架";else->"B站请求未成功（$code）"})
                json.optJSONObject("data")?:JSONObject()
            }
        }finally{permits.release()}
    }
    private fun nav():JSONObject=request("x/web-interface/nav",allowGuest=true).also{data->
        data.optJSONObject("wbi_img")?.let{img->val keys=listOf("img_url","sub_url").joinToString(""){img.optString(it).substringAfterLast('/').substringBefore('.')};if(keys.length==64){wbiKeys=keys;keysUntil=System.currentTimeMillis()+3_600_000}}
    }
    fun status():JSONObject {val n=nav();return JSONObject().put("loggedIn",n.optBoolean("isLogin")).put("uid",n.optLong("mid").takeIf{it>0}?.toString().orEmpty()).put("name",n.optString("uname"))}
    @Synchronized fun startLogin():String {
        val data=request("x/passport-login/web/qrcode/generate",passport=true)
        qrKey=data.getString("qrcode_key");qrExpires=System.currentTimeMillis()+180_000;lastPoll=0
        val url=data.getString("url");val parsed=url.toHttpUrl()
        require(parsed.scheme=="https"&&parsed.host in setOf("passport.bilibili.com","account.bilibili.com")){"B站登录地址无效"};return url
    }
    @Synchronized fun pollLogin():JSONObject {
        check(qrKey.isNotEmpty()&&System.currentTimeMillis()<qrExpires){"二维码已过期，请重新获取"}
        if(System.currentTimeMillis()-lastPoll<1800)return JSONObject().put("state","waiting")
        lastPoll=System.currentTimeMillis();var received=emptyList<Cookie>()
        val data=request("x/passport-login/web/qrcode/poll",mapOf("qrcode_key" to qrKey),passport=true,cookies={received=it})
        return when(data.optInt("code",-1)){
            0->{val allowed=setOf("SESSDATA","bili_jct","DedeUserID","DedeUserID__ckMd5","sid");val cookies=received.filter{it.name in allowed&&!it.value.contains(';')};check(cookies.any{it.name=="SESSDATA"}){"B站未返回有效登录，请重新扫码"};cookie=cookies.joinToString("; "){it.name+"="+it.value};save(cookie);qrKey="";JSONObject().put("state","success")}
            86090->JSONObject().put("state","scanned")
            86101->JSONObject().put("state","waiting")
            86038->{qrKey="";JSONObject().put("state","expired")}
            else->error("扫码状态暂不可用，请重新获取二维码")
        }
    }
    @Synchronized fun logout(){cookie="";qrKey="";save(null)}
    fun search(keyword:String,page:Int):JSONObject {
        require(keyword.isNotBlank()&&keyword.length<=80&&page in 1..1000){"请输入80字以内的关键词"}
        if(wbiKeys.isEmpty()||System.currentTimeMillis()>keysUntil)nav()
        check(wbiKeys.length==64){"B站搜索初始化失败，请稍后重试"}
        val data=request("x/web-interface/wbi/search/type",sign(mapOf("search_type" to "video","keyword" to keyword,"page" to page.toString(),"page_size" to "20"),wbiKeys,System.currentTimeMillis()/1000))
        check(!data.has("v_voucher")){"B站搜索需要验证，请扫码登录后重试；若已登录，请稍后再试"}
        val rows=data.optJSONArray("result")?:error("B站搜索暂不可用，请稍后重试")
        check(rows.length()<=50){"B站搜索结果格式已改变"}
        return JSONObject().put("items",JSONArray().apply{for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val bv=row.optString("bvid");if(validBvid(bv))put(JSONObject().put("bvid",bv).put("title",cleanTitle(row.optString("title"))).put("owner",row.optString("author").take(100)).put("views",viewCount(row.opt("play"))).put("duration",row.optString("duration")).put("available",true))}}).put("hasMore",page<data.optInt("numPages",page)).put("page",page)
    }
    fun folders(uid:String,page:Int):JSONObject {
        val owner=uid.ifBlank{cookie.split(';').map{it.trim()}.firstOrNull{it.startsWith("DedeUserID=")}?.substringAfter('=').orEmpty()}
        require(owner.matches(Regex("[0-9]{1,20}"))&&page in 1..1000){"请先扫码登录，或输入公开收藏夹所属用户的UID"}
        val d=request("x/v3/fav/folder/created/list",mapOf("up_mid" to owner,"pn" to "$page","ps" to "20"))
        val list=d.optJSONArray("list")?:JSONArray();check(list.length()<=20){"B站收藏夹分页格式已改变"}
        return JSONObject().put("items",JSONArray().apply{for(i in 0 until list.length()){val row=list.getJSONObject(i);put(JSONObject().put("id",row.getLong("id").toString()).put("title",row.optString("title").take(150)).put("count",row.optInt("media_count")))}}).put("hasMore",d.optBoolean("has_more")).put("page",page)
    }
    fun favorites(folder:String,page:Int):JSONObject {
        require(folder.matches(Regex("[0-9]{1,20}"))&&page in 1..1000){"收藏夹编号无效"}
        val d=request("x/v3/fav/resource/list",mapOf("media_id" to folder,"pn" to "$page","ps" to "20","platform" to "web"))
        val rows=d.optJSONArray("medias")?:JSONArray();check(rows.length()<=20){"B站收藏内容分页格式已改变"}
        return JSONObject().put("items",JSONArray().apply{for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val bv=row.optString("bvid");put(JSONObject().put("aid",row.optLong("id").toString()).put("bvid",bv).put("title",cleanTitle(row.optString("title"))).put("owner",row.optJSONObject("upper")?.optString("name").orEmpty().take(100)).put("views",row.optJSONObject("cnt_info")?.optLong("play",-1)?:-1).put("duration",row.optLong("duration")).put("available",validBvid(bv)&&row.optString("title")!="已失效视频"))}}).put("hasMore",d.optBoolean("has_more")).put("page",page).put("canManage",cookieValue("DedeUserID").isNotEmpty()&&d.optJSONObject("info")?.optLong("mid")?.toString()==cookieValue("DedeUserID"))
    }
    fun video(bvid:String):BiliVideo {
        require(validBvid(bvid)){"视频BV号无效"}
        val d=request("x/web-interface/view",mapOf("bvid" to bvid))
        val cid=d.getLong("cid");require(cid in 1..999_999_999_999_999L)
        return BiliVideo(bvid,cid,d.getLong("aid"),cleanTitle(d.getString("title")),d.getJSONObject("owner").getString("name").take(100),d.optLong("duration"),d.optJSONObject("stat")?.optLong("view",-1)?:-1)
    }
    /** One explicit order -> one set-style add; never removes from any other folder.
     * At most 10 folder pages (200 folders), 30s discovery budget, no write retries.
     * An uncertain create is recorded before submission and reconciled by name on the next order.
     */
    @Synchronized fun favoriteForKtv(bvid:String):JSONObject {
        val uid=cookieValue("DedeUserID");val csrf=cookieValue("bili_jct")
        check(uid.matches(Regex("[0-9]{1,20}"))&&csrf.isNotBlank()){"请重新登录B站后自动收藏"}
        val video=video(bvid);check(video.aid>0){"视频编号无效"};val key="bili_ktv_folder:"+uid
        var folder=readState(key).toLongOrNull()?.takeIf{it>0}?:0L
        if(folder>0){
            val info=request("x/v3/fav/folder/info",mapOf("media_id" to "$folder"))
            check(info.optString("title")=="窝牛KTV"&&info.optLong("mid")==uid.toLong()){"窝牛KTV收藏夹已变更，请在B站恢复名称后重试"}
        }else{
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);var complete=false
            for(page in 1..10){
                check(System.nanoTime()<deadline){"收藏夹查找超时，稍后点歌时再试"}
                val data=request("x/v3/fav/folder/created/list",mapOf("up_mid" to uid,"pn" to "$page","ps" to "20"))
                val rows=data.optJSONArray("list")?:JSONArray();check(rows.length()<=20){"收藏夹分页格式已改变"}
                for(i in 0 until rows.length()){val row=rows.getJSONObject(i);if(row.optString("title")=="窝牛KTV"){folder=row.getLong("id");break}}
                if(folder>0)break
                if(!data.optBoolean("has_more")){complete=true;break}
            }
            if(folder==0L){
                check(complete){"收藏夹超过200个，请在B站手动创建窝牛KTV收藏夹并移到前面"}
                check(readState(key+":creating")!="unknown"){"上次创建收藏夹结果待确认，请在B站确认窝牛KTV收藏夹后再次点歌"}
                writeState(key+":creating","unknown")
                val created=try{request("x/v3/fav/folder/add",form=mapOf("title" to "窝牛KTV","intro" to "在窝牛KTV点过的B站歌曲","privacy" to "1","csrf" to csrf))}catch(e:BiliApiRejection){writeState(key+":creating","");throw e}
                folder=created.getLong("id");check(folder>0)
            }
            writeState(key,"$folder");writeState(key+":creating","")
        }
        request("x/v3/fav/resource/deal",form=mapOf("rid" to "${video.aid}","type" to "2","add_media_ids" to "$folder","csrf" to csrf))
        return JSONObject().put("saved",true).put("folderId","$folder").put("folderTitle","窝牛KTV")
    }
    /** Removes only this video's membership in the selected, freshly verified own folder. */
    @Synchronized fun removeFavorite(folder:String,aid:String):JSONObject {
        require(folder.matches(Regex("[0-9]{1,20}"))&&aid.toLongOrNull()?.let{it>0}==true){"收藏标识无效"}
        val uid=cookieValue("DedeUserID");val csrf=cookieValue("bili_jct")
        check(uid.isNotEmpty()&&csrf.isNotEmpty()){"请先登录B站"}
        val info=request("x/v3/fav/folder/info",mapOf("media_id" to folder))
        check(info.optLong("mid").toString()==uid){"只能取消自己收藏夹中的收藏"}
        request("x/v3/fav/resource/deal",form=mapOf("rid" to aid,"type" to "2","del_media_ids" to folder,"csrf" to csrf))
        return JSONObject().put("removed",true)
    }
    private fun cookieValue(name:String)=cookie.split(';').map{it.trim()}.firstOrNull{it.startsWith("$name=")}?.substringAfter('=').orEmpty()
    /** One 6-minute segment; independent cancellable call so toggling off cancels network work. */
    suspend fun danmaku(cid:Long,segment:Int):List<BiliComment> = kotlinx.coroutines.suspendCancellableCoroutine { cont->
        require(cid in 1..999_999_999_999_999L&&segment in 1..1000)
        val url=apiBase.toHttpUrl().newBuilder().addPathSegments("x/v2/dm/web/seg.so").addQueryParameter("type","1").addQueryParameter("oid","$cid").addQueryParameter("segment_index","$segment").build()
        val call=client.newCall(Request.Builder().url(url).header("User-Agent",UA).header("Referer","https://www.bilibili.com/").apply{if(cookie.isNotEmpty())header("Cookie",cookie)}.build())
        cont.invokeOnCancellation{call.cancel()}
        call.enqueue(object:okhttp3.Callback {
            override fun onFailure(call:okhttp3.Call,e:java.io.IOException){if(cont.isActive)cont.resumeWith(Result.failure(IllegalStateException("弹幕连接失败，请关闭后重新开启")))}
            override fun onResponse(call:okhttp3.Call,response:okhttp3.Response){
                try{val rows=response.use{r->check(r.isSuccessful){"B站弹幕暂不可用"};val body=r.body?:error("弹幕响应为空");check(body.contentLength()<=2*1024*1024);check(!r.header("Content-Type").orEmpty().contains("json")){"B站弹幕要求验证或稍后重试"}
                    val bytes=body.byteStream().use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=2*1024*1024){"弹幕分段过大"};out.write(buffer,0,n)};out.toByteArray()};BiliDanmaku.decode(bytes)}
                    if(cont.isActive)cont.resumeWith(Result.success(rows))
                }catch(e:Exception){if(cont.isActive)cont.resumeWith(Result.failure(IllegalStateException("弹幕暂不可用，请关闭后重新开启")))}
            }
        })
    }
    fun source(bvid:String,cid:Long):PlaySource {
        require(validBvid(bvid)&&cid>0){"视频标识无效"}
        val d=request("x/player/playurl",mapOf("bvid" to bvid,"cid" to cid.toString(),"qn" to "64","fnval" to "1","fnver" to "0","fourk" to "0","platform" to "html5"))
        val streams=d.optJSONArray("durl")?:error("该视频暂未提供兼容的播放格式，请选择其他视频")
        check(streams.length()==1){"该视频为多段格式，暂不支持连续播放"}
        val url=streams.getJSONObject(0).getString("url").let{if(it.startsWith("http://"))"https://"+it.removePrefix("http://") else it}
        check(allowedMedia(url)){"B站返回了不支持的播放地址"}
        return PlaySource(url,mapOf("User-Agent" to UA,"Referer" to "https://www.bilibili.com/video/$bvid/"))
    }
}
