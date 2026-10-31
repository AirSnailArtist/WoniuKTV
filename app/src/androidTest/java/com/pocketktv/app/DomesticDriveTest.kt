package com.pocketktv.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DomesticDriveTest {
 @Test fun providersKeepSeparateIdsAndResumeWithoutTouching115()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val name="domestic-fixture-${UUID.randomUUID()}.db";val db=LibraryDb(context,name)
  val stores=mutableListOf<SecretStore>();val file=File.createTempFile("domestic-fixture-",".json",context.cacheDir)
  try {
   db.setting("account","fixture-115",true);db.setting("scan_done","1",true)
   val original=db.rememberBilibili(BiliVideo("BV1GJ411x7h7",987,1,"原有歌曲","歌手",100))
   val ids=mutableSetOf<String>()
   for(provider in listOf("quark","uc")){
    var downloads=0;var badHost=false
    val client=OkHttpClient.Builder().addInterceptor {chain->
     val r=chain.request();assertEquals("fixture=session; __uid=fixture-user",r.header("Cookie"));val payload=when{
      r.url.encodedPath=="/account/info"->"""{"success":true,"code":"OK","data":{"nickname":"fixture-nickname","config":{}}}"""
      r.url.encodedPath.endsWith("/file/sort")-> {assertEquals("100",r.url.queryParameter("_size"));"""{"status":200,"code":0,"data":{"list":[{"fid":"same-file-id","file_name":"歌手 - 歌曲.mp4","file":true,"size":123}]},"metadata":{"_total":1}}"""}
      r.url.encodedPath.endsWith("/file/download")-> {downloads++;val domain=if(badHost)"example.com" else if(provider=="quark")"download.quark.cn" else "download.uc.cn";"""{"status":200,"code":0,"data":[{"download_url":"https://$domain/fixture.mp4"}]}"""}
      else->error("Unexpected endpoint")
     };Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(payload.toResponseBody()).build()
    }.build()
    val store=SecretStore(context,"domestic-fixture-${UUID.randomUUID()}");stores.add(store);val drive=DomesticDrive(provider,store,client);drive.login("fixture=session; __uid=fixture-user")
    val scan=DomesticScan(db,drive);scan.start("0","/");assertEquals(1,scan.slice{});assertNull(scan.next());assertEquals(0,scan.slice{})
    val id=DomesticDrive.songId(provider,drive.account,"same-file-id");assertTrue(ids.add(id));val song=db.song(id)!!;assertEquals(provider,song.metadataProvider);assertTrue(song.path.startsWith("woniu-cloud://$provider/"));assertNotNull(db.song(original.id))
    val source=drive.source(song);assertEquals("fixture=session; __uid=fixture-user",source.headers["Cookie"]);assertTrue(source.url.endsWith("fixture.mp4"));badHost=true;var rejected=false;try{drive.source(song)}catch(e:IllegalArgumentException){rejected=true};assertTrue(rejected);assertEquals(2,downloads)
    assertEquals(id,DomesticDrive.songId(provider,drive.account,"same-file-id"));assertNotEquals(id,DomesticDrive.songId(provider,DomesticDrive.tag("other-account"),"same-file-id"))
   }
   CatalogCodec.export(db,file,"fixture-115");assertFalse(file.readText().contains("fixture=session"));file.inputStream().use{CatalogCodec.import(db,it,"fixture-115")}
   for(id in ids)assertNotNull(db.song(id));assertEquals(3,db.count())
   val plan=db.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT folder FROM domestic_scan WHERE provider=? AND account=? AND done=0 LIMIT 1",arrayOf("quark",DomesticDrive.tag("fixture-user"))).use{c->buildString{while(c.moveToNext())append(c.getString(3))}};assertTrue(plan.contains("domestic_scan_pending"))
  }finally{stores.forEach{it.clear()};db.close();context.deleteDatabase(name);file.delete()}
 }
 @Test fun baiduAndAliyunListImportPlaybackAndRefreshStayProviderScoped()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val name="allcloud-fixture-${UUID.randomUUID()}.db";val db=LibraryDb(context,name);val stores=mutableListOf<SecretStore>()
  try {
   db.setting("account","fixture-owner",true)
   for(provider in listOf("baidu","aliyun")){
    var refreshes=0;var markerSeen=false
    val client=OkHttpClient.Builder().followRedirects(false).addInterceptor{chain->
     val r=chain.request();val url=r.url;var code=200;var location=""
     val body=when{
      url.encodedPath=="/api/user/getinfo"->"""{"errno":0,"records":[{"uk":12345}]}"""
      url.encodedPath=="/api/list"->{assertEquals("100",url.queryParameter("num"));"""{"errno":0,"list":[{"fs_id":123,"server_filename":"歌手 - 歌曲.mp4","isdir":0,"path":"/KTV/歌手 - 歌曲.mp4","size":123}]}"""}
      url.host=="pcs.baidu.com"->{assertEquals("bytes=0-0",r.header("Range"));assertFalse(url.toString().contains("BDUSS"));code=302;location="https://fixture.baidupcs.com/fixture.mp4";""}
      url.host=="auth.alipan.com"->{refreshes++;"""{"access_token":"new-token","refresh_token":"new-refresh","expires_in":3600}"""}
      url.encodedPath=="/v2/user/get"->"""{"user_id":"fixture-user","default_drive_id":"123"}"""
      url.encodedPath=="/v2/file/list"->{val payload=JSONObject(r.body!!.let{val b=okio.Buffer();it.writeTo(b);b.readUtf8()});markerSeen=markerSeen||payload.optString("marker")=="next-marker";"""{"items":[{"file_id":"ali-file","name":"歌手 - 歌曲.mp4","type":"file","size":123}],"next_marker":""}"""}
      url.encodedPath=="/v2/file/get_download_url"->"""{"url":"https://fixture.aliyuncs.com/fixture.mp4"}"""
      else->error("Unexpected endpoint")
     }
     Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("fixture").apply{if(location.isNotBlank())header("Location",location)}.body(body.toResponseBody()).build()
    }.build()
    val store=SecretStore(context,"allcloud-fixture-${UUID.randomUUID()}");stores.add(store);var drive=DomesticDrive(provider,store,client,db)
    if(provider=="baidu")drive.login("BDUSS=fixture-session")else drive.loginAli(mapOf("Authorization" to "Bearer fixture-token","X-Device-Id" to "fixture-device","X-Signature" to "fixture-signature"),"fixture-refresh")
    val entries=drive.list("0",0);assertEquals(1,entries.entries.size)
    val song=db.rememberDomestic(provider,drive.account,entries.entries.first());assertEquals(provider,song.metadataProvider)
    val source=drive.source(song);assertFalse(source.headers.containsKey("Authorization"));assertFalse(source.headers.containsKey("Cookie"));assertTrue(source.url.startsWith("https://fixture."))
    if(provider=="baidu")assertEquals("/KTV/歌手 - 歌曲.mp4",android.net.Uri.parse(song.path).getQueryParameter("remote"))
    else {
     val saved=JSONObject(store.load()!!).put("expires",0);store.save(saved.toString());drive=DomesticDrive(provider,store,client,db);drive.list("0",0);assertEquals(1,refreshes)
     // A persisted current marker can resume without enumerating earlier pages.
     db.setting("cloud_cursor:aliyun:${drive.account}:"+DomesticDrive.tag("0"),JSONObject().put("at",100).put("marker","next-marker").put("nextAt",200).put("next","").toString(),true)
     drive=DomesticDrive(provider,store,client,db);drive.list("0",100);assertTrue(markerSeen)
    }
   }
   assertEquals(2,db.count())
  }finally{stores.forEach{it.clear()};db.close();context.deleteDatabase(name)}
 }
 @Test fun rejectsOversizedPagesAndMalformedIdentifiers(){
  val rows=org.json.JSONArray();repeat(101){rows.put(JSONObject().put("fid","x").put("file_name","x").put("file",true))}
  var rejected=false;try{DomesticDrive.decodePage(JSONObject().put("data",JSONObject().put("list",rows)).put("metadata",JSONObject().put("_total",101)),"0")}catch(e:IllegalArgumentException){rejected=true};assertTrue(rejected)
 }
}
