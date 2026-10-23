package com.pocketktv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import java.net.ServerSocket
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Loopback-only transport adapter for LibVLC. Keeps signed URLs and 115 headers out of player options/logs. */
class PlaybackTransport:java.io.Closeable {
 private val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
 private val workers=Executors.newCachedThreadPool()
 private val sockets=ConcurrentHashMap.newKeySet<Socket>()
 private val calls=ConcurrentHashMap.newKeySet<Call>()
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
  .addNetworkInterceptor{chain->val b=chain.request().newBuilder();if(!is115Host(chain.request().url.host))b.removeHeader("Cookie");chain.proceed(b.build())}.build()
 @Volatile private var source:Pair<String,Map<String,String>>?=null
 @Volatile private var token=UUID.randomUUID().toString()
 init{workers.execute{while(!server.isClosed){try{val socket=server.accept();sockets.add(socket);workers.execute{serve(socket)}}catch(_:Exception){if(server.isClosed)break}}}}
 fun open(url:String,headers:Map<String,String>):String {reset();source=url to headers;token=UUID.randomUUID().toString();return "http://127.0.0.1:${server.localPort}/$token"}
 fun reset(){source=null;calls.forEach{it.cancel()};sockets.forEach{runCatching{it.close()}}}
 private fun serve(socket:Socket){var call:Call?=null
  try{socket.soTimeout=15000;val reader=socket.getInputStream().bufferedReader(Charsets.US_ASCII);val first=reader.readLine()?:return
   val parts=first.split(' ');if(parts.size<2)return
   val method=parts[0];val current=source
   if(method !in listOf("GET","HEAD")||parts[1]!="/$token"||current==null){socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());return}
   var range:String?=null;var size=first.length
   while(true){val line=reader.readLine()?:return;if(line.isEmpty())break;size+=line.length;require(size<16384);if(line.startsWith("Range:",true))range=line.substringAfter(':').trim()}
   val request=Request.Builder().url(current.first).method(method,null).apply{current.second.forEach{(k,v)->header(k,v)};header("Accept-Encoding","identity");range?.takeIf{it.matches(Regex("bytes=\\d*-\\d*"))}?.let{header("Range",it)}}.build()
   call=client.newCall(request);calls.add(call)
   call.execute().use{response->val out=socket.getOutputStream();val status=response.code
    val header=StringBuilder("HTTP/1.1 $status ${if(response.isSuccessful) "OK" else "Upstream error"}\r\nConnection: close\r\n")
    for(key in listOf("Content-Type","Content-Length","Content-Range","Accept-Ranges"))response.header(key)?.let{header.append(key).append(": ").append(it).append("\r\n")}
    out.write(header.append("\r\n").toString().toByteArray(Charsets.US_ASCII));out.flush()
    if(method!="HEAD")response.body?.byteStream()?.use{it.copyTo(out,65536)}
   }
  }catch(_:Exception){}finally{call?.let{calls.remove(it);it.cancel()};sockets.remove(socket);runCatching{socket.close()}}
 }
 override fun close(){reset();runCatching{server.close()};workers.shutdownNow();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
}
