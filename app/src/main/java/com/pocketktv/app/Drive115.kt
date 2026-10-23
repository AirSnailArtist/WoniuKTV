package com.pocketktv.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DriveEntry(val id: String, val parent: String, val name: String, val folder: Boolean, val pickCode: String, val size: Long)
data class DrivePage(val entries: List<DriveEntry>, val total: Int)
data class LoginQr(val uid: String, val time: String, val sign: String, val content: String, val verifier: String? = null)
data class PlaySource(val url: String, val headers: Map<String,String>)

/** Session QR and read-only file access adapted from MIT SheltonZhu/115driver.
 * Optional PKCE flow follows Apache-2.0 xhofe/115-sdk-go and MIT 115-plus-desktop.
 * No shared AppID, secret or account is embedded. No third-party token service is used.
 */
class Drive115(private val store: SecretStore, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).followRedirects(false).build()) {
    companion object { const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" }
    private var lastRequest = 0L
    var credentials = store.load()?.let { runCatching { JSONObject(it) }.getOrNull() }
        private set
    val loggedIn get() = credentials != null
    val accountId get() = credentials?.optString("account_id", "") ?: ""
    private fun save(json: JSONObject) { store.save(json.toString()); credentials = json }
    fun logout() { credentials = null; store.clear() }

    private suspend fun request(url: String, form: Map<String,String>? = null, auth: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        if (auth) {
            val wait = 1100L - (System.currentTimeMillis()-lastRequest)
            if (wait > 0) delay(wait)
            lastRequest = System.currentTimeMillis()
        }
        val builder = Request.Builder().url(url).header("User-Agent", UA).header("Referer","https://115.com/")
        if (auth) {
            val credential = credentials ?: error("请先登录 115")
            if (credential.optString("mode") == "open") builder.header("Authorization", "Bearer " + credential.getString("access_token"))
            else builder.header("Cookie", credential.getString("cookie"))
        }
        if (form != null) builder.post(FormBody.Builder().apply { form.forEach { (k,v) -> add(k,v) } }.build())
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("115 请求失败（HTTP ${response.code}），请稍后重试")
            val text = response.body?.string() ?: error("115 返回空响应")
            val json = try { JSONObject(text) } catch (_: Exception) { error("115 返回非 JSON 数据，请稍后重试") }
            if ((json.has("state") && !stateAccepted(json.opt("state"))) || (json.has("code") && json.optInt("code") != 0)) {
                val code = json.optInt("errno", json.optInt("code"))
                val message = json.optString("message",json.optString("msg",json.optString("error","请求未成功")))
                throw DriveError(code, "115：${message.take(120)}（$code）")
            }
            if(url.contains("/downurl")) {
                val cookies=okhttp3.Cookie.parseAll(response.request.url,response.headers)
                json.put("_download_cookies",cookies.joinToString("; "){it.name+"="+it.value})
            }
            json
        }
    }
    suspend fun startLogin(appId: String = ""): LoginQr {
        if (appId.isBlank()) {
            val data = request("https://qrcodeapi.115.com/api/1.0/web/1.0/token/").getJSONObject("data")
            return LoginQr(data.getString("uid"),data.get("time").toString(),data.getString("sign"),data.getString("qrcode"))
        }
        val verifier = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(SessionCipher.newKey()+SessionCipher.newKey()+SessionCipher.newKey())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        val data = request("https://passportapi.115.com/open/authDeviceCode",mapOf("client_id" to appId,
            "code_challenge" to java.util.Base64.getEncoder().encodeToString(digest),"code_challenge_method" to "sha256")).getJSONObject("data")
        return LoginQr(data.getString("uid"),data.get("time").toString(),data.getString("sign"),data.getString("qrcode"),verifier)
    }
    suspend fun poll(qr: LoginQr): Int {
        val url = "https://qrcodeapi.115.com/get/status/".toHttpUrl().newBuilder()
            .addQueryParameter("uid",qr.uid).addQueryParameter("time",qr.time).addQueryParameter("sign",qr.sign)
            .addQueryParameter("_",System.currentTimeMillis().toString()).build().toString()
        return qrStatus(request(url))
    }
    suspend fun finishLogin(qr: LoginQr) {
        if (qr.verifier != null) {
            val data = request("https://passportapi.115.com/open/deviceCodeToToken", mapOf("uid" to qr.uid,"code_verifier" to qr.verifier)).getJSONObject("data")
            data.put("mode","open"); data.put("account_id",data.optString("user_id"))
            save(data)
            val info = request("https://proapi.115.com/open/user/info",auth=true).getJSONObject("data")
            save(data.put("account_id",info.get("user_id").toString()))
        } else {
            // TV session avoids displacing the phone session used for scanning. Another TV login may be displaced.
            val data = request("https://passportapi.115.com/app/1.0/tv/1.0/login/qrcode",mapOf("account" to qr.uid,"app" to "tv")).getJSONObject("data")
            val cookie = data.getJSONObject("cookie")
            listOf("UID","CID","SEID").forEach { require(cookie.optString(it).isNotBlank()) { "115 未返回完整登录凭据，请重新扫码" } }
            val value = listOf("UID","CID","SEID","KID").filter { cookie.optString(it).isNotBlank() }
                .joinToString("; ") { "$it=${cookie.getString(it)}" }
            val id = cookie.getString("UID").substringBefore('_')
            save(JSONObject().put("mode","session").put("cookie",value).put("account_id",id))
        }
    }
    private suspend fun authorized(url: String, form: Map<String,String>? = null): JSONObject {
        return try { request(url,form,true) } catch (e: DriveError) {
            if (credentials?.optString("mode") == "open" && e.code in setOf(40140116,40140117,40140118,40140119,40140121)) {
                val token = request("https://passportapi.115.com/open/refreshToken",mapOf("refresh_token" to credentials!!.getString("refresh_token"))).getJSONObject("data")
                token.put("mode","open").put("account_id",accountId); save(token)
                request(url,form,true)
            } else throw e
        }
    }
    suspend fun list(cid: String, offset: Int = 0): DrivePage {
        val open = credentials?.optString("mode") == "open"
        val base = if (open) "https://proapi.115.com/open/ufile/files" else "https://webapi.115.com/files"
        val url = base.toHttpUrl().newBuilder().addQueryParameter("cid",cid).addQueryParameter("offset",offset.toString())
            .addQueryParameter("limit","500").addQueryParameter("show_dir","1").addQueryParameter("o","file_name")
            .addQueryParameter("asc","1").addQueryParameter("custom_order","1").addQueryParameter("format","json").build().toString()
        val json = authorized(url)
        return parsePage(json,cid,open)
    }
    suspend fun source(pickCode: String): PlaySource {
        val headers = linkedMapOf("User-Agent" to UA,"Referer" to "https://115.com/")
        val json = if (credentials?.optString("mode") == "open") {
            val response=authorized("https://proapi.115.com/open/ufile/downurl", mapOf("pick_code" to pickCode))
            response.optString("_download_cookies").takeIf{it.isNotBlank()}?.let{headers["Cookie"]=it}
            response.getJSONObject("data")
        } else {
            val key = SessionCipher.newKey()
            val body = SessionCipher.encode(JSONObject().put("pickcode",pickCode).toString(),key)
            val data = authorized("https://proapi.115.com/app/chrome/downurl",mapOf("data" to body))
            headers["Cookie"]=listOf(credentials!!.getString("cookie"),data.optString("_download_cookies")).filter{it.isNotBlank()}.joinToString("; ")
            JSONObject(SessionCipher.decode(data.getString("data"),key))
        }
        val entry = json.keys().asSequence().mapNotNull { json.optJSONObject(it) }.firstOrNull { it.has("url") } ?: error("115 未提供可播放地址")
        val urlValue = entry.opt("url")
        val url = if (urlValue is JSONObject) urlValue.optString("url") else urlValue?.toString().orEmpty()
        require(url.startsWith("https://")) { "115 未返回 HTTPS 播放地址" }
        val host=url.toHttpUrl().host
        if(!is115Host(host)) headers.remove("Cookie")
        return PlaySource(url, headers)
    }
    suspend fun <T> downloadCatalog(pickCode:String, consume:suspend(java.io.InputStream)->T):T {
        val source=source(pickCode)
        return withContext(Dispatchers.IO){
            val request=Request.Builder().url(source.url).apply{source.headers.forEach{(k,v)->header(k,v)}}.build()
            client.newBuilder().callTimeout(120,TimeUnit.SECONDS).build().newCall(request).execute().use{response->
                require(response.isSuccessful){"目录下载失败（HTTP ${response.code}）"}
                val body=response.body?:error("目录文件为空")
                require(body.contentLength()<=CatalogCodec.MAX_BYTES){"目录文件超过 128MB 限制"}
                consume(body.byteStream())
            }
        }
    }
    /** Uploads only the generated catalog, under a unique name, through the normal web upload flow.
     * Protocol reference: p115client upload_sample_init/upload_file_sample; independent Kotlin implementation.
     * This never modifies, moves or overwrites the user's media files.
     */
    suspend fun catalogFolder():String {
        var offset=0;var pages=0;val deadline=android.os.SystemClock.elapsedRealtime()+60_000
        do{require(++pages<=10&&android.os.SystemClock.elapsedRealtime()<deadline){"115根目录查询达到预算，请缩小目录范围后重试"};val page=list("0",offset);page.entries.firstOrNull{it.folder&&it.name=="窝牛家庭KTV"}?.let{return it.id}
            offset+=page.entries.size;if(offset>=page.total||page.entries.isEmpty())break
        }while(true)
        val result=authorized("https://webapi.115.com/files/add",mapOf("pid" to "0","cname" to "窝牛家庭KTV"))
        return result.optString("cid").ifBlank{result.optJSONObject("data")?.optString("cid").orEmpty()}.also{require(it.matches(Regex("[0-9]{1,30}"))){"115 未返回备份目录标识"}}
    }
    suspend fun uploadCatalog(file:java.io.File,cid:String){
        require(file.length() in 1..CatalogCodec.MAX_BYTES){"目录文件为空或超过大小限制"}
        require(cid.matches(Regex("[0-9]{1,30}"))){"无效的目标目录"}
        require(credentials?.optString("mode")=="session"){"当前官方授权模式暂不支持上传，可先导出 JSON 手动上传到 115"}
        val init=request("https://uplb.115.com/3.0/sampleinitupload.php",mapOf("filename" to file.name,
            "filesize" to file.length().toString(),"target" to "U_1_$cid"),true)
        val data=if(init.has("host"))init else init.optJSONObject("data")?:error("115 未提供目录上传地址")
        val host=data.getString("host").replaceFirst("http://","https://")
        val parsed=host.toHttpUrl()
        require(parsed.scheme=="https"&&(parsed.host.endsWith(".aliyuncs.com")||is115Host(parsed.host))){"115 返回的上传地址不受支持，请先导出到本机"}
        val multipart=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("name",file.name).addFormDataPart("key",data.getString("object"))
            .addFormDataPart("policy",data.getString("policy")).addFormDataPart("OSSAccessKeyId",data.getString("accessid"))
            .addFormDataPart("success_action_status","200").addFormDataPart("callback",data.getString("callback"))
            .addFormDataPart("signature",data.getString("signature"))
            .addFormDataPart("file",file.name,file.asRequestBody("application/json".toMediaType())).build()
        withContext(Dispatchers.IO){
            // Signed upload fields go only to the approved storage endpoint; account cookies are not forwarded.
            client.newCall(Request.Builder().url(parsed).header("User-Agent",UA).post(multipart).build()).execute().use{response->
                require(response.isSuccessful){"目录上传失败（HTTP ${response.code}）"}
                val result=runCatching{JSONObject(response.body?.string().orEmpty())}.getOrNull()
                require(stateAccepted(result?.opt("state"))){"上传结果未确认，请到 115 检查是否已生成目录清单"}
            }
        }
    }
    class DriveError(val code: Int, message: String): Exception(message)
}

