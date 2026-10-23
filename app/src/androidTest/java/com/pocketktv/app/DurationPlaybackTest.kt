package com.pocketktv.app
import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.player.PlaybackEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
class DurationPlaybackTest {
 @Test fun sunnyMpegTimelineAndSeek()=runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("duration_e2e")=="true")
  val inst=InstrumentationRegistry.getInstrumentation();val ctx=ApplicationProvider.getApplicationContext<Context>();val db=LibraryDb(ctx)
  val song=db.readableDatabase.rawQuery("SELECT id FROM songs WHERE title='晴天' AND artist='周杰伦' AND lower(name) LIKE '%.mpg' LIMIT 1",null).use{assertTrue(it.moveToFirst());db.song(it.getString(0))!!}
  val source=Drive115(SecretStore(ctx)).source(song.pickCode)
  val activity=inst.startActivitySync(Intent(ctx,SongbookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SongbookActivity
  var engine:PlaybackEngine?=null;var view:PlayerView?=null;val report=File(ctx.filesDir,"duration-playback-result.txt");report.writeText("START 晴天 MPG\n")
  try{inst.runOnMainSync{view=PlayerView(activity);activity.addContentView(view,FrameLayout.LayoutParams(640,360));engine=PlaybackEngine(activity,{}, {},{report.appendText("ERROR $it\n")});engine!!.attach(view!!);engine!!.play(song.id.toLong(),source.url,source.headers,true,0,"mpg")}
   var duration=0L
   for(attempt in 0 until 240){delay(200);inst.runOnMainSync{duration=engine!!.durationMs};if(duration>0)break}
   inst.runOnMainSync{val f=PlaybackEngine::class.java.getDeclaredField("player");f.isAccessible=true;val native=f.get(engine) as org.videolan.libvlc.MediaPlayer;report.appendText("BEFORE probe=${engine!!.durationProbeInfo} corrected=$duration raw_length=${native.length} raw_time=${native.time} raw_position=${native.position}\n")}
   assertTrue("duration=$duration",duration in 120_000L..600_000L)
   inst.runOnMainSync{engine!!.seekTo(duration/2)};delay(4000)
   var position=0L;inst.runOnMainSync{position=engine!!.currentPositionMs;val f=PlaybackEngine::class.java.getDeclaredField("player");f.isAccessible=true;val native=f.get(engine) as org.videolan.libvlc.MediaPlayer;report.appendText("AFTER corrected=$duration position=$position raw_length=${native.length} raw_position=${native.position}\n")}
   assertTrue("seek=$position target=${duration/2}",kotlin.math.abs(position-duration/2)<20000)
   report.appendText("PASS\n")
  }finally{inst.runOnMainSync{engine?.detach(view!!);engine?.release();(view?.parent as? android.view.ViewGroup)?.removeView(view)};db.close()}
 }
}
