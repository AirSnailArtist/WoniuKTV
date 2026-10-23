package com.pocketktv.app
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.player.PlaybackEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
class UniversalPlaybackTest {
 @Test fun actualLibraryFormats()=runBlocking {
  val inst=InstrumentationRegistry.getInstrumentation();assumeTrue(InstrumentationRegistry.getArguments().getString("universal_playback")=="true")
  val context=ApplicationProvider.getApplicationContext<Context>();val db=LibraryDb(context);val drive=Drive115(SecretStore(context))
  val report=File(context.filesDir,"universal-playback-test.txt");report.writeText("START\n")
  val samples=mutableListOf<Song>()
  for((ext,limit) in listOf("mpg" to 4,"mpeg" to 2,"avi" to 3,"mp4" to 2)){
   val ids=db.readableDatabase.rawQuery("SELECT id FROM songs WHERE lower(name) LIKE ? ORDER BY id LIMIT ?",arrayOf("%.$ext",limit.toString())).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
   samples+=ids.mapNotNull{db.song(it)}
  }
  val activity=inst.startActivitySync(Intent(context,SongbookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SongbookActivity
  lateinit var engine:PlaybackEngine;lateinit var host:PlayerView;var error=""
  inst.runOnMainSync{host=PlayerView(activity);host.useController=false;(activity.window.decorView as ViewGroup).addView(host,FrameLayout.LayoutParams(-1,-1));engine=PlaybackEngine(activity,{}, {},{error=it});engine.attach(host)}
  fun waitFor(timeout:Long=30000,condition:()->Boolean):Boolean {val until=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<until){var ok=false;inst.runOnMainSync{ok=condition()};if(ok)return true;Thread.sleep(300)};return false}
  try{
   for((index,song) in samples.withIndex()){
    report.appendText("TEST ${index+1}/${samples.size} ${song.name}\n")
    val source=drive.source(song.pickCode);inst.runOnMainSync{error="";engine.play(song.id.toLong(),source.url,source.headers,true)}
    assertTrue("No AV frames: ${song.name}; $error",waitFor{val stat=engine.diagnosticStats();error.isEmpty()&&engine.isPlaying&&engine.videoOutputCount>0&&(stat?.displayedPictures?:0)>4&&(stat?.playedAbuffers?:0)>0})
    inst.runOnMainSync{val st=engine.diagnosticStats()!!;report.appendText("AV_OK video=${st.displayedPictures} audio=${st.playedAbuffers} duration=${engine.durationMs} tracks=${engine.audioTracks().size} channels=${engine.audioChannelCount}\n");engine.pause()}
    Thread.sleep(250);inst.runOnMainSync{assertFalse(engine.isPlaying);engine.resume();engine.seekTo(15000)}
    assertTrue("seek failed: ${song.name}",waitFor{engine.currentPositionMs>=14000&&engine.isPlaying})
    if(index==0){for(track in listOf(1,0)){
     inst.runOnMainSync{engine.setVocalMode(if(track==1)"accompaniment" else "original",track,2)}
     assertTrue("audio track switch failed",waitFor{engine.selectedAudioTrackIndex==track&&engine.isPlaying})
     report.appendText("TRACK_OK $track\n")
    }}
    if(index==0){for(channel in listOf("left","right","stereo")){
     inst.runOnMainSync{engine.setChannelMode(channel)}
     assertTrue("channel switch failed: $channel",waitFor{engine.isPlaying&&engine.videoOutputCount>0&&(engine.diagnosticStats()?.playedAbuffers?:0)>0})
     inst.runOnMainSync{assertEquals(channel,engine.selectedChannelMode);report.appendText("CHANNEL_OK $channel position="+engine.currentPositionMs+"\n")}
    }}
    inst.runOnMainSync{report.appendText("SEEK_OK position=${engine.currentPositionMs}\n");engine.stop()}
   };report.appendText("COMPLETE ${samples.size}/${samples.size}\n")
  }finally{inst.runOnMainSync{engine.release();(host.parent as? ViewGroup)?.removeView(host)};db.close()}
 }
}