internal fun qrStatus(json: JSONObject): Int = json.optJSONObject("data")?.optInt("status",0) ?: 0

internal fun is115Host(host:String)=listOf("115.com","115cdn.com","115cdn.net").any{host==it||host.endsWith(".$it")}
internal fun stateAccepted(value:Any?)=value==true || value?.toString()=="1" || value?.toString().equals("true",true)

/** Web and Open API have different field names; do not coerce directory IDs to file IDs. */
internal fun parsePage(json: JSONObject, parent: String, open: Boolean): DrivePage {
    val data = json.optJSONArray("data") ?: error("115 文件列表格式不受支持")
    val list = (0 until data.length()).map { index ->
        val item = data.getJSONObject(index)
        if (open) {
            val folder = item.optString("file_category") == "0" || item.optInt("is_directory",0) == 1
            DriveEntry(item.get("file_id").toString(),parent,item.getString("file_name"),folder,item.optString("pick_code"),item.optLong("file_size"))
        } else {
            val folder = !item.has("fid") || item.optString("fid").isBlank()
            DriveEntry(if(folder) item.get("cid").toString() else item.get("fid").toString(),parent,item.getString("n"),folder,item.optString("pc"),item.optLong("s"))
        }
    }
    return DrivePage(list,json.optInt("count",list.size))
}
