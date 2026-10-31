package com.pocketktv.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Independent implementation of the web protocol; no external token service or bundled account.
 * One explicit page (100 entries, 2MiB, 20 seconds) per request; no hidden all-pages iteration.
 */
class DomesticDrive(val provider:String,private val store:SecretStore,
                    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build(),private val cursorDb:LibraryDb?=null) {
    companion object {
        val labels=mapOf("quark" to "夸克网盘","uc" to "UC网盘","baidu" to "百度网盘","aliyun" to "阿里云盘")
        const val UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        fun tag(uid:String):String=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
        fun songId(provider:String,account:String,fid:String):String {
            require(provider in labels&&account.matches(Regex("[a-f0-9]{24}"))&&fid.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
            val digest=MessageDigest.getInstance("SHA-256").digest("$provider:$account:$fid".toByteArray())
            var n=0L;for(i in 0..6)n=(n shl 8) or (digest[i].toLong() and 255)
            return (9_000_000_000_000_000_000L+n).toString()
        }
        fun decodePage(j:JSONObject,parent:String):DrivePage {
            val rows=j.getJSONObject("data").getJSONArray("list");require(rows.length()<=100){"网盘分页超出100项"}
            val total=j.getJSONObject("metadata").getInt("_total");require(total>=0)
            return DrivePage((0 until rows.length()).map {i->val r=rows.getJSONObject(i);val id=r.getString("fid");require(id.matches(Regex("[a-zA-Z0-9_-]{1,128}")));val name=r.getString("file_name");require(name.length in 1..4096);DriveEntry(id,parent,name,!r.getBoolean("file"),id,r.optLong("size"))},total)
        }
    }
    init{require(provider in labels)}
    val label get()=labels.getValue(provider)
    val home get()=when(provider){"quark"->"https://pan.quark.cn";"uc"->"https://drive.uc.cn";"baidu"->"https://pan.baidu.com";else->"https://www.alipan.com"}
    private val api get()=when(provider){"quark"->"https://drive.quark.cn/1/clouddrive";"uc"->"https://pc-api.uc.cn/1/clouddrive";"baidu"->"https://pan.baidu.com";else->"https://api.alipan.com"}
    private val pr get()=if(provider=="quark")"ucpro" else "UCBrowser"
    private var credential=store.load()?.let{runCatching{JSONObject(it)}.getOrNull()}
    val account get()=credential?.optString("account").orEmpty()
    val loggedIn get()=account.isNotEmpty()
    private fun save(j:JSONObject){store.save(j.toString());credential=j}
    private fun request(url:String,cookie:String,body:JSONObject?=null,update:Boolean=true,headers:Map<String,String> = emptyMap()):JSONObject {
        val u=url.toHttpUrl();require(u.scheme=="https"&&u.host in setOf(home.toHttpUrl().host,api.toHttpUrl().host,"auth.alipan.com").filter{provider=="aliyun"||it!="auth.alipan.com"})
        val b=Request.Builder().url(u).header("User-Agent",UA).header("Referer",home+"/").header("Cookie",cookie)
        headers.forEach{(k,v)->b.header(k,v)}
        if(body!=null)b.post(body.toString().toRequestBody("application/json".toMediaType()))
        return client.newCall(b.build()).execute().use{r->
            check(r.isSuccessful){"$label 请求失败（HTTP ${r.code}），请重新登录或稍后重试"}
            val data=r.body?:error("$label 返回空响应");require(data.contentLength()<=2_097_152)
            val text=data.byteStream().use{input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192);while(true){val n=input.read(buf);if(n<0)break;check(out.size()+n<=2_097_152){"网盘响应超过2MiB预算"};out.write(buf,0,n)};out.toString("UTF-8")}
            val j=runCatching{JSONObject(text)}.getOrElse{error("$label 未返回有效数据")}
            check((!j.has("status")||j.optInt("status")==200)&&(!j.has("code")||j.optString("code") in listOf("0","OK"))&&j.optBoolean("success",true)&&j.optInt("errno",0)==0&&j.optInt("error_code",0)==0){"$label 登录失效或平台未允许此操作，请重新登录"}
            if(update&&credential!=null){val changes=Cookie.parseAll(u,r.headers).filter{it.name in setOf("__puus","__pus")};if(changes.isNotEmpty()){val parts=cookie.split(';').map{it.trim()}.filter{it.contains('=')}.associate{it.substringBefore('=') to it.substringAfter('=')}.toMutableMap();changes.forEach{parts[it.name]=it.value};save(JSONObject(credential.toString()).put("cookie",parts.entries.joinToString("; "){it.key+"="+it.value}))}}
            j
        }
    }
    suspend fun login(cookie:String)=withContext(Dispatchers.IO){
        require(cookie.length in 1..32768&&!cookie.contains('\n')&&!cookie.contains('\r')){"请先在官方页面扫码登录"}
        if(provider=="baidu"){
            val info=request("$home/api/user/getinfo?need_selfinfo=1",cookie,update=false)
            val records=info.getJSONArray("records");require(records.length()==1){"请先完成百度网盘登录"};val uid=records.getJSONObject(0).get("uk").toString();require(uid.isNotBlank())
            request("$home/api/list?dir=%2F&num=100&page=1&order=name&desc=0&web=1&clienttype=0",cookie,update=false)
            save(JSONObject().put("account",tag(uid)).put("cookie",cookie));return@withContext
        }
        require(provider!="aliyun"){"请使用阿里云盘网页登录"}
        val info=request("$home/account/info?fr=pc&platform=pc",cookie,update=false).getJSONObject("data")
        val uid=info.optString("uid").ifBlank{cookie.split(';').map{it.trim()}.firstOrNull{it.substringBefore('=')=="__uid"}?.substringAfter('=').orEmpty()};check(info.length()>0&&uid.isNotBlank()&&uid.length<=200){"尚未登录成功，请在官方页面扫码并确认"}
        // Validate access before saving or changing the current account.
        request(listUrl("0",0),cookie,update=false).let{decodePage(it,"0")}
        save(JSONObject().put("account",tag(uid)).put("cookie",cookie))
    }
    private val memory=mutableMapOf<String,String>()
    private fun state(key:String)=cursorDb?.setting(key)?:memory[key].orEmpty()
    private fun state(key:String,value:String){if(cursorDb!=null)cursorDb.setting(key,value,true) else {require(memory.size<2000||key in memory){"目录会话超过预算，请重新打开网盘"};memory[key]=value}}
    private fun aliHeaders(j:JSONObject=credential?:error("请登录阿里云盘")):Map<String,String>{val h=j.getJSONObject("headers");return h.keys().asSequence().associateWith{h.getString(it)}}
    suspend fun loginAli(headers:Map<String,String>,refreshToken:String="")=withContext(Dispatchers.IO){
        require(provider=="aliyun"&&headers["Authorization"].orEmpty().startsWith("Bearer ")&&headers["X-Device-Id"].orEmpty().isNotBlank()&&headers["X-Signature"].orEmpty().isNotBlank()){"请在官方阿里云盘页面完成登录，等文件列表出现后再确认"}
        val safe=headers.filterKeys{it in setOf("Authorization","X-Device-Id","X-Signature","X-Canary")}.toMutableMap();safe["Origin"]=home
        val info=request("$api/v2/user/get","",JSONObject(),false,safe);val uid=info.getString("user_id");val driveId=info.optString("resource_drive_id").ifBlank{info.optString("default_drive_id")};require(driveId.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        request("$api/v2/file/list","",JSONObject().put("drive_id",driveId).put("parent_file_id","root").put("limit",100).put("order_by","name").put("order_direction","ASC"),false,safe)
        save(JSONObject().put("account",tag(uid)).put("drive_id",driveId).put("headers",JSONObject(safe as Map<*,*>)).put("refresh_token",refreshToken.take(16384)).put("expires",System.currentTimeMillis()+20*60_000))
    }
    private fun refreshAli(){
        val c=credential?:error("请登录阿里云盘");val token=c.optString("refresh_token");if(token.isBlank()||c.optLong("expires")>System.currentTimeMillis())return
        val j=request("https://auth.alipan.com/v2/account/token","",JSONObject().put("grant_type","refresh_token").put("refresh_token",token),false)
        val updated=JSONObject(c.toString());updated.getJSONObject("headers").put("Authorization","Bearer "+j.getString("access_token"));updated.put("refresh_token",j.optString("refresh_token",token)).put("expires",System.currentTimeMillis()+j.optLong("expires_in",3600).coerceIn(60,86400)*1000-60_000);save(updated)
    }
    private fun aliRequest(path:String,body:JSONObject):JSONObject{refreshAli();return request(api+path,"",body,headers=aliHeaders())}
    private fun aliPage(parent:String,offset:Int):DrivePage {
        val key="cloud_cursor:aliyun:$account:"+tag(parent);val saved=state(key).takeIf{it.isNotBlank()}?.let{JSONObject(it)}
        val marker=when{offset==0->"";saved?.optInt("at")==offset->saved.getString("marker");saved?.optInt("nextAt")==offset->saved.getString("next");else->error("阿里分页断点不可用，请返回目录首页重试")}
        require(marker.length<=8192);val j=aliRequest("/v2/file/list",JSONObject().put("drive_id",credential!!.getString("drive_id")).put("parent_file_id",if(parent=="0")"root" else parent).put("marker",marker).put("limit",100).put("order_by","name").put("order_direction","ASC"))
        val rows=j.getJSONArray("items");require(rows.length()<=100);val next=j.optString("next_marker");require(next.length<=8192);check(next.isEmpty()||rows.length()==100){"阿里返回不完整分页，已保留断点"}
        val entries=(0 until rows.length()).map{i->val r=rows.getJSONObject(i);val id=r.getString("file_id");require(id.matches(Regex("[a-zA-Z0-9_-]{1,128}")));val name=r.getString("name");require(name.length in 1..4096);DriveEntry(id,parent,name,r.getString("type")=="folder",id,r.optLong("size"))}
        state(key,JSONObject().put("at",offset).put("marker",marker).put("nextAt",offset+100).put("next",next).toString())
        return DrivePage(entries,offset+rows.length()+if(next.isBlank())0 else 1)
    }
    private fun baiduPage(parent:String,offset:Int):DrivePage {
        val path=if(parent=="0")"/" else state("cloud_baidu_dir:$account:$parent").also{require(it.startsWith("/")&&it.length<=4096){"百度目录路径缺失，请从根目录重新选择"}}
        val url="$api/api/list".toHttpUrl().newBuilder().addQueryParameter("dir",path).addQueryParameter("num","100").addQueryParameter("page",(offset/100+1).toString()).addQueryParameter("order","name").addQueryParameter("desc","0").addQueryParameter("web","1").addQueryParameter("clienttype","0").build()
        val j=request(url.toString(),credential!!.getString("cookie"));val rows=j.getJSONArray("list");require(rows.length()<=100)
        val entries=(0 until rows.length()).map{i->val r=rows.getJSONObject(i);val id=r.get("fs_id").toString();require(id.matches(Regex("[0-9]{1,30}")));val remote=r.getString("path");require(remote.startsWith("/")&&remote.length<=4096);val folder=r.getInt("isdir")==1;if(folder)state("cloud_baidu_dir:$account:$id",remote);DriveEntry(id,parent,r.getString("server_filename").also{require(it.length in 1..4096)},folder,id,r.optLong("size"),remote)}
        return DrivePage(entries,offset+entries.size+if(entries.size==100)1 else 0)
    }
    private fun listUrl(parent:String,offset:Int):String {
        require(parent.matches(Regex("[a-zA-Z0-9_-]{1,128}"))&&offset>=0&&offset%100==0)
        return "$api/file/sort".toHttpUrl().newBuilder().addQueryParameter("pr",pr).addQueryParameter("fr","pc").addQueryParameter("pdir_fid",parent).addQueryParameter("_page",(offset/100+1).toString()).addQueryParameter("_size","100").addQueryParameter("_fetch_total","1").addQueryParameter("_sort","file_type:asc,file_name:asc").build().toString()
    }
    suspend fun list(parent:String,offset:Int)=withContext(Dispatchers.IO){check(loggedIn){"请先登录$label"};require(offset>=0&&offset%100==0);if(provider=="aliyun")return@withContext aliPage(parent,offset);if(provider=="baidu")return@withContext baiduPage(parent,offset);decodePage(request(listUrl(parent,offset),credential!!.getString("cookie")),parent).also{check(it.entries.size==100||offset+it.entries.size>=it.total){"网盘返回不完整分页，已保留断点"}}}
    suspend fun source(song:Song):PlaySource=withContext(Dispatchers.IO){
        check(loggedIn){"请先登录$label"};val identity=Uri.parse(song.path)
        require(song.metadataProvider==provider&&identity.scheme=="woniu-cloud"&&identity.host==provider&&identity.pathSegments.firstOrNull()==account){"请登录导入这首歌时使用的$label 账号"}
        require(song.id==songId(provider,account,song.pickCode)){"网盘歌曲标识不一致，请重新导入这一首"}
        if(provider=="aliyun"){
            val j=aliRequest("/v2/file/get_download_url",JSONObject().put("drive_id",credential!!.getString("drive_id")).put("file_id",song.pickCode).put("expire_sec",14400))
            val target=j.getString("url").toHttpUrl();require(target.scheme=="https"&&(target.host.endsWith(".aliyuncs.com")||target.host.endsWith(".alipan.com")||target.host.endsWith(".aliyundrive.com"))){"阿里返回未支持的播放域名"}
            return@withContext PlaySource(target.toString(),mapOf("User-Agent" to UA,"Referer" to "$home/"))
        }
        if(provider=="baidu"){
            val path=identity.getQueryParameter("remote").orEmpty();require(path.startsWith("/")&&path.length<=4096){"百度文件路径缺失，请重新导入这一首"}
            val url="https://pcs.baidu.com/rest/2.0/pcs/file".toHttpUrl().newBuilder().addQueryParameter("method","download").addQueryParameter("app_id","250528").addQueryParameter("path",path).build()
            val headers=mapOf("User-Agent" to "netdisk;P2SP;3.0.0.112","Cookie" to credential!!.getString("cookie"))
            client.newCall(Request.Builder().url(url).apply{headers.forEach{(k,v)->header(k,v)}}.header("Range","bytes=0-0").build()).execute().use{r->
                if(r.code in 300..399){val target=url.resolve(r.header("Location")?:error("百度未返回播放地址"))?:error("百度播放地址无效");require(target.scheme=="https"&&(target.host.endsWith(".baidupcs.com")||target.host.endsWith(".baidu.com"))){"百度返回未支持的播放域名"};return@withContext PlaySource(target.toString(),mapOf("User-Agent" to headers.getValue("User-Agent")))}
                check(r.isSuccessful&&r.header("Content-Type").orEmpty().let{!it.contains("json")&&!it.contains("html")}){"百度未开放原文件播放，请检查网盘账号权限或重新登录"}
                return@withContext PlaySource(url.toString(),headers)
            }
        }
        val url="$api/file/download".toHttpUrl().newBuilder().addQueryParameter("pr",pr).addQueryParameter("fr","pc").build()
        val j=request(url.toString(),credential!!.getString("cookie"),JSONObject().put("fids",JSONArray().put(song.pickCode)))
        val target=j.getJSONArray("data").getJSONObject(0).getString("download_url").toHttpUrl()
        val suffix=if(provider=="quark")"quark.cn" else "uc.cn"
        require(target.scheme=="https"&&(target.host==suffix||target.host.endsWith(".$suffix"))){"平台返回未支持的播放域名，未发送登录信息"}
        PlaySource(target.toString(),mapOf("User-Agent" to UA,"Referer" to "$home/","Cookie" to credential!!.getString("cookie")))
    }
}
