package com.pocketktv.app
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.json.JSONObject
import java.net.Socket
import java.io.File
class PhoneSongbookTest {
 @Test fun authenticatedRealQueue() {
  val inst=InstrumentationRegistry.getInstrumentation();assumeTrue(InstrumentationRegistry.getArguments().getString("phone_e2e")=="true")
  val ctx=ApplicationProvider.getApplicationContext<Context>();val db=LibraryDb(ctx)
  val activity=inst.startActivitySync(Intent(ctx,SongbookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SongbookActivity
  val field=SongbookActivity::class.java.getDeclaredField("phoneServer");field.isAccessible=true
  val server=field.get(activity) as PhoneSongbookServer
  val token=server.pairingUrl!!.substringAfter("token=")
  fun request(path:String,method:String="GET",body:String="",authorized:Boolean=true):String{
   Socket("127.0.0.1",server.listeningPort).use{s->s.soTimeout=15000;val payload=body.toByteArray(Charsets.UTF_8)
    val header="$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n"+(if(authorized)"X-Woniu-Pair: $token\r\n" else "")+"Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n\r\n"
    s.getOutputStream().write(header.toByteArray());s.getOutputStream().write(payload);s.getOutputStream().flush();return s.getInputStream().bufferedReader().readText()
   }
  }
  assertTrue(request("/api/player").contains("channelMode"))
  assertTrue(request("/api/control","POST","{\"action\":\"openManagement\"}").startsWith("HTTP/1.1 400"))
  assertTrue(request("/api/control","POST","{\"action\":\"pause\"}").contains("\"ok\":true"))
  val audio=ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
  val stream=android.media.AudioManager.STREAM_MUSIC
  val volumeBefore=audio.getStreamVolume(stream)
  try{
   request("/api/control","POST","{\"action\":\"volume\",\"params\":{\"delta\":-1}}")
   inst.waitForIdleSync()
   if(!audio.isVolumeFixed)assertEquals((volumeBefore-1).coerceAtLeast(audio.getStreamMinVolume(stream)),audio.getStreamVolume(stream))
   assertEquals(audio.getStreamVolume(stream)*100/audio.getStreamMaxVolume(stream),JSONObject(activity.PlayerBridge().state()).getInt("volume"))
  }finally{inst.runOnMainSync{audio.setStreamVolume(stream,volumeBefore,0)}}
  val artists=request("/api/artists?scope=chinese");assertTrue(artists.contains("刘德华"));assertFalse(artists.contains("pick_code"))
  assertTrue(request("/api/artist-songs?name="+java.net.URLEncoder.encode("刘若英","UTF-8")).contains("刘若英"))
  val charts=request("/api/discovery");assertTrue(charts.contains("recommendations"))
  val before=db.queue().map{it.queueId}.toSet();var added:Long?=null
  try{
   assertTrue(request("/api/queue",authorized=false).startsWith("HTTP/1.1 401"))
   assertTrue(request("/").contains("想唱哪一首"))
   val search=request("/api/songs?keyword="+java.net.URLEncoder.encode("周杰伦","UTF-8"));assertTrue(search.startsWith("HTTP/1.1 200"));assertTrue(search.contains("周杰伦"));assertFalse(search.contains("pick_code"));assertFalse(search.contains("Cookie"))
   val song=db.readableDatabase.rawQuery("SELECT id FROM songs WHERE id NOT IN (SELECT song_id FROM queue) LIMIT 1",null).use{it.moveToFirst();it.getString(0)}
   val order=request("/api/order","POST",JSONObject().put("id",song).toString());assertTrue(order.contains("\"ok\":true"))
   added=db.queue().firstOrNull{it.songIdForTest()==song&&it.queueId !in before}?.queueId
   assertNotNull(added);assertTrue(request("/api/queue").contains(song));inst.waitForIdleSync();assertEquals(db.song(song)!!.title,JSONObject(activity.PlayerBridge().state()).getString("title"))
   File(ctx.filesDir,"phone-e2e-result.txt").writeText("PASS unauthorized=401 page=200 search=200 no_private_fields=true real_queue_order=true\n")
  }finally{added?.let{db.removeQueue(it)};inst.runOnMainSync{activity.LocalBridge().request("/control","POST","{\"action\":\"pause\"}")};db.close()}
 }
 private fun Song.songIdForTest()=id
}
