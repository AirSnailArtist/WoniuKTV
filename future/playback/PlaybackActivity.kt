package com.pocketktv.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.homektv.tv.player.PlaybackEngine
import kotlinx.coroutines.*
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class PocketActivity: ComponentActivity() {
    private val bg=Color.rgb(16,19,27);private val surface=Color.rgb(26,31,43)
    private val gold=Color.rgb(244,199,107);private val ink=Color.rgb(242,244,250);private val muted=Color.rgb(157,166,186)
    private lateinit var db:LibraryDb;private lateinit var drive:Drive115;private lateinit var engine:PlaybackEngine
    private lateinit var root:LinearLayout;private lateinit var header:LinearLayout;private lateinit var library:LinearLayout
    private lateinit var status:TextView;private lateinit var resultsHint:TextView;private lateinit var now:TextView
    private lateinit var search:EditText;private lateinit var list:ListView;private lateinit var playerView:PlayerView
    private lateinit var placeholder:TextView;private lateinit var vocalButton:Button;private lateinit var loginButton:Button
    private lateinit var scanButton:Button;private lateinit var pauseButton:Button
    private var rows=emptyList<Song>();private var mode="all";private var current:Song?=null
    private var scanJob:Job?=null;private var playJob:Job?=null;private var searchJob:Job?=null
    private var companionMode=false;private var fullScreen=false;private var resolving=false
    private var autoRefreshes=0;private var playbackGeneration=0L
    private val ticker=Handler(Looper.getMainLooper())
    private val heartbeat=object:Runnable {override fun run(){
        if(::engine.isInitialized) pauseButton.text=if(engine.isPlaying)"暂停" else "继续"
        ticker.postDelayed(this,800)
    }}

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        db=LibraryDb(this);drive=Drive115(SecretStore(this))
        engine=PlaybackEngine(this,{}, {nextSong()}, {message ->
            if(current!=null && autoRefreshes<1 && !resolving){
                autoRefreshes++;resolve(current!!,engine.currentPositionMs,true)
            } else { resolving=false;setStatus("播放失败：$message。可重试或切歌；部分编码可能不兼容本机。") }
        })
        buildUi();engine.attach(playerView)
        engine.onAudioTracksChanged={tracks ->
            current?.let{song -> applyAudio(song);setStatus("正在播放 · ${tracks.size} 条音轨 · "+if(companionMode)"伴唱" else "原唱")}
        }
        refresh(true);ticker.post(heartbeat)
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun panel(color:Int=surface)=GradientDrawable().apply{setColor(color);cornerRadius=dp(14).toFloat()}
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun text(value:String,size:Float=14f,color:Int=ink)=TextView(this).apply{text=value;textSize=size;setTextColor(color);setPadding(dp(8),dp(5),dp(8),dp(5))}
    private fun button(label:String,action:()->Unit)=Button(this).apply{
        text=label;textSize=13f;isAllCaps=false;setTextColor(ink);background=panel();minHeight=dp(42);minimumWidth=dp(64)
        setPadding(dp(11),dp(4),dp(11),dp(4));setOnClickListener{action()}
        layoutParams=LinearLayout.LayoutParams(-2,dp(42)).apply{setMargins(dp(3),dp(2),dp(3),dp(2))}
    }
    private fun field(hintText:String)=EditText(this).apply{
        hint=hintText;setTextColor(ink);setHintTextColor(muted);textSize=15f;isSingleLine=true
        setPadding(dp(12),dp(8),dp(12),dp(8));background=panel()
    }
    private fun buildUi(){
        root=column().apply{setBackgroundColor(bg);setPadding(dp(12),dp(8),dp(12),dp(8))};setContentView(root)
        header=row()
        header.addView(column().apply{
            addView(text("口袋K歌",25f,gold).apply{setTypeface(typeface,Typeface.BOLD)})
            addView(text("本地曲库 · 云端开唱",11f,muted))
        },LinearLayout.LayoutParams(0,-2,1f))
        loginButton=button("115 登录"){accountDialog()};header.addView(loginButton)
        header.addView(button("选择曲库"){openFolderBrowser()})
        scanButton=button("续扫"){toggleScan()};header.addView(scanButton)
        header.addView(button("设置"){settingsDialog()})
        root.addView(header)
        status=text("正在读取本地曲库…",12f,muted);root.addView(status)
        val content=row().apply{gravity=Gravity.TOP}
        library=column().apply{setPadding(0,0,dp(10),0)}
        val tabs=row()
        listOf("all" to "曲库","favorites" to "收藏","queue" to "已点").forEach{(key,label)->tabs.addView(button(label){mode=key;refresh()})}
        library.addView(tabs)
        search=field("搜歌名 / 歌手 / 拼音首字母")
        search.doAfterTextChanged{refresh()};library.addView(search,LinearLayout.LayoutParams(-1,dp(44)))
        resultsHint=text("",11f,muted);library.addView(resultsHint)
        list=ListView(this).apply{divider=null;dividerHeight=dp(5);setBackgroundColor(bg)}
        list.setOnItemClickListener{_,_,position,_->
            rows.getOrNull(position)?.let {if(mode=="queue") queueActions(it) else songActions(it)}
        }
        library.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        content.addView(library,LinearLayout.LayoutParams(0,-1,0.46f))
        val stage=column().apply{background=panel();setPadding(dp(8),dp(8),dp(8),dp(8))}
        now=text("今晚，唱你喜欢的歌",19f,gold);stage.addView(now)
        val frame=FrameLayout(this)
        playerView=PlayerView(this).apply{setBackgroundColor(Color.BLACK);keepScreenOn=true;controllerShowTimeoutMs=3000}
        frame.addView(playerView,FrameLayout.LayoutParams(-1,-1))
        placeholder=text("先登录 115，选择曲库文件夹\n扫描后即可搜歌、点歌、直接播放\n\n歌曲留在 115，不下载整库",16f,muted).apply{gravity=Gravity.CENTER;setBackgroundColor(Color.rgb(12,15,21))}
        frame.addView(placeholder,FrameLayout.LayoutParams(-1,-1))
        stage.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        val controls=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
        val bar=row()
        pauseButton=button("继续"){playPause()};bar.addView(pauseButton)
        bar.addView(button("重唱"){if(current!=null)engine.restart()})
        bar.addView(button("切歌"){nextSong()})
        vocalButton=button("原唱"){companionMode=!companionMode;current?.let{applyAudio(it)}};bar.addView(vocalButton)
        bar.addView(button("音轨设置"){audioDialog()})
        bar.addView(button("全屏"){fullScreen=!fullScreen;library.visibility=if(fullScreen)View.GONE else View.VISIBLE;header.visibility=if(fullScreen)View.GONE else View.VISIBLE})
        controls.addView(bar);stage.addView(controls)
        content.addView(stage,LinearLayout.LayoutParams(0,-1,0.54f))
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun setStatus(message:String){status.text=message}
    private fun refresh(announce:Boolean=false){
        searchJob?.cancel()
        val query=search.text.toString();val selectedMode=mode
        searchJob=lifecycleScope.launch{
            delay(160)
            val (items,total)=withContext(Dispatchers.IO){
                (if(selectedMode=="queue") db.queue() else db.search(query,selectedMode=="favorites")) to db.count()
            }
            rows=items;list.adapter=SongAdapter()
            resultsHint.text=if(selectedMode=="queue")"已点 ${items.size} 首 · 点击可播放或移除" else "曲库 $total 首 · 显示 ${items.size} 首 · 点击点歌"
            loginButton.text=if(drive.loggedIn)"115 已登录" else "115 登录"
            if(announce && current==null && scanJob?.isActive!=true) setStatus(when{
                !drive.loggedIn -> "先登录自己的 115，再选择曲库文件夹。所有索引保存在本机。"
                total==0 -> "已登录 115 · 点击「选择曲库」，建议先测试一个小目录"
                else -> "本机已收录 $total 首 · "+db.setting("root_path","尚未选择曲库")
            })
        }
    }
    private inner class SongAdapter:BaseAdapter(){
        override fun getCount()=rows.size
        override fun getItem(position:Int)=rows[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View{
            val song=rows[position]
            return column().apply{
                background=panel();setPadding(dp(5),dp(5),dp(5),dp(5))
                addView(text((if(song.favorite)"★  " else "")+song.title,16f))
                addView(text(song.artist.ifBlank{"未识别歌手"}+"  ·  "+song.name.substringAfterLast('.').uppercase(Locale.ROOT),11f,muted))
            }
        }
    }
    private fun songActions(song:Song){
        AlertDialog.Builder(this).setTitle(song.title).setItems(arrayOf("加入已点","立即播放",if(song.favorite)"取消收藏" else "收藏", "查看文件位置")){_,which->
            when(which){
                0->lifecycleScope.launch{withContext(Dispatchers.IO){db.enqueue(song.id)};refresh();if(current==null)startQueue()}
                1->lifecycleScope.launch{val qid=withContext(Dispatchers.IO){db.enqueue(song.id)};current?.let{db.setting("active",it.queueId.toString(),true)};autoRefreshes=0;resolve(song.copy(queueId=qid));refresh()}
                2->lifecycleScope.launch{withContext(Dispatchers.IO){db.favorite(song.id,!song.favorite)};refresh()}
                3->AlertDialog.Builder(this).setTitle("115 文件位置").setMessage(song.path).setPositiveButton("知道了",null).show()
            }
        }.setNegativeButton("取消",null).show()
    }
    private fun queueActions(song:Song){
        AlertDialog.Builder(this).setTitle(song.title).setItems(arrayOf("播放这首","从已点移除")){_,which->
            if(which==0){autoRefreshes=0;resolve(song)}else if(current?.queueId==song.queueId){nextSong()}else{db.removeQueue(song.queueId);refresh()}
        }.show()
    }
    private fun startQueue(){lifecycleScope.launch{
        val items=withContext(Dispatchers.IO){db.queue()}
        val next=items.firstOrNull()
        if(next!=null){autoRefreshes=0;resolve(next)}else setStatus("还没有已点歌曲，先从曲库选一首")
    }}
    private fun nextSong(){
        playJob?.cancel();playbackGeneration++;resolving=false
        current?.let{db.removeQueue(it.queueId)};current=null;engine.stop();placeholder.visibility=View.VISIBLE
        now.text="今晚，唱你喜欢的歌";startQueue();refresh()
    }
    private fun resolve(song:Song,position:Long=0,refreshUrl:Boolean=false){
        if(!drive.loggedIn){accountDialog();return}
        playJob?.cancel();val generation=++playbackGeneration
        current=song;resolving=true;now.text=song.title+if(song.artist.isBlank())"" else " · "+song.artist
        if(!refreshUrl)engine.stop()
        placeholder.visibility=View.VISIBLE;placeholder.text="正在连接 115…"
        setStatus(if(refreshUrl)"正在刷新播放地址…" else "正在获取播放地址 · ${song.title}")
        playJob=lifecycleScope.launch{
            try{
                val source=drive.source(song.pickCode)
                ensureActive();if(generation!=playbackGeneration)return@launch
                engine.play(song.id.toLongOrNull()?:song.id.hashCode().toLong(),source.url,source.headers,true)
                if(position>0)engine.seekTo(position)
                applyAudio(song);placeholder.visibility=View.GONE;resolving=false
                setStatus("已连接 115 · 正在缓冲 ${song.title}")
            }catch(e:CancellationException){throw e}catch(e:Exception){
                if(generation==playbackGeneration){resolving=false;placeholder.text="暂时无法播放\n${safeError(e)}\n点击继续可重试";setStatus(safeError(e))
                }
            }
        }
    }
    private fun playPause(){
        if(resolving)return
        val song=current
        if(song==null)startQueue()else if(engine.isPlaying)engine.pause()else if(engine.durationMs>0)engine.resume()else{autoRefreshes=0;resolve(song)}
    }
    private fun applyAudio(song:Song){
        vocalButton.text=if(companionMode)"伴唱" else "原唱"
        if(song.audioMode=="tracks"){
            engine.setChannelMode("stereo")
            engine.setVocalMode(if(companionMode)"accompaniment" else "original",song.accompaniment,maxOf(engine.audioTracks().size,2))
            if(companionMode && engine.audioTracks().size<=song.accompaniment)setStatus("尚未发现指定伴奏音轨；若伴奏在左右声道，请打开「音轨设置」")
        }else{
            engine.setVocalMode("original",0,1)
            engine.setChannelMode(if(companionMode)song.audioMode else "stereo")
            if(companionMode && engine.audioChannelCount==1)setStatus("源文件为单声道，无法从左右声道分出伴奏")
        }
    }
    private fun audioDialog(){
        val song=current?:run{setStatus("播放歌曲后可设置原伴唱音轨");return}
        val names=engine.audioTracks()
        val options=names.mapIndexed{i,name->"伴奏在音轨 ${i+1}（$name）"}+listOf("伴奏在左声道","伴奏在右声道")
        AlertDialog.Builder(this).setTitle("选择真实伴奏所在位置").setItems(options.toTypedArray()){_,which->
            val audioMode=if(which<names.size)"tracks" else if(which==names.size)"left" else "right"
            val updated=song.copy(audioMode=audioMode,accompaniment=if(audioMode=="tracks")which else 0)
            db.audio(song.id,updated.audioMode,updated.accompaniment);current=updated;companionMode=true;applyAudio(updated)
            setStatus("已记住这首歌的伴唱设置；普通 MV 没有独立伴奏时无法消除人声")
        }.setNegativeButton("取消",null).show()
    }
    private fun accountDialog(){
        if(scanJob?.isActive==true){setStatus("请先暂停扫描，再切换账号");return}
        val choices=if(drive.loggedIn)arrayOf("重新扫码 / 切换 115 账号","退出当前 115 账号")else arrayOf("扫码登录 115")
        AlertDialog.Builder(this).setTitle("115 账号").setMessage("凭据加密保存在本机，不上传到我们的服务器。会话扫码使用 TV 登录类型，可能使已有 115 TV 会话退出。切换不同账号会清除本机旧曲库索引和已点队列。").setPositiveButton(choices[0]){_,_->loginQr()}
            .apply{if(drive.loggedIn)setNeutralButton("退出登录"){_,_->
                playJob?.cancel();playbackGeneration++;engine.stop();current=null;drive.logout();refresh();placeholder.visibility=View.VISIBLE;placeholder.text="已退出 115，重新登录后可播放"
            }}.setNegativeButton("取消",null).show()
    }
    private fun loginQr(){
        playJob?.cancel();playbackGeneration++;engine.stop();current=null
        val box=column().apply{setPadding(dp(20),dp(12),dp(20),dp(12));gravity=Gravity.CENTER}
        val image=ImageView(this);box.addView(image,LinearLayout.LayoutParams(dp(230),dp(230)))
        val message=text("正在获取二维码…",14f);box.addView(message)
        val dialog=AlertDialog.Builder(this).setTitle("用手机 115 扫码确认登录").setView(box).setNegativeButton("取消",null).create()
        var job:Job?=null;dialog.setOnDismissListener{job?.cancel()};dialog.show()
        job=lifecycleScope.launch{
            try{
                val qr=drive.startLogin(db.setting("appid"))
                val bitmap=withContext(Dispatchers.Default){
                    val matrix=QRCodeWriter().encode(qr.content,BarcodeFormat.QR_CODE,600,600,mapOf(EncodeHintType.MARGIN to 2))
                    Bitmap.createBitmap(600,600,Bitmap.Config.RGB_565).apply{
                        val pixels=IntArray(600*600){i->if(matrix[i%600,i/600])Color.BLACK else Color.WHITE};setPixels(pixels,0,600,0,0,600,600)
                    }
                }
                image.setImageBitmap(bitmap);message.text="等待扫码 · 二维码仅来自 115"
                var failures=0
                repeat(110){
                    delay(1800)
                    val state=try{drive.poll(qr).also{failures=0}}catch(e:CancellationException){throw e}catch(e:Exception){
                        failures++;if(failures>=4)throw e;message.text="网络暂时波动，正在重试…";return@repeat
                    }
                    when(state){
                        1->message.text="已扫码，请在手机上确认"
                        2->{drive.finishLogin(qr);withContext(Dispatchers.IO){db.bindAccount(drive.accountId)}
                            dialog.dismiss();refresh();setStatus("115 登录成功，点击「选择曲库」");return@launch}
                        -1->error("二维码已过期，请关闭后重新扫码")
                        -2->error("已取消登录")
                    }
                }
                message.text="二维码已过期，请关闭后重新扫码"
            }catch(e:CancellationException){throw e}catch(e:Exception){message.text=safeError(e)}
        }
    }
    private fun openFolderBrowser(){
        if(!drive.loggedIn){accountDialog();return}
        if(scanJob?.isActive==true){setStatus("先暂停当前扫描，再选择新的曲库目录");return}
        val box=column().apply{setPadding(dp(14),dp(6),dp(14),dp(6))}
        val pathView=text("115 根目录",14f,gold);box.addView(pathView)
        val folderList=ListView(this);box.addView(folderList,LinearLayout.LayoutParams(-1,dp(240)))
        val tools=row();box.addView(tools)
        var task=ScanTask("0","/",0);var entries=emptyList<DriveEntry>();val parents=mutableListOf<ScanTask>()
        var job:Job?=null;var loading=false
        val dialog=AlertDialog.Builder(this).setTitle("选择要扫描的曲库文件夹").setView(box).setPositiveButton("扫描当前目录",null).setNegativeButton("关闭",null).create()
        fun load(){
            job?.cancel();loading=true;pathView.text=task.path+" · 正在加载…";folderList.adapter=null
            job=lifecycleScope.launch{
                try{
                    val result=drive.list(task.cid,task.offset);entries=result.entries
                    pathView.text=task.path+" · ${task.offset+1}–${task.offset+entries.size} / ${result.total}"
                    folderList.adapter=ArrayAdapter(this@PocketActivity,android.R.layout.simple_list_item_1,entries.map{(if(it.folder)"▸  " else "♪  ")+it.name})
                    tools.removeAllViews()
                    if(parents.isNotEmpty())tools.addView(button("上级"){task=parents.removeAt(parents.lastIndex);load()})
                    if(task.offset>0)tools.addView(button("上一页"){task=task.copy(offset=maxOf(0,task.offset-500));load()})
                    if(task.offset+entries.size<result.total)tools.addView(button("下一页"){task=task.copy(offset=task.offset+entries.size);load()})
                }catch(e:CancellationException){throw e}catch(e:Exception){pathView.text=safeError(e)}finally{loading=false}
            }
        }
        folderList.setOnItemClickListener{_,_,position,_->if(!loading)entries.getOrNull(position)?.let{entry->
            if(entry.folder){parents.add(task);task=ScanTask(entry.id,task.path.trimEnd('/')+"/"+entry.name,0);load()}
            else setStatus("这是视频文件，请选择它所在的文件夹进行扫描")
        }}
        dialog.setOnDismissListener{job?.cancel()};dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            if(!loading){val selected=task;dialog.dismiss()
                AlertDialog.Builder(this).setTitle("扫描这个文件夹？").setMessage("${selected.path}\n\n会递归读取歌曲文件名，生成本地索引，不下载视频。大型曲库首次扫描可能较久，可暂停续扫。建议先测试小目录。").setPositiveButton("开始扫描"){_,_->
                    lifecycleScope.launch{withContext(Dispatchers.IO){db.startScan(selected.cid,selected.path)};runScan()}
                }.setNegativeButton("取消",null).show()
            }
        };load()
    }
    private fun toggleScan(){
        if(scanJob?.isActive==true){scanJob?.cancel();scanButton.text="续扫";setStatus("正在暂停扫描并保存断点…");return}
        if(!drive.loggedIn){accountDialog();return}
        lifecycleScope.launch{
            val task=withContext(Dispatchers.IO){db.nextFolder()}
            if(task!=null)runScan()else openFolderBrowser()
        }
    }
    private fun runScan(){
        if(scanJob?.isActive==true)return
        scanButton.text="暂停扫描"
        scanJob=lifecycleScope.launch{
            try{
                while(true){
                    ensureActive()
                    val task=withContext(Dispatchers.IO){db.nextFolder()}?:break
                    setStatus("扫描中 · "+task.path+" · 已读 ${task.offset} 项")
                    val page=drive.list(task.cid,task.offset)
                    withContext(Dispatchers.IO){db.commitPage(task,page)}
                    refresh()
                }
                db.setting("scan_done","1",true);setStatus("曲库扫描完成 · ${db.count()} 首 · 可直接搜歌点唱")
            }catch(e:CancellationException){setStatus("已保存扫描断点，点击续扫可继续");throw e}
            catch(e:Exception){setStatus("扫描暂停：${safeError(e)}。已保留断点，可点击续扫。")}
            finally{scanButton.text="续扫";scanJob=null;refresh()}
        }
    }
    private fun settingsDialog(){
        AlertDialog.Builder(this).setTitle("口袋K歌 · 本地模式").setItems(arrayOf("文件名规则：歌手 - 歌名 / 歌名 - 歌手","官方开放平台 AppID（可选）","开源来源与说明")){_,which->
            when(which){
                0->AlertDialog.Builder(this).setTitle("选择曲库的命名规则").setSingleChoiceItems(arrayOf("歌手 - 歌名","歌名 - 歌手"),if(db.setting("name_rule")=="title_artist")1 else 0){dialog,index->
                    db.setting("name_rule",if(index==0)"artist_title" else "title_artist",true);dialog.dismiss();setStatus("命名规则已保存，重新扫描后应用到歌曲")
                }.show()
                1->{val input=field("填写你自己的 115 AppID；留空使用会话扫码").apply{setText(db.setting("appid"))}
                    AlertDialog.Builder(this).setTitle("官方 PKCE 授权配置").setMessage("不内置或借用他人的应用密钥。已有自己的 AppID 可填写；修改后需重新扫码。普通个人测试可留空使用会话扫码，接口可用性由 115 决定。").setView(input).setPositiveButton("保存"){_,_->db.setting("appid",input.text.toString().trim(),true)}.setNegativeButton("取消",null).show()}
                2->showAbout()
            }
        }.show()
    }
    private fun showAbout(){
        val text=buildString{
            append("基于 Home KTV 安卓播放器改造，复用播放、切音轨、文件名解析和 LRC 解析。曲库、队列改为本机 SQLite，115 直连。\n\n")
            append("此版为个人测试版。会话扫码不是已获批的官方 OAuth 应用；正式发布前需申请自己的 115 应用接入。扫描只读取文件列表，不改动云端文件。\n\n")
            append("音轨 / 左右声道切换需要源文件已有伴奏；不包含 AI 消音。当前不含麦克风混响、评分或 B站接入。\n\n")
            listOf("Home-KTV.txt","115driver.txt","115-plus-desktop.txt","115-sdk-go.txt","THIRD-PARTY.txt").forEach{name->
                append("\n—— $name ——\n");append(runCatching{assets.open("licenses/$name").bufferedReader().readText()}.getOrDefault(""));append("\n")
            }
        }
        val view=ScrollView(this).apply{addView(this@PocketActivity.text(text,12f))}
        AlertDialog.Builder(this).setTitle("关于与开源许可").setView(view).setPositiveButton("关闭",null).show()
    }
    private fun safeError(e:Exception):String {
        // Do not display network exception URLs: a URL may contain short-lived credentials.
        return if(e is Drive115.DriveError)e.message.orEmpty() else when(e){
            is java.net.SocketTimeoutException->"网络超时，请检查网络后重试"
            is java.net.UnknownHostException->"无法连接 115，请检查网络"
            is java.io.IOException->"网络连接中断，请稍后重试"
            else->e.message?.takeIf{!it.contains("http",true)&&!it.contains("token",true)&&!it.contains("cookie",true)}?.take(160)?:"操作未完成，请重试"
        }
    }
    override fun onStop(){super.onStop();engine.pause()}
    override fun onDestroy(){ticker.removeCallbacksAndMessages(null);scanJob?.cancel();playJob?.cancel();engine.detach(playerView);engine.release();super.onDestroy()}
}
