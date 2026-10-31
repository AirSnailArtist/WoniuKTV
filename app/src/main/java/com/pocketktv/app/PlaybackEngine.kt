package com.homektv.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import com.pocketktv.app.PlaybackTransport
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.*
import com.pocketktv.app.MpegTimeline
import com.pocketktv.app.is115Host
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Unified LibVLC engine: the same demux/decoder path for every cloud-library format. */
class PlaybackEngine(context:Context,private val onProgress:(Long)->Unit,private val onFinished:()->Unit,private val onError:(String)->Unit){
 private companion object {
  // DS: output gap ~300ms at 200ms caching, versus ~1.6s at 1500ms.
  // Keep one input; competing 115 streams can fail during track preloading.
  const val NETWORK_CACHE_MS=200
 }
 private val appContext=context.applicationContext
 private val main=Handler(Looper.getMainLooper())
 private var channelMode="stereo"
 private val audioSession=(appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
 private var enhancer:android.media.audiofx.LoudnessEnhancer?=null
 var loudnessBoost=false;private set
 val loudnessAvailable get()=audioSession>0
 val decoderVolume get()=player.volume
 fun setLoudnessBoost(enabled:Boolean):Boolean=try{
  if(enabled){check(audioSession>0);val effect=enhancer?:android.media.audiofx.LoudnessEnhancer(audioSession).also{enhancer=it};effect.setTargetGain(600);effect.enabled=true}else enhancer?.enabled=false
  loudnessBoost=enabled;true
 }catch(_:RuntimeException){loudnessBoost=false;false}
 private var softwareVolume=100
 fun setPlaybackVolume(value:Int){softwareVolume=value.coerceIn(0,100);player.setVolume(softwareVolume)}
 val playbackVolume get()=softwareVolume
 private fun createCore()=LibVLC(appContext,arrayListOf("--quiet","--no-video-title-show","--network-caching=$NETWORK_CACHE_MS","--no-audio-time-stretch","--audiotrack-session-id=${audioSession.coerceAtLeast(0)}","--stereo-mode="+when(channelMode){"left"->3;"right"->4;else->1}))
 private var core=createCore()
 private var player=MediaPlayer(core)
 private var savedUrl="";private var savedHeaders=emptyMap<String,String>()

 private var resumePosition=0L;private var pauseAfterStart=false
 private val transport=PlaybackTransport()
 private var layout:VLCVideoLayout?=null
 private var fileId:Long?=null
 private val probeScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private var probeJob:Job?=null
 private val probeClient=OkHttpClient.Builder().connectTimeout(6,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(18,TimeUnit.SECONDS).addNetworkInterceptor{chain->val request=chain.request().newBuilder();if(!is115Host(chain.request().url.host))request.removeHeader("Cookie");chain.proceed(request.build())}.build()
 @Volatile private var correctedDuration:Long?=null
 @Volatile var durationProbeInfo="";private set
 private var mpegTimeline=false
 private val durationCache=android.util.LruCache<Long,Long>(256)
 private var active=false
 private var released=false
 private var mode="original"
 private var trackIndex=0
 private var lastTracks=emptyList<String>()
 private var scaleMode=3
 private val resizeAfterLayout=Runnable{if(!released&&layout!=null)setResizeMode(scaleMode)}
 private fun scheduleResize(){main.removeCallbacks(resizeAfterLayout);main.post(resizeAfterLayout)}
 var onAudioTracksChanged:((List<String>)->Unit)?=null
 var onExternalVolumeChange:((Int)->Unit)?=null
 var videoOutputCount=0;private set
 val supportsChannelSelection=true
 val selectedChannelMode get()=channelMode
 val isPlaying get()=active&&player.isPlaying
 val seekable get()=active&&player.isSeekable
 val playbackFraction get()=if(seekable)player.position.coerceIn(0f,1f) else 0f
 fun seekFraction(fraction:Float){if(active&&player.isSeekable)player.setPosition(fraction.coerceIn(0f,1f),false)}
 val durationMs get()=if(!active)0L else correctedDuration?:if(mpegTimeline)0L else player.length.coerceAtLeast(0L)
 val currentPositionMs:Long get(){
  if(!active)return 0L
  val duration=correctedDuration;val fraction=player.position
  // DVD MPEG timestamps can retain an hour-long offset after seeking.
  return if(mpegTimeline&&duration!=null&&fraction in 0f..1f)(fraction*duration).toLong() else player.time.coerceAtLeast(0L)
 }
 val audioChannelCount:Int get(){if(!active||(diagnosticStats()?.playedAbuffers?:0)==0)return 0;val media=player.media?:return 0;return try{(0 until media.trackCount).mapNotNull{media.getTrack(it) as? org.videolan.libvlc.interfaces.IMedia.AudioTrack}.firstOrNull()?.channels?:0}finally{media.release()}}
 init{bindEvents()}
 private fun bindEvents(){player.setEventListener{event->if(active&&!released)when(event.type){
  MediaPlayer.Event.Playing->{player.setVolume(softwareVolume);Log.i("PlaybackEngine","VLC playing fileId=$fileId channel=$channelMode");applyPendingSeek();if(pauseAfterStart){pauseAfterStart=false;player.pause()};refreshTracks();setResizeMode(scaleMode)}
  MediaPlayer.Event.Vout->{videoOutputCount=event.voutCount;setResizeMode(scaleMode);Log.i("PlaybackEngine","VLC video outputs=$videoOutputCount fileId=$fileId")}
  MediaPlayer.Event.ESAdded,MediaPlayer.Event.ESSelected->refreshTracks()
  MediaPlayer.Event.EndReached->{active=false;main.removeCallbacks(ticker);onFinished()}
  MediaPlayer.Event.EncounteredError->{active=false;main.removeCallbacks(ticker);onError("播放器无法读取或解码此文件，请重试或切歌")}
 }}}
 fun attach(view:PlayerView){view.player=null
  if(layout==null){layout=VLCVideoLayout(view.context);view.addView(layout,FrameLayout.LayoutParams(-1,-1));player.attachViews(layout!!,null,false,true);layout!!.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->scheduleResize()};findTexture(layout!!)?.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->scheduleResize()};setResizeMode(view.resizeMode)}
 }
 fun detach(view:PlayerView){main.removeCallbacks(resizeAfterLayout);player.detachViews();layout?.let{(it.parent as? ViewGroup)?.removeView(it)};layout=null}
 fun play(id:Long,url:String,headers:Map<String,String> = emptyMap(),force:Boolean=false,startPositionMs:Long=0,formatHint:String=""){
  if(!force&&fileId==id&&active){resume();return}
  stop();pauseAfterStart=false;savedUrl=url;savedHeaders=headers;resumePosition=startPositionMs;fileId=id;active=true;videoOutputCount=0;lastTracks=emptyList()
  mpegTimeline=formatHint.lowercase() in listOf("mpg","mpeg");correctedDuration=durationCache.get(id);durationProbeInfo=""
  val local=transport.open(url,headers)
  if(mpegTimeline&&correctedDuration==null){probeJob=probeScope.launch{val duration=try{MpegTimeline.probe(probeClient,url,headers){durationProbeInfo+=" $it"}}catch(e:CancellationException){throw e}catch(e:Exception){durationProbeInfo+=" error="+e.javaClass.simpleName;null};withContext(Dispatchers.Main){if(active&&fileId==id){if(duration!=null){correctedDuration=duration;durationCache.put(id,duration)}else if(player.length in 1..1_800_000)correctedDuration=player.length;applyPendingSeek()}}}}
  val media=Media(core,Uri.parse(local));media.setHWDecoderEnabled(true,false)
  media.addOption(":network-caching=$NETWORK_CACHE_MS");media.addOption(":no-video-title-show")
  player.media=media;media.release();setResizeMode(scaleMode);player.play();main.post(ticker)
 }
 fun pause(){if(active)player.pause()}
 fun resume(){if(active)player.play()}
 private fun applyPendingSeek(){if(resumePosition>0&&(!mpegTimeline||correctedDuration!=null)){val target=resumePosition;resumePosition=0;seekTo(target)}}
 fun seekTo(positionMs:Long){if(active){val duration=correctedDuration;if(mpegTimeline){if(duration!=null)player.setPosition((positionMs.toFloat()/duration).coerceIn(0f,1f),false)else resumePosition=positionMs.coerceAtLeast(0)}else player.setTime(positionMs.coerceAtLeast(0),false)}}
 fun restart(){seekTo(0);resume()}
 fun stop(){probeJob?.cancel();probeJob=null;correctedDuration=null;mpegTimeline=false;active=false;fileId=null;main.removeCallbacks(ticker);player.stop();transport.reset();lastTracks=emptyList()}
 fun release(){if(released)return;enhancer?.release();enhancer=null;stop();released=true;main.removeCallbacks(resizeAfterLayout);player.detachViews();player.release();core.release();transport.close();probeScope.cancel()}
 fun diagnosticStats():org.videolan.libvlc.interfaces.IMedia.Stats?{val media=player.media?:return null;return try{media.stats}finally{media.release()}}
 val selectedAudioTrackIndex get()=player.audioTracks.orEmpty().filter{it.id>=0}.indexOfFirst{it.id==player.audioTrack}
 fun audioTracks():List<String> = player.audioTracks.orEmpty().filter{it.id>=0}.mapIndexed{i,t->t.name?.takeIf{it.isNotBlank()}?:"音轨 ${i+1}"}
 private fun refreshTracks(){val labels=audioTracks();if(labels!=lastTracks){lastTracks=labels;onAudioTracksChanged?.invoke(labels);applyTrack()}}
 fun setVocalMode(mode:String,accompanimentIndex:Int?,audioTrackCount:Int){this.mode=mode;trackIndex=if(mode=="accompaniment")accompanimentIndex?:1 else 0;applyTrack()}
 private fun applyTrack(){val tracks=player.audioTracks.orEmpty().filter{it.id>=0};tracks.getOrNull(trackIndex)?.let{if(player.audioTrack!=it.id){Log.i("PlaybackEngine","Audio switch track=$trackIndex uptimeMs=${android.os.SystemClock.elapsedRealtime()}");player.setAudioTrack(it.id)}}}
 fun setChannelMode(mode:String){
  if(mode !in listOf("stereo","left","right")||mode==channelMode)return
  val hint=if(mpegTimeline)"mpg" else "";val id=fileId;val url=savedUrl;val headers=savedHeaders;val position=currentPositionMs;val playing=isPlaying;val wasActive=active
  channelMode=mode;active=false;main.removeCallbacks(ticker)
  player.setEventListener(null);player.stop();player.detachViews();player.release();core.release()
  core=createCore();player=MediaPlayer(core);bindEvents()
  layout?.let{player.attachViews(it,null,false,true);setResizeMode(scaleMode)}
  if(wasActive&&id!=null&&url.isNotBlank()){play(id,url,headers,true,position,hint);pauseAfterStart=!playing}
 }
 fun setResizeMode(mode:Int){scaleMode=mode;player.videoScale=if(mode==3)MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT;updateZoom()}
 private fun findTexture(view:android.view.View):android.view.TextureView?{if(view is android.view.TextureView)return view;if(view is ViewGroup)for(i in 0 until view.childCount)findTexture(view.getChildAt(i))?.let{return it};return null}
 private fun updateZoom(){val view=layout?:return;val texture=findTexture(view);var zoom=1f
  if(scaleMode==4&&view.width>0&&view.height>0&&texture!=null&&texture.width>0&&texture.height>0){zoom=maxOf(view.width.toFloat()/texture.width,view.height.toFloat()/texture.height)}
  view.scaleX=zoom;view.scaleY=zoom
 } fun applyVolume(volume:Int,muted:Boolean){val audio=appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager;val max=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,volume.coerceIn(0,100)*max/100,0);player.setVolume(if(muted)0 else 100)}
 private val ticker=object:Runnable{override fun run(){if(active&&!released){onProgress(currentPositionMs);main.postDelayed(this,150)}}}
}








