package com.pocketktv.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*

/** Keeps user-started playback eligible in the background while the player activity exists.
 * Removing the task or explicitly stopping playback releases the notification and wake lock.
 */
class PlaybackNotificationService:Service() {
    companion object {
        private const val CHANNEL="woniu_playback"
        private const val ID=7
        private var current:PlaybackNotificationService?=null
        private var command:((String)->Unit)?=null
        fun start(context:Context,title:String,artist:String,onCommand:(String)->Unit){
            command=onCommand
            val intent=Intent(context,PlaybackNotificationService::class.java).putExtra("title",title).putExtra("artist",artist)
            if(current==null)context.startForegroundService(intent) else current?.update(title,artist,"loading",0,0)
        }
        fun update(title:String,artist:String,state:String,position:Long,duration:Long){current?.update(title,artist,state,position,duration)}
        fun stop(context:Context){command=null;context.stopService(Intent(context,PlaybackNotificationService::class.java))}
    }
    private lateinit var session:MediaSession
    private var title="窝牛家庭KTV";private var artist="";private var state="loading"
    private var lastState="";private var lastTitle="";private var lastUpdate=0L
    private var pausedAt=0L
    private var wake:PowerManager.WakeLock?=null
    override fun onCreate(){
        super.onCreate();current=this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"音乐播放",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null);setShowBadge(false)})
        session=MediaSession(this,"WoniuPlayback").apply{
            setSessionActivity(openApp());setCallback(object:MediaSession.Callback(){
                override fun onPlay(){command?.invoke("resume")}
                override fun onPause(){command?.invoke("pause_only")}
                override fun onSkipToNext(){command?.invoke("next")}
                override fun onStop(){command?.invoke("stop");stopSelf()}
            });isActive=true
        }
        val notification=notification()
        if(Build.VERSION.SDK_INT>=29)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)else startForeground(ID,notification)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val action=intent?.action
        if(action!=null){command?.invoke(action);if(action=="stop")stopSelf()}
        else{title=intent?.getStringExtra("title")?:title;artist=intent?.getStringExtra("artist")?:artist;update(title,artist,state,0,0)}
        return START_NOT_STICKY
    }
    private fun openApp()=PendingIntent.getActivity(this,0,Intent(this,SongbookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun control(action:String)=PendingIntent.getService(this,action.hashCode(),Intent(this,PlaybackNotificationService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification():Notification {
        val playing=state in listOf("playing","loading")
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title).setContentText(artist)
            .setContentIntent(openApp()).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(playing)
            .addAction(Notification.Action.Builder(if(playing)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,if(playing)"暂停" else "继续",control(if(playing)"pause_only" else "resume")).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next,"下一首",control("next")).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"停止",control("stop")).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2)).build()
    }
    private fun update(nextTitle:String,nextArtist:String,nextState:String,position:Long,duration:Long){
        title=nextTitle;artist=nextArtist;state=nextState
        if(state in listOf("idle","error")){stopSelf();return}
        val now=SystemClock.elapsedRealtime();val playing=state=="playing"
        if(playing){
            pausedAt=0
            if(wake?.isHeld!=true)wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"WoniuKTV:playback").apply{setReferenceCounted(false);acquire(10*60_000L)}
        }else{wake?.let{if(it.isHeld)it.release()};wake=null;if(pausedAt==0L)pausedAt=now;if(now-pausedAt>10*60_000){stopSelf();return}}
        if(now-lastUpdate<1000&&lastState==state&&lastTitle==title)return
        lastUpdate=now;lastState=state;lastTitle=title
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ARTIST,artist).putLong(MediaMetadata.METADATA_KEY_DURATION,duration).build())
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP)
            .setState(if(playing)PlaybackState.STATE_PLAYING else if(state=="loading")PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED,position,if(playing)1f else 0f).build())
        getSystemService(NotificationManager::class.java).notify(ID,notification())
    }
    override fun onTaskRemoved(rootIntent:Intent?){command?.invoke("stop");stopSelf();super.onTaskRemoved(rootIntent)}
    override fun onDestroy(){current=null;command=null;wake?.let{if(it.isHeld)it.release()};wake=null;session.isActive=false;session.release();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
    override fun onBind(intent:Intent?)=null
}
