package com.pocketktv.app
import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.NetworkInterface
import java.net.Inet4Address
import java.util.UUID

/** Local-network pairing page: no account cookies or media URLs are exposed. */
class PhoneSongbookServer(private val context:Context,private val photo:(String)->ByteArray?={null},private val bridge:(String,String,String)->String):NanoHTTPD("0.0.0.0",0){
 private val pairing=UUID.randomUUID().toString().replace("-","")
 val pairingUrl:String? get(){val interfaces=NetworkInterface.getNetworkInterfaces().toList().sortedBy{if(it.name.startsWith("wlan"))0 else 1}
  val address=interfaces.flatMap{it.inetAddresses.toList()}.filterIsInstance<Inet4Address>().firstOrNull{!it.isLoopbackAddress&&it.isSiteLocalAddress}?.hostAddress?:return null
  return "http://$address:$listeningPort/?token=$pairing"
 }
 override fun serve(session:IHTTPSession):Response {
  val supplied=session.headers["x-woniu-pair"]?:session.parameters["token"]?.firstOrNull().orEmpty()
  if(!java.security.MessageDigest.isEqual(supplied.toByteArray(),pairing.toByteArray()))return response(Response.Status.UNAUTHORIZED,"application/json","{\"ok\":false,\"message\":\"请重新扫描播放设备上的点歌二维码\"}")
  return try{when{
   session.uri in listOf("/phone-swiper.js","/phone-swiper.css")&&session.method==Method.GET->response(Response.Status.OK,if(session.uri.endsWith(".js"))"application/javascript" else "text/css",context.assets.open(session.uri.removePrefix("/")).bufferedReader().readText())
   session.uri=="/"&&session.method==Method.GET->response(Response.Status.OK,"text/html",context.assets.open("phone-songbook.html").bufferedReader().readText())
   session.uri in listOf("/api/recommendations","/api/phone-songs","/api/phone-artists","/api/phone-queue")&&session.method==Method.GET->{
    val route=when(session.uri){"/api/recommendations"->"/recommendations";"/api/phone-songs"->"/phone/songs";"/api/phone-queue"->"/phone/queue";else->"/phone/artists"}
    val query=listOf("after","keyword","artist","scope","refresh").mapNotNull{key->session.parameters[key]?.firstOrNull()?.take(100)?.let{key+"="+android.net.Uri.encode(it)}}.joinToString("&")
    response(Response.Status.OK,"application/json",bridge(route+"?"+query,"GET",""))
   }
   session.uri in listOf("/api/recommendations/dismiss","/api/recommendations/backup","/api/phone-state")&&session.method==Method.POST->{
    require((session.headers["content-length"]?.toLongOrNull()?:0)<=8192);val body=hashMapOf<String,String>();session.parseBody(body)
    response(Response.Status.OK,"application/json",bridge(if(session.uri=="/api/phone-state")"/phone/state" else session.uri.removePrefix("/api"),"POST",body["postData"].orEmpty()))
   }
   session.uri=="/api/chart-candidates"&&session.method==Method.GET->response(Response.Status.OK,"application/json",bridge("/chart/candidates?title="+android.net.Uri.encode(session.parameters["title"]?.firstOrNull().orEmpty().take(150)),"GET",""))
   session.uri=="/api/chart-match"&&session.method==Method.POST->{require((session.headers["content-length"]?.toLongOrNull()?:0)<=8192);val body=hashMapOf<String,String>();session.parseBody(body);response(Response.Status.OK,"application/json",bridge("/chart/match","POST",body["postData"].orEmpty()))}
   session.uri=="/api/discovery"&&session.method==Method.GET->response(Response.Status.OK,"application/json",bridge("/discovery"+(if(session.parameters["refresh"]?.firstOrNull()=="1")"?refresh=1" else ""),"GET",""))
   session.uri=="/api/artists"&&session.method==Method.GET->response(Response.Status.OK,"application/json",bridge("/browse/artists?scope="+android.net.Uri.encode(session.parameters["scope"]?.firstOrNull()?:"chinese"),"GET",""))
   session.uri=="/api/artist-songs"&&session.method==Method.GET->{val name=session.parameters["name"]?.firstOrNull().orEmpty().take(80);val offset=session.parameters["offset"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0)?:0;response(Response.Status.OK,"application/json",bridge("/browse/songs?artist="+android.net.Uri.encode(name)+"&limit=20&offset=$offset","GET",""))}
   session.uri=="/api/artist-photo"&&session.method==Method.GET->{val bytes=photo(session.parameters["name"]?.firstOrNull().orEmpty().take(80));if(bytes==null)newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","")else newFixedLengthResponse(Response.Status.OK,"image/jpeg",java.io.ByteArrayInputStream(bytes),bytes.size.toLong()).apply{addHeader("Cache-Control","private, max-age=86400");addHeader("Referrer-Policy","no-referrer")}}
   session.uri=="/api/player"&&session.method==Method.GET->response(Response.Status.OK,"application/json",bridge("/player","GET",""))
   session.uri=="/api/control"&&session.method==Method.POST->{require((session.headers["content-length"]?.toLongOrNull()?:0)<=8192);val parts=hashMapOf<String,String>();session.parseBody(parts);val command=JSONObject(parts["postData"].orEmpty());require(command.getString("action") in listOf("play","pause","next","restart","volume","player_action","top","cancel"));response(Response.Status.OK,"application/json",bridge("/control","POST",command.toString()))}
   session.uri=="/api/queue"&&session.method==Method.GET->response(Response.Status.OK,"application/json",bridge("/queue","GET",""))
   session.uri=="/api/songs"&&session.method==Method.GET->{val keyword=session.parameters["keyword"]?.firstOrNull().orEmpty().take(80);val offset=session.parameters["offset"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0)?:0;response(Response.Status.OK,"application/json",bridge("/songs?keyword="+android.net.Uri.encode(keyword)+"&limit=5&offset=$offset","GET",""))}
   session.uri=="/api/order"&&session.method==Method.POST->{require((session.headers["content-length"]?.toLongOrNull()?:0)<=8192)
    val body=hashMapOf<String,String>();session.parseBody(body);val payload=JSONObject(body["postData"].orEmpty());val id=payload.getString("id");require(id.matches(Regex("[0-9]{1,30}")))
    val command=JSONObject().put("action","order").put("params",JSONObject().put("song_id",id).put("origin",payload.optString("origin")))
    response(Response.Status.OK,"application/json",bridge("/control","POST",command.toString()))
   }
   else->response(Response.Status.NOT_FOUND,"application/json","{\"ok\":false,\"message\":\"接口不存在\"}")
  }}catch(_:Exception){response(Response.Status.BAD_REQUEST,"application/json","{\"ok\":false,\"message\":\"请求无效，请重试\"}")}
 }
 private fun response(status:Response.Status,type:String,body:String)=newFixedLengthResponse(status,"$type; charset=utf-8",body).apply{addHeader("Cache-Control","no-store");addHeader("Referrer-Policy","no-referrer");addHeader("X-Content-Type-Options","nosniff");addHeader("Content-Security-Policy","default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:")}
}
