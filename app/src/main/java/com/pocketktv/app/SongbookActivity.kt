package com.pocketktv.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.os.Build
import android.app.AlertDialog
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.homektv.tv.player.PlaybackEngine
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Bundled MIT Home KTV UI; only local assets can access the database bridge. */
class SongbookActivity:ComponentActivity(){
    private lateinit var web:WebView
    private lateinit var db:LibraryDb
    private lateinit var learning:SmartLibrary
    private var learningToken:String?=null
    private var learningSong:Song?=null
    private var learningProgress=SingingProgress()
    private var learningCheckpoint=0L
    private var learningAttempts=0
    private var learningRetryAt=0L
    @Volatile private var learningWarning=""
    private var backupJob:Job?=null
    @Volatile private var backupStatus=""
    private fun learn(action:()->Unit){try{action();learningWarning=""}catch(e:Exception){learningWarning="学习记录暂时不可用："+e.javaClass.simpleName;android.util.Log.w("WoniuLearning",learningWarning)}}
    private fun learningTick(){
        val song=activeSong?:return
        if(engine.isPlaying&&learningToken==null&&learningAttempts<3&&android.os.SystemClock.elapsedRealtime()>=learningRetryAt){learningAttempts++;learningRetryAt=android.os.SystemClock.elapsedRealtime()+15_000;learn{learningToken=learning.start(song);learningSong=song;learningProgress=SingingProgress()}}
        val token=learningToken?:return
        val accompaniment=song.edition.contains("伴")||(vocalMode=="accompaniment"&&((engine.selectedAudioTrackIndex>0&&engine.selectedAudioTrackIndex==song.accompaniment)||engine.selectedChannelMode in listOf("left","right")))
        val clock=android.os.SystemClock.elapsedRealtime()
        learningProgress.sample(positionMs,clock,engine.isPlaying,accompaniment)
        if(engine.isPlaying&&clock-learningCheckpoint>=15_000){learningCheckpoint=clock;learn{learning.checkpoint(token,learningProgress,durationMs)}}
    }
    private fun finishLearning(outcome:String){
        val token=learningToken?:return;val song=learningSong?:return
        learningToken=null;learn{learning.finish(token,song,learningProgress, durationMs,outcome)}
        learningSong=null;learningProgress=SingingProgress()
    }
    private fun backupSmart(){runOnUiThread{
        if(backupJob?.isActive==true)return@runOnUiThread
        backupJob=lifecycleScope.launch{backupStatus="正在备份曲库与智能偏好…";try{val path=CatalogRepository(db,Drive115(SecretStore(this@SongbookActivity)),cacheDir).backup{backupStatus=it};backupStatus="已备份到115：$path"}catch(e:Exception){backupStatus="备份未完成，请核对115："+db.setting("catalog_backup_pending")+"（"+e.javaClass.simpleName+"）"}}
    }}
    private lateinit var engine:PlaybackEngine
    private lateinit var videoPanel:FrameLayout
    private lateinit var videoTitle:TextView
    private lateinit var videoView:PlayerView
    private lateinit var playerSeek:SeekBar
    private lateinit var elapsedLabel:TextView
    private lateinit var durationLabel:TextView
    private var draggingSeek=false
    private lateinit var playerMenu:LinearLayout
    private var menuHideJob:Job?=null
    private lateinit var playerOverlay:WebView
    private var menuShown=false
    @Volatile private var audioLabels=emptyList<String>()
    @Volatile private var audioChannels=0
    @Volatile private var vocalMode="original"
    private lateinit var rootFrame:FrameLayout
    private var videoFullscreen=true
    private var playJob:Job?=null
    @Volatile private var activeSong:Song?=null
    @Volatile private var counterpart:Song?=null
    private lateinit var discovery:PublicCharts
    private var discoveryJob:Job?=null
    private var lastDiscoveryAttempt=0L
    private fun refreshDiscovery(force:Boolean=false){runOnUiThread{if(discoveryJob?.isActive==true||(!force&&System.currentTimeMillis()-lastDiscoveryAttempt<60_000))return@runOnUiThread;lastDiscoveryAttempt=System.currentTimeMillis();discoveryJob=lifecycleScope.launch{discovery.refresh(force);if(!isDestroyed)web.evaluateJavascript("window.dispatchEvent(new Event('discovery-updated'))",null)}}}
    private var phoneServer:PhoneSongbookServer?=null
    @Volatile private var activeQueueId=0L
    @Volatile private var playState="idle"
    @Volatile private var positionMs=0L
    @Volatile private var durationMs=0L
    @Volatile private var playError=""
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state:Bundle?){
        super.onCreate(state);volumeControlStream=android.media.AudioManager.STREAM_MUSIC;db=LibraryDb(this);if(db.setting("stretch_default_v1")!="true"){db.setting("video_resize",AspectRatioFrameLayout.RESIZE_MODE_FILL.toString(),true);db.setting("video_fullscreen","true",true);db.setting("stretch_default_v1","true",true)};window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        learning=SmartLibrary(db);discovery=PublicCharts(this,db)
        val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        rootFrame=root;videoFullscreen=db.setting("video_fullscreen","true")=="true"
        val bar=LinearLayout(this)
        bar.addView(TextView(this).apply{text="窝牛家庭KTV";textSize=20f;setTextColor(Color.WHITE);setPadding(20,12,20,12)},LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(Button(this).apply{text="115 / 曲库管理";setOnClickListener{openManagement()}})
        web=WebView(this).apply{
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            settings.allowFileAccess=false;settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(LocalBridge(),"Woniu")
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=request.url.host!="app.woniu.local"
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse{
                    val uri=request.url
                    if(uri.scheme!="https"||uri.host!="app.woniu.local"||uri.path.orEmpty().contains(".."))return blocked()
                    if(uri.path=="/api/browse/artists"&&request.method=="GET") {
                        val result=LocalBridge().request("/browse/artists?"+uri.encodedQuery.orEmpty(),"GET","")
                        return WebResourceResponse("application/json","UTF-8",ByteArrayInputStream(result.toByteArray(Charsets.UTF_8)))
                    }
                    if(uri.path=="/artist-art"){val bytes=discovery.artistPhoto(uri.getQueryParameter("name").orEmpty())?:return blocked();return WebResourceResponse("image/jpeg",null,ByteArrayInputStream(bytes))}
                    val path=uri.path.orEmpty().trimStart('/').ifBlank{"index.html"}
                    val mime=when(path.substringAfterLast('.')){"html"->"text/html";"js"->"application/javascript";"css"->"text/css";"png"->"image/png";"svg"->"image/svg+xml";else->"application/octet-stream"}
                    return try{WebResourceResponse(mime,"UTF-8",assets.open("songbook/$path"))}catch(_:Exception){blocked()}
                }
            }
        }
        root.addView(web,FrameLayout.LayoutParams(-1,-1))
        videoPanel=FrameLayout(this).apply{setBackgroundColor(Color.BLACK);visibility=View.GONE}
        videoView=PlayerView(this).apply{
            useController=false;keepScreenOn=true;isFocusable=true;isFocusableInTouchMode=true
            resizeMode=db.setting("video_resize",AspectRatioFrameLayout.RESIZE_MODE_FILL.toString()).toIntOrNull()?:AspectRatioFrameLayout.RESIZE_MODE_FILL
            setOnTouchListener{_,event->if(event.action==MotionEvent.ACTION_UP){if(playerMenu.visibility==View.VISIBLE)hideControls()else showControls()};true}
        }
        videoPanel.addView(videoView,FrameLayout.LayoutParams(-1,-1))
        videoTitle=TextView(this).apply{setTextColor(Color.WHITE);textSize=17f;setPadding(22,12,22,12);setBackgroundColor(0x99000000.toInt())}
        videoPanel.addView(videoTitle,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val controls=LinearLayout(this).apply{gravity=Gravity.CENTER;setBackgroundColor(0xcc11151c.toInt());setPadding(8,5,8,5)}
        val controlsContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xee11151c.toInt())}
        playerMenu=controlsContainer
        val progressRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(20,0,20,0)}
        elapsedLabel=TextView(this).apply{setTextColor(Color.WHITE);textSize=13f;text="0:00"}
        durationLabel=TextView(this).apply{setTextColor(Color.WHITE);textSize=13f;text="0:00"}
        playerSeek=SeekBar(this).apply{max=1000;keyProgressIncrement=10;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){draggingSeek=true;menuHideJob?.cancel()}
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){if(fromUser&&!draggingSeek){learningProgress.discontinuity();engine.seekTo(durationMs*value/1000)}}
            override fun onStopTrackingTouch(bar:SeekBar){learningProgress.discontinuity();engine.seekTo(durationMs*bar.progress/1000);draggingSeek=false;showControls()}
        })}
        progressRow.addView(elapsedLabel);progressRow.addView(playerSeek,LinearLayout.LayoutParams(0,60,1f));progressRow.addView(durationLabel)
        controlsContainer.addView(progressRow)
        fun control(title:String,action:()->Unit){controls.addView(Button(this).apply{
            text=title;isAllCaps=false;minimumHeight=64;setTextColor(Color.rgb(222,243,234))
            background=android.graphics.drawable.StateListDrawable().apply{
                addState(intArrayOf(android.R.attr.state_focused),android.graphics.drawable.GradientDrawable().apply{setColor(0xff29463c.toInt());cornerRadius=12f;setStroke(3,0xff9de5cd.toInt())})
                addState(intArrayOf(),android.graphics.drawable.GradientDrawable().apply{setColor(0xff263b34.toInt());cornerRadius=12f})
            };setPadding(20,8,20,8);setOnClickListener{action();if(videoPanel.visibility==View.VISIBLE)showControls()}
        },LinearLayout.LayoutParams(-2,80).apply{setMargins(8,0,8,0)})}
        control("返回点歌"){hideVideo()};control("暂停 / 继续"){if(engine.isPlaying){engine.pause();playState="paused"}else{engine.resume();playState="playing"}}
        control("下一首"){nextSong()};control("原唱 / 伴奏"){audioOptions()};control("画面"){pictureOptions()}
        control("更多"){AlertDialog.Builder(this).setTitle("播放操作").setItems(arrayOf("重唱当前歌曲","重新获取播放地址并重试","停止播放，返回点歌")){_,i->when(i){
            0->{engine.restart();playState="playing"};1->playQueue(activeQueueId,true);2->{finishLearning("stop");playJob?.cancel();engine.stop();activeSong=null;activeQueueId=0;playState="idle";hideVideo()}
        }}.setNegativeButton("关闭",null).show()}
        controlsContainer.addView(controls);videoPanel.addView(controlsContainer,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        controlsContainer.visibility=View.GONE;videoTitle.visibility=View.GONE
        playerOverlay=WebView(this).apply{
            setBackgroundColor(Color.TRANSPARENT);settings.javaScriptEnabled=true
            settings.allowFileAccess=false;settings.allowContentAccess=false
            addJavascriptInterface(PlayerBridge(),"Player")
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse{
                    return if(request.url.scheme=="https"&&request.url.host=="app.woniu.local"&&request.url.path=="/player-overlay.html")
                        WebResourceResponse("text/html","UTF-8",assets.open("player-overlay.html")) else blocked()
                }
            }
            loadUrl("https://app.woniu.local/player-overlay.html")
        }
        videoPanel.addView(playerOverlay,FrameLayout.LayoutParams(-1,-1))
        root.addView(videoPanel,FrameLayout.LayoutParams(-1,-1));setContentView(root)
        engine=PlaybackEngine(this,{pos->positionMs=pos;durationMs=engine.durationMs;learningTick()
                if(!draggingSeek){playerSeek.progress=if(durationMs>0)(pos*1000/durationMs).toInt().coerceIn(0,1000) else 0}
                elapsedLabel.text=timeText(pos);durationLabel.text=timeText(durationMs)
                audioChannels=engine.audioChannelCount
                if(playError.isBlank()&&engine.isPlaying)playState="playing"},
            {nextSong(true)},{message->finishLearning("error");playState="error";playError=message;videoTitle.text="播放失败：$message · 可点重试或下一首";showControls()})
        engine.attach(videoView)
        engine.setResizeMode(videoView.resizeMode)
        engine.onAudioTracksChanged={tracks->
            audioLabels=tracks
            android.util.Log.i("WoniuPlayback","audio tracks=${tracks.size} channels=${engine.audioChannelCount}")
            activeSong?.let{song->when(song.audioMode){
                "left","right"->engine.setChannelMode(song.audioMode)
                "track"->engine.setVocalMode("accompaniment",song.accompaniment,tracks.size)
            }}
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){
            override fun handleOnBackPressed(){
                if(videoPanel.visibility==View.VISIBLE){
                    playerOverlay.evaluateJavascript("window.closePopup ? window.closePopup() : false"){closed->if(closed!="true"){if(menuShown)hideControls()else hideVideo()}}
                    return
                }
                web.evaluateJavascript("(function(){var b=document.querySelector('[role=\"dialog\"] button[aria-label=\"关闭\"]');if(b){b.click();return true;}return false;})()"){handled->
                    if(handled!="true"){if(web.canGoBack())web.goBack() else finish()}
                }
            }
        })
        phoneServer=runCatching{PhoneSongbookServer(this,{name->discovery.artistPhoto(name)}){path,method,body->LocalBridge().request(path,method,body)}.apply{start(5000,false)}}.getOrNull()
        web.loadUrl("https://app.woniu.local/index.html#/home")
        lifecycleScope.launch{withContext(Dispatchers.IO){db.reparseMetadata{done,total->runOnUiThread{title="曲库整理 $done / $total"}};ArtistDirectory.prepare(db)};web.evaluateJavascript("window.dispatchEvent(new Event('artist-index-ready'))",null)}
    }
    private fun blocked()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
    private fun showPhonePairing(){
        val url=phoneServer?.pairingUrl
        if(url==null){AlertDialog.Builder(this).setTitle("手机扫码点歌").setMessage("请将播放设备与手机连接到同一个 Wi-Fi，再打开扫码点歌。").setPositiveButton("知道了",null).show();return}
        val matrix=com.google.zxing.qrcode.QRCodeWriter().encode(url,com.google.zxing.BarcodeFormat.QR_CODE,420,420)
        val bitmap=android.graphics.Bitmap.createBitmap(420,420,android.graphics.Bitmap.Config.ARGB_8888)
        for(y in 0 until 420)for(x in 0 until 420)bitmap.setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,20,32,24)}
        panel.addView(ImageView(this).apply{setImageBitmap(bitmap)},LinearLayout.LayoutParams(-1,420))
        panel.addView(TextView(this).apply{text="微信扫一扫，即可在手机上选歌\n手机与播放设备需要连接同一个 Wi-Fi\n关闭 App 后二维码失效";textSize=19f;gravity=Gravity.CENTER;setPadding(0,20,0,10)})
        AlertDialog.Builder(this).setTitle("窝牛 · 手机点歌").setView(panel).setPositiveButton("关闭",null).show()
    }
    private fun openManagement(){startActivity(Intent(this,PocketActivity::class.java))}
    private fun hideVideo(){menuHideJob?.cancel();videoPanel.visibility=View.GONE;setPlaybackImmersive(false);web.requestFocus();web.evaluateJavascript("window.dispatchEvent(new Event('woniu-change'))",null)}
    private fun showVideo(){videoPanel.visibility=View.VISIBLE;applyVideoFrame();hideControls()}
    private fun applyVideoFrame(){
        videoPanel.layoutParams=if(videoFullscreen)FrameLayout.LayoutParams(-1,-1) else FrameLayout.LayoutParams((rootFrame.width*.68).toInt(),(rootFrame.height*.68).toInt(),Gravity.BOTTOM or Gravity.RIGHT).apply{setMargins(16,16,16,16)}
        setPlaybackImmersive(videoFullscreen)
    }
    private fun pictureOptions(){
        AlertDialog.Builder(this).setTitle("画面设置 · 4:3 可保持原比例")
            .setItems(arrayOf(if(videoFullscreen)"切换小窗播放" else "切换全屏播放","原比例显示（推荐，不拉伸）","裁切铺满（可能裁掉边缘）","拉伸铺满（人物可能变形）")){_,i->
                if(i==0){videoFullscreen=!videoFullscreen;db.setting("video_fullscreen",videoFullscreen.toString(),true);applyVideoFrame()}
                else{videoView.resizeMode=when(i){1->AspectRatioFrameLayout.RESIZE_MODE_FIT;2->AspectRatioFrameLayout.RESIZE_MODE_ZOOM;else->AspectRatioFrameLayout.RESIZE_MODE_FILL};engine.setResizeMode(videoView.resizeMode);db.setting("video_resize",videoView.resizeMode.toString(),true)}
                hideControls()
            }.setNegativeButton("关闭",null).show()
    }
    private fun showControls(){
        menuShown=true;playerOverlay.requestFocus();playerOverlay.evaluateJavascript("window.setMenu && window.setMenu(true)",null)
        armMenuHide()
    }
    private fun armMenuHide(){menuHideJob?.cancel();if(playState!="error")menuHideJob=lifecycleScope.launch{delay(5000);if(!draggingSeek)hideControls()}}
    private fun hideControls(){menuHideJob?.cancel();menuShown=false;playerMenu.visibility=View.GONE;videoTitle.visibility=View.GONE;playerOverlay.evaluateJavascript("window.setMenu && window.setMenu(false)",null);playerOverlay.requestFocus()}
    private fun setPlaybackImmersive(enabled:Boolean){
        if(Build.VERSION.SDK_INT>=30){
            window.insetsController?.apply{systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if(enabled)hide(WindowInsets.Type.systemBars())else show(WindowInsets.Type.systemBars())}
        }else{
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility=if(enabled)View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    private fun timeText(value:Long):String{val seconds=value.coerceAtLeast(0)/1000;return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"}
    private fun audioOptions(){
        val song=activeSong?:return;val tracks=engine.audioTracks()
        val choices=mutableListOf("原始立体声（不做消音）")
        val actions=mutableListOf<()->Unit>({engine.setChannelMode("stereo");engine.setVocalMode("original",0,tracks.size);db.audio(song.id,"tracks",0)})
        if(tracks.size>1)tracks.forEachIndexed{i,name->
            choices.add("音轨 ${i+1}：$name")
            actions.add{engine.setChannelMode("stereo");engine.setVocalMode("accompaniment",i,tracks.size);db.audio(song.id,"track",i)}
        }
        if(engine.audioChannelCount==2){
            choices.add("仅左声道（双声道 KTV 试听）");actions.add{engine.setChannelMode("left");db.audio(song.id,"left",0)}
            choices.add("仅右声道（双声道 KTV 试听）");actions.add{engine.setChannelMode("right");db.audio(song.id,"right",0)}
        }
        AlertDialog.Builder(this).setTitle("${tracks.size} 条音轨 · ${engine.audioChannelCount} 声道")
            .setItems(choices.toTypedArray()){_,i->actions[i]();activeSong=db.song(song.id)?.copy(queueId=activeQueueId)}
            .setNeutralButton("伴奏说明"){_,_->AlertDialog.Builder(this).setMessage("独立伴奏音轨或左右声道分离的 KTV 文件可以直接切换。普通 MV 的立体声不等于伴奏，左右声道试听不会自动去人声。需要独立伴奏文件或额外的人声分离处理。").setPositiveButton("知道了",null).show()}
            .setNegativeButton("关闭",null).show()
    }
    private fun playQueue(queueId:Long?=null,force:Boolean=false,resumeAt:Long=0){
        val song=db.queue().let{items->if(queueId!=null)items.firstOrNull{it.queueId==queueId} else items.firstOrNull()}
        if(song==null){playState="idle";activeSong=null;activeQueueId=0;engine.stop();hideVideo();return}
        if(!force&&activeSong!=null){showVideo();return}
        learningAttempts=0;learningRetryAt=0
        if(learningSong?.let{SmartLibrary.workKey(it)!=SmartLibrary.workKey(song)}==true)finishLearning("replaced")
        learningProgress.discontinuity();learningSong=if(learningToken!=null)song else null
        playJob?.cancel();engine.stop();activeSong=song;vocalMode=if(song.edition.contains("伴")||song.audioMode in listOf("left","right")||(song.audioMode=="track"&&song.accompaniment>0))"accompaniment" else "original";counterpart=db.counterpart(song);activeQueueId=song.queueId;positionMs=0;durationMs=0;playState="loading";playError="";audioLabels=emptyList();audioChannels=0
        videoTitle.text="正在获取 115 视频：${song.title}";showVideo()
        playJob=lifecycleScope.launch{
            try{
                val source=Drive115(SecretStore(this@SongbookActivity)).source(song.pickCode)
                ensureActive();videoTitle.text=song.title;engine.setChannelMode("stereo")
                engine.setVocalMode("original",0,1)
                engine.play(song.id.toLong(),source.url,source.headers,true,resumeAt,song.name.substringAfterLast('.'))
            }catch(e:CancellationException){throw e}catch(e:Exception){
                playState="error";playError=if(e is Drive115.DriveError)e.message.orEmpty() else e.javaClass.simpleName+"："+(e.message?.takeUnless{it.contains("http")||it.contains("token",true)||it.contains("cookie",true)}?:"请重试")
                videoTitle.text="无法播放：$playError · 可点重试或下一首"
                showControls()
            }
        }
    }
    private fun nextSong(natural:Boolean=false){
        finishLearning(if(natural)"natural" else if(playState=="error")"error" else "skip")
        playJob?.cancel();if(activeSong!=null)db.removeQueue(activeQueueId)
        activeSong=null;activeQueueId=0;playQueue(force=true)
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        if(::videoPanel.isInitialized&&videoPanel.visibility==View.VISIBLE){
            if(menuShown&&event.action==KeyEvent.ACTION_DOWN)armMenuHide()
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_B){if(event.action==KeyEvent.ACTION_UP)onBackPressedDispatcher.onBackPressed();return true}
            if(event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)&&!menuShown){
                if(event.action==KeyEvent.ACTION_UP)showControls();return true
            }
            if(event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_ENTER)){
                if(event.action==KeyEvent.ACTION_UP)playerOverlay.evaluateJavascript("window.remoteConfirm && window.remoteConfirm()",null);return true
            }
            if(event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)){
                if(event.action==KeyEvent.ACTION_UP){if(event.keyCode==KeyEvent.KEYCODE_MEDIA_NEXT)nextSong() else if(engine.isPlaying){engine.pause();playState="paused"}else{engine.resume();playState="playing"}};return true
            }
            return super.dispatchKeyEvent(event)
        }
        if(::web.isInitialized&&event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_BUTTON_A)){
            if(event.action==KeyEvent.ACTION_UP)web.evaluateJavascript("(function(){var e=document.activeElement;if(e&&e.matches('button,a[href]'))e.click();else if(e&&e.tagName==='INPUT')e.focus();})()",null)
            return true
        }
        if(event.keyCode==KeyEvent.KEYCODE_BUTTON_B){if(event.action==KeyEvent.ACTION_UP)onBackPressedDispatcher.onBackPressed();return true}
        return super.dispatchKeyEvent(event)
    }
    override fun onResume(){super.onResume();if(::web.isInitialized&&web.progress==100)web.reload()}
    override fun onStop(){learningProgress.discontinuity();learningToken?.let{token->learn{learning.checkpoint(token,learningProgress,durationMs)}};super.onStop();if(::engine.isInitialized&&engine.isPlaying){engine.pause();playState="paused"}}
    override fun onDestroy(){finishLearning("interrupted");phoneServer?.stop();menuHideJob?.cancel();playJob?.cancel();engine.detach(videoView);engine.release();playerOverlay.removeJavascriptInterface("Player");playerOverlay.destroy();web.removeJavascriptInterface("Woniu");web.destroy();db.close();super.onDestroy()}
    private fun switchVocal(target:String){
        val song=activeSong?:return;if(target==vocalMode)return
        if(audioLabels.size>1){val named=audioLabels.indexOfFirst{if(target=="accompaniment")it.contains("伴奏")||it.contains("伴唱") else it.contains("原唱")};val index=if(named>=0)named else if(target=="accompaniment")1 else 0
            engine.setChannelMode("stereo");engine.setVocalMode(if(index==0)"original" else "accompaniment",index,audioLabels.size);db.audio(song.id,"track",index);activeSong=db.song(song.id)?.copy(queueId=activeQueueId);vocalMode=target
        }else if(counterpart?.edition?.contains("伴")== (target=="accompaniment")){val at=positionMs;db.replaceQueueSong(activeQueueId,counterpart!!.id);playQueue(activeQueueId,true,at)}
        else if(song.audioMode in listOf("left","right")){engine.setChannelMode(if(target=="accompaniment")song.audioMode else "stereo");vocalMode=target}
        else{android.widget.Toast.makeText(this,if(playState=="loading")"正在解析音轨，请稍候" else "本资源没有可识别的"+(if(target=="accompaniment")"伴奏" else "原唱")+"版本",android.widget.Toast.LENGTH_SHORT).show();return}
        android.widget.Toast.makeText(this,if(vocalMode=="accompaniment")"已切换伴奏" else "已切换原唱",android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun systemVolumePercent():Int{val audio=getSystemService(AUDIO_SERVICE) as android.media.AudioManager;return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)*100/audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)}
    inner class PlayerBridge{
        @JavascriptInterface fun queue():String=snapshot().toString()

        @JavascriptInterface fun state():String=JSONObject().put("title",activeSong?.title.orEmpty()).put("artist",activeSong?.artist.orEmpty())
            .put("position",positionMs).put("duration",durationMs).put("state",playState).put("error",playError)
            .put("vocalMode",vocalMode).put("volume",systemVolumePercent()).put("fullscreen",videoFullscreen).put("tracks",JSONArray(audioLabels)).put("channels",audioChannels).put("channelControl",engine.supportsChannelSelection).put("channelMode",engine.selectedChannelMode).put("edition",activeSong?.edition.orEmpty()).put("counterpart",counterpart?.edition.orEmpty()).toString()
        @JavascriptInterface fun seek(value:Int){runOnUiThread{learningProgress.discontinuity();engine.seekTo(durationMs*value.coerceIn(0,1000)/1000);armMenuHide()}}
        @JavascriptInterface fun action(action:String){runOnUiThread{
            when(action){
                "toggle_menu"->{if(menuShown)hideControls()else showControls();return@runOnUiThread}
                "hold_menu"->{menuHideJob?.cancel();return@runOnUiThread}
                "interact"->{armMenuHide();return@runOnUiThread}
                "pause"->{if(engine.isPlaying){engine.pause();playState="paused"}else{engine.resume();playState="playing"}}
                "next"->nextSong()
                "restart"->{engine.restart();playState="playing"}
                "back"->{hideVideo();return@runOnUiThread}
                "retry"->playQueue(activeQueueId,true)
                "toggle_vocal"->switchVocal(if(vocalMode=="original")"accompaniment" else "original")
                "original","accompaniment"->switchVocal(action)
                "counterpart"->{counterpart?.let{val at=positionMs;db.replaceQueueSong(activeQueueId,it.id);playQueue(activeQueueId,true,at)}}
                "toggle_fullscreen"->{videoFullscreen=!videoFullscreen;db.setting("video_fullscreen",videoFullscreen.toString(),true);applyVideoFrame()}
                "fit","zoom","fill"->{videoView.resizeMode=when(action){"fit"->AspectRatioFrameLayout.RESIZE_MODE_FIT;"zoom"->AspectRatioFrameLayout.RESIZE_MODE_ZOOM;else->AspectRatioFrameLayout.RESIZE_MODE_FILL};engine.setResizeMode(videoView.resizeMode);db.setting("video_resize",videoView.resizeMode.toString(),true)}
                "stereo","left","right"->{activeSong?.let{engine.setChannelMode(action);if(action=="stereo")engine.setVocalMode("original",0,audioLabels.size);db.audio(it.id,if(action=="stereo")"tracks" else action,0);activeSong=db.song(it.id)?.copy(queueId=activeQueueId)}}
                else->{if(action.startsWith("track:")){val index=action.substringAfter(':').toIntOrNull();if(index!=null&&index in audioLabels.indices)activeSong?.let{engine.setChannelMode("stereo");engine.setVocalMode("accompaniment",index,audioLabels.size);db.audio(it.id,"track",index);activeSong=db.song(it.id)?.copy(queueId=activeQueueId)}}}
            };armMenuHide()
        }}
    }
    private fun songJson(song:Song)=JSONObject().put("id",song.id).put("title",song.title).put("artist",song.artist.ifBlank{"未识别歌手"})
        .put("mediaType",if(song.name.substringAfterLast('.').lowercase() in listOf("mp3","flac","wav","aac","m4a","ogg"))"AUDIO" else "MV").put("language",song.language).put("genre",song.genre).put("edition",song.edition).put("durationMs",0).put("coverUrl",JSONObject.NULL)
    private fun songsJson(songs:List<Song>)=JSONArray().apply{songs.forEach{put(songJson(it))}}
    private fun snapshot()=JSONObject().put("state",playState).put("playing",activeSong?.let{JSONObject().put("queueId",activeQueueId).put("song",songJson(it)).put("orderedByNick","家人")}?:JSONObject.NULL)
        .put("tvOnline",true).put("position_ms",positionMs).put("duration_ms",durationMs).put("error",playError)
        .put("list",JSONArray().apply{db.queue().filter{it.queueId!=activeQueueId||activeSong==null}.forEach{put(JSONObject().put("queueId",it.queueId).put("song",songJson(it)).put("orderedBy","local-family").put("orderedByNick","家人").put("status","waiting"))}})
    private fun phoneSnapshot(ids:List<String>,after:Long=Long.MIN_VALUE):JSONObject {
        require(ids.size<=200&&ids.all{it.matches(Regex("[0-9]{1,30}"))})
        val head=db.readableDatabase.rawQuery("SELECT id,song_id FROM queue WHERE id>? AND id<>? ORDER BY id LIMIT 51",arrayOf(after.toString(),(if(activeSong!=null)activeQueueId else Long.MIN_VALUE).toString())).use{c->buildList{while(c.moveToNext())add(c.getLong(0) to c.getString(1))}}
        val rows=JSONArray();head.take(50).forEach{(id,songId)->db.song(songId)?.let{rows.put(JSONObject().put("queueId",id.toString()).put("song",songJson(it)))}}
        val queued=JSONArray()
        if(ids.isNotEmpty())db.readableDatabase.rawQuery("SELECT DISTINCT song_id FROM queue WHERE song_id IN ("+ids.joinToString(","){"?"}+")",ids.toTypedArray()).use{c->while(c.moveToNext())queued.put(c.getString(0))}
        activeSong?.id?.takeIf{it in ids}?.let{queued.put(it)}
        return JSONObject().put("items",rows).put("list",rows).put("queuedIds",queued).put("hasMore",head.size>50).put("after",head.take(50).lastOrNull()?.first?.toString().orEmpty()).put("playing",activeSong?.let{JSONObject().put("song",songJson(it))}?:JSONObject.NULL).put("player",JSONObject(PlayerBridge().state()))
    }
    inner class LocalBridge{
        @JavascriptInterface fun songbookVisible():Boolean=::videoPanel.isInitialized&&videoPanel.visibility!=View.VISIBLE
        @JavascriptInterface fun setTheme(value:String){if(value in listOf("dark","light"))db.setting("ui_theme",value,true)}
        @JavascriptInterface fun openPhone(){runOnUiThread{showPhonePairing()}}
        @JavascriptInterface fun openManagement(){runOnUiThread{this@SongbookActivity.openManagement()}}
        @JavascriptInterface fun request(path:String,method:String,body:String):String=try{
            require(path.length<8192&&body.length<65536)
            val uri=Uri.parse("https://app.woniu.local$path");val route=uri.path.orEmpty()
            val data=if(body.isBlank())JSONObject() else JSONObject(body)
            val result:Any=when{
                method=="POST"&&route=="/phone/state"->{val raw=data.optJSONArray("ids")?:JSONArray();require(raw.length()<=200);phoneSnapshot((0 until raw.length()).map{raw.getString(it)})}
                method=="GET"&&route=="/phone/queue"->phoneSnapshot(emptyList(),uri.getQueryParameter("after")?.toLongOrNull()?:Long.MIN_VALUE)
                method=="GET"&&route=="/phone/songs"->{val rows=db.phoneSongs(uri.getQueryParameter("after").orEmpty().take(30),uri.getQueryParameter("keyword").orEmpty().take(80),uri.getQueryParameter("artist").orEmpty().take(80));JSONObject().put("items",songsJson(rows.take(20))).put("hasMore",rows.size>20).put("after",rows.take(20).lastOrNull()?.id.orEmpty())}
                method=="GET"&&route=="/phone/artists"->{val rows=db.phoneArtists(uri.getQueryParameter("after").orEmpty().take(80),uri.getQueryParameter("keyword").orEmpty().take(80),uri.getQueryParameter("scope")?:"chinese");JSONObject().put("items",JSONArray().apply{rows.take(20).forEach{put(JSONObject().put("name",it))}}).put("hasMore",rows.size>20).put("after",rows.take(20).lastOrNull().orEmpty())}
                method=="GET"&&route=="/recommendations"->learning.recommendations(uri.getQueryParameter("refresh")=="1").put("warning",learningWarning).put("backupDirty",db.setting("smart_revision","0")!=db.setting("smart_backed_revision","0")).put("backupStatus",backupStatus)
                method=="POST"&&route=="/recommendations/dismiss"->{learning.dismiss(db.song(data.getString("id"))?:error("歌曲不存在"));learning.recommendations()}
                method=="POST"&&route=="/recommendations/backup"->{backupSmart();JSONObject().put("status","已开始备份曲库与智能偏好")}
                method=="GET"&&route=="/queue"->snapshot()
                method=="GET"&&route=="/player"->JSONObject(PlayerBridge().state())
                method=="GET"&&route=="/songs"->songsJson(db.search(uri.getQueryParameter("keyword").orEmpty(),offset=uri.getQueryParameter("offset")?.toIntOrNull()?:0,limit=uri.getQueryParameter("limit")?.toIntOrNull()?:200))
                method=="GET"&&route=="/songs/new"->songsJson(db.search(""))
                method=="GET"&&route=="/chart/candidates"->songsJson(db.titleCandidates(ChartMatcher.titleKey(uri.getQueryParameter("title").orEmpty())))
                method=="POST"&&route=="/chart/match"->{val a=data.getJSONArray("artists");discovery.choose(data.getString("title"),(0 until a.length()).map{a.getString(it)},data.getString("id"));JSONObject(discovery.snapshot)}
                method=="GET"&&route=="/discovery"->{refreshDiscovery(uri.getQueryParameter("refresh")=="1");JSONObject(discovery.snapshot)}
                method=="GET"&&route=="/ranking"->JSONArray()
                method=="GET"&&route=="/favorites/ids"->JSONArray(db.search("",true).map{it.id})
                method=="GET"&&route=="/favorites"->songsJson(db.search("",true))
                route.startsWith("/favorites/")&&method in listOf("POST","DELETE")->{
                    val id=route.substringAfterLast('/');require(db.song(id)!=null){"歌曲不存在"};db.favorite(id,method=="POST");JSONObject()
                }
                method=="GET"&&route=="/browse/artists"->ArtistDirectory.page(db,uri.getQueryParameter("scope")?:"chinese",uri.getQueryParameter("after").orEmpty(),uri.getQueryParameter("keyword").orEmpty(),uri.getQueryParameter("initial").orEmpty())
                method=="GET"&&route=="/browse/songs"->{
                    songsJson(db.search("",category=uri.getQueryParameter("tag").orEmpty(),artist=uri.getQueryParameter("artist").orEmpty(),language=uri.getQueryParameter("language").orEmpty(),offset=uri.getQueryParameter("offset")?.toIntOrNull()?:0,limit=uri.getQueryParameter("limit")?.toIntOrNull()?:200,scope=uri.getQueryParameter("scope")?:"all"))
                }
                method=="GET"&&route=="/browse/tags"->{
                    val result=JSONArray()
                    for((label,condition) in listOf("华语歌曲" to LibraryLanguage.condition("chinese"),"其他外语" to "(${LibraryLanguage.condition("foreign")} AND language<>'英语')","待识别语种" to "language=''")){
                        val amount=db.readableDatabase.rawQuery("SELECT COUNT(*) FROM songs WHERE $condition",null).use{it.moveToFirst();it.getInt(0)};result.put(JSONObject().put("name",label).put("songCount",amount))
                    }
                    val english=db.readableDatabase.rawQuery("SELECT COUNT(*) FROM songs WHERE language='英语'",null).use{it.moveToFirst();it.getInt(0)}
                    result.put(JSONObject().put("name","英文歌曲").put("songCount",english))
                    for((label,column) in listOf("分类" to "genre","版本" to "edition","目录" to "source_group")){
                        db.readableDatabase.rawQuery("SELECT $column,COUNT(*) FROM songs WHERE $column<>'' GROUP BY $column ORDER BY COUNT(*) DESC",null).use{c->while(c.moveToNext())result.put(JSONObject().put("name",label+"："+c.getString(0)).put("songCount",c.getInt(1)))}
                    }
                    val unknown=db.readableDatabase.rawQuery("SELECT COUNT(*) FROM songs WHERE artist='' OR artist='佚名'",null).use{it.moveToFirst();it.getInt(0)}
                    if(unknown>0)result.put(JSONObject().put("name","待整理歌手").put("songCount",unknown))
                    result

                }
                method=="GET"&&route=="/browse/languages"->{val items=JSONArray();db.readableDatabase.rawQuery("SELECT language,COUNT(*) FROM songs WHERE language<>'' GROUP BY language ORDER BY COUNT(*) DESC",null).use{c->while(c.moveToNext())items.put(JSONObject().put("name",c.getString(0)).put("songCount",c.getInt(1)))};items}
                method=="GET"&&route=="/library/status"->JSONObject().put("songs",db.count()).put("metadataVersion",db.setting("metadata_rule_version")).put("targetVersion",LibraryMetadataPipeline.VERSION)
                method=="GET"&&route in listOf("/history","/playlists")->JSONArray()
                route.startsWith("/room/host")->JSONObject().put("claimed",true).put("isHost",true).put("hostNickname","家人")
                route=="/control"&&method=="POST"->{
                    val args=data.optJSONObject("params")?:JSONObject()
                    when(data.getString("action")){
                        "order"->{val id=args.getString("song_id");val selectedSong=db.song(id)?:error("歌曲不存在");if(activeSong!=null&&playState=="playing"){db.enqueueUnique(id);learn{learning.ordered(selectedSong,args.optString("origin"))}}else{val selected=db.selectForPlayback(id);learn{learning.ordered(selectedSong,args.optString("origin"))};runOnUiThread{playQueue(selected,true)}}}
                        "cancel"->db.removeQueue(args.getLong("queue_id"))
                        "top"->db.topQueue(args.getLong("queue_id"))
                        "play"->runOnUiThread{if(activeSong==null)playQueue()else if(playState=="error")playQueue(activeQueueId,true)else{engine.resume();playState="playing";showVideo()}}
                        "show_player"->runOnUiThread{if(activeSong==null)playQueue()else showVideo()}
                        "player_action"->{val action=args.getString("value");require(action in listOf("toggle_vocal","original","accompaniment","counterpart","stereo","left","right") || (action.startsWith("track:") && action.substringAfter(':').toIntOrNull()?.let{it in audioLabels.indices}==true)){"音轨尚未就绪"};PlayerBridge().action(action)}
                        "audio"->PlayerBridge().action("toggle_vocal")
                        "volume"->runOnUiThread{val audio=getSystemService(AUDIO_SERVICE) as android.media.AudioManager;engine.setPlaybackVolume(100);audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,if(args.optInt("delta",1)>0)android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,android.media.AudioManager.FLAG_SHOW_UI);if(audio.isVolumeFixed)android.widget.Toast.makeText(this@SongbookActivity,"此设备系统音量固定，请通过电视或功放调节",android.widget.Toast.LENGTH_SHORT).show()}


                        "pause"->runOnUiThread{engine.pause();playState="paused"}
                        "next"->runOnUiThread{nextSong()}
                        "restart"->runOnUiThread{engine.restart();playState="playing";showVideo()}
                        else->error("不支持的播放操作")
                    };snapshot()
                }
                else->error("这个功能尚未接入本地版本")
            }
            JSONObject().put("ok",true).put("data",result).toString()
        }catch(e:Exception){JSONObject().put("ok",false).put("message",e.message?:"本地操作失败").toString()}
    }
}

