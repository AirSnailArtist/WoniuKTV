package com.pocketktv.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import java.io.File

/** Phase one: local library shell on the Home KTV Android foundation. Playback is intentionally deferred. */
class PocketActivity:ComponentActivity(){
    private var bg=Color.rgb(16,21,28);private var surface=Color.rgb(32,44,58);private var gold=Color.rgb(160,228,206)
    private var ink=Color.rgb(237,243,248);private var muted=Color.rgb(155,167,190)
    private lateinit var db:LibraryDb;private lateinit var drive:Drive115
    private lateinit var status:TextView;private lateinit var summary:TextView;private lateinit var location:TextView
    private lateinit var hint:TextView;private lateinit var list:ListView;private lateinit var search:EditText
    private lateinit var loginButton:Button;private lateinit var scanButton:Button;private lateinit var empty:TextView
    private var rows=emptyList<Song>();private var favorites=false;private var scanning:Job?=null;private var searching:Job?=null
    private var transferring:Job?=null;private var exportFile:File?=null
    private lateinit var home:LinearLayout;private lateinit var library:LinearLayout
    private var queueMode=false
    private fun showLibrary(onlyFavorites:Boolean=false,queued:Boolean=false){
        favorites=onlyFavorites;queueMode=queued;home.visibility=View.GONE;library.visibility=View.VISIBLE;refresh()
    }
    private fun showHome(){home.visibility=View.VISIBLE;library.visibility=View.GONE;search.clearFocus()}
    private fun management(){AlertDialog.Builder(this).setTitle("曲库管理").setItems(arrayOf("选择 115 曲库并扫描","继续 / 暂停扫描","目录备份与恢复","整理歌名与分类","设置")){_,i->
        when(i){0->browse("scan");1->toggleScan();2->catalogMenu();3->runTask("正在整理歌名与分类…"){CatalogRepository(db,drive,cacheDir).organize{done,total->runOnUiThread{status.text="正在整理 $done / $total 首"}};"曲库整理完成，共 ${db.count()} 首；可按语种、流派和版本浏览"};4->settings()}
    }.setNegativeButton("关闭",null).show()}
    private val exportLauncher=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val file=exportFile
        if(uri!=null&&file!=null)runTask("正在保存曲库目录…"){
            withContext(Dispatchers.IO){contentResolver.openOutputStream(uri,"w")!!.use{out->file.inputStream().use{it.copyTo(out)}}}
            "目录已保存。上传这份 JSON 到 115 后，新设备可从 115 导入。"
        }
    }
    private val importLauncher=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)runTask("正在导入曲库目录…"){
            val count=withContext(Dispatchers.IO){contentResolver.openInputStream(uri)!!.use{CatalogCodec.import(db,it,drive.accountId)}}
            "已导入 $count 首，无需扫描视频文件"
        }
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        db=LibraryDb(this);drive=Drive115(SecretStore(this));if(db.setting("ui_theme")=="light"){bg=Color.rgb(238,243,249);surface=Color.WHITE;gold=Color.rgb(37,102,203);ink=Color.rgb(33,53,78);muted=Color.rgb(83,106,133)};buildUi();refresh()
        status.text=if(drive.loggedIn)"已登录 115，可选择目录扫描，或直接导入已保存的曲库目录" else "第一步：扫码登录自己的 115 账号"
    }
    private fun dp(n:Int)=(resources.displayMetrics.density*n).toInt()
    private fun col()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun panel()=GradientDrawable().apply{setColor(surface);cornerRadius=dp(12).toFloat()}
    private fun label(value:String,size:Float=14f,color:Int=ink)=TextView(this).apply{text=value;textSize=size;setTextColor(color);setPadding(dp(10),dp(5),dp(10),dp(5))}
    private fun button(value:String,action:()->Unit)=Button(this).apply{
        text=value;textSize=13f;isAllCaps=false;setTextColor(ink)
        background=android.graphics.drawable.StateListDrawable().apply{
            addState(intArrayOf(android.R.attr.state_focused),GradientDrawable().apply{setColor(surface);cornerRadius=dp(12).toFloat();setStroke(dp(3),gold)})
            addState(intArrayOf(),panel())
        };minimumWidth=dp(62);minHeight=dp(44)
        setPadding(dp(11),dp(4),dp(11),dp(4));setOnClickListener{action()}
        layoutParams=LinearLayout.LayoutParams(-2,dp(44)).apply{setMargins(dp(4),dp(3),dp(4),dp(3))}
    }
    private fun field(value:String)=EditText(this).apply{hint=value;isSingleLine=true;textSize=15f;setTextColor(ink);setHintTextColor(muted);background=panel();setPadding(dp(14),dp(10),dp(14),dp(10))}
    private fun buildUi(){
        val root=col().apply{setBackgroundColor(bg);setPadding(dp(16),dp(10),dp(16),dp(10))};setContentView(root)
        val header=row()
        header.addView(col().apply{
            addView(label("窝牛家庭KTV",26f,gold).apply{setTypeface(typeface,Typeface.BOLD)})
            addView(label("把喜欢的歌，留在家里唱",11f,muted))
        },LinearLayout.LayoutParams(0,-2,1f))
        loginButton=button("115 登录"){accountDialog()};header.addView(loginButton)
        header.addView(button("曲库管理"){management()})
        scanButton=button("续扫"){toggleScan()};scanButton.visibility=View.GONE
        root.addView(header)
        val stats=row().apply{background=panel();setPadding(dp(6),dp(5),dp(6),dp(5))}
        summary=label("0 首歌曲",19f,gold);stats.addView(summary)
        location=label("尚未选择 115 曲库目录",12f,muted);stats.addView(location,LinearLayout.LayoutParams(0,-2,1f));root.addView(stats)
        status=label("",12f,muted);root.addView(status)
        val navigation=row();navigation.addView(button("首页"){showHome()});navigation.addView(button("歌曲"){showLibrary()});navigation.addView(button("收藏"){showLibrary(true)});navigation.addView(button("已点歌曲"){showLibrary(queued=true)});root.addView(navigation)
        home=col();root.addView(home,LinearLayout.LayoutParams(-1,0,1f))
        home.addView(label("今晚，想唱哪一首？",28f).apply{setPadding(dp(12),dp(22),dp(12),dp(16))})
        home.addView(label("你的 115 曲库 · 本地搜索 · 自由点歌",14f,muted))
        val cards=row()
        fun card(title:String,description:String,action:()->Unit)=col().apply{
            background=panel();isFocusable=true;isClickable=true;setPadding(dp(15),dp(18),dp(15),dp(18))
            addView(label(title,23f,gold));addView(label(description,13f,muted));setOnClickListener{action()}
            layoutParams=LinearLayout.LayoutParams(0,dp(150),1f).apply{setMargins(dp(6),dp(14),dp(6),dp(14))}
        }
        cards.addView(card("歌曲点唱","汉字 / 全拼 / 首字母搜索"){showLibrary()})
        cards.addView(card("我的收藏","把常唱的歌放在一起"){showLibrary(true)})
        cards.addView(card("已点歌曲","查看并管理本次点歌队列"){showLibrary(queued=true)})
        home.addView(cards)
        home.addView(label("首次使用：右上角登录 115，再到「曲库管理」选择文件夹。",13f,muted))
        library=col().apply{visibility=View.GONE};root.addView(library,LinearLayout.LayoutParams(-1,0,1f))
        val filters=row();filters.addView(button("全部歌曲"){showLibrary()});filters.addView(button("我的收藏"){showLibrary(true)})
        search=field("搜索歌名、歌手、全拼或首字母，如 zjl")
        filters.addView(search,LinearLayout.LayoutParams(0,dp(44),1f));search.doAfterTextChanged{refresh()};library.addView(filters)
        hint=label("",11f,muted);library.addView(hint)
        val frame=FrameLayout(this);list=ListView(this).apply{divider=null;dividerHeight=dp(5)}
        frame.addView(list,FrameLayout.LayoutParams(-1,-1))
        empty=label("你的曲库，从这里开始\n\n① 扫码登录 115\n② 选择 KTV 曲库文件夹\n③ 扫描并保存到本机\n\n已有曲库清单？点「目录备份」直接导入。",17f,muted).apply{gravity=Gravity.CENTER}
        frame.addView(empty,FrameLayout.LayoutParams(-1,-1));library.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(label("窝牛家庭KTV 0.5 · 本地曲库 · 115 目录备份与恢复",11f,muted))
        list.setOnItemClickListener{_,_,position,_->rows.getOrNull(position)?.let{song->
            AlertDialog.Builder(this).setTitle(song.title).setMessage("歌手：${song.artist.ifBlank{"未识别"}}\n文件：${song.name}\n路径：${song.path}\n\n加入队列后可在「已点歌曲」管理；播放功能开发中。")
                .setPositiveButton(if(queueMode)"移出已点" else "点歌"){_,_->if(queueMode)db.removeQueue(song.queueId) else db.enqueue(song.id);refresh();status.text=if(queueMode)"已移出队列" else "已加入点歌队列"}
                .setNeutralButton(if(song.favorite)"取消收藏" else "收藏"){_,_->db.favorite(song.id,!song.favorite);refresh()}.setNegativeButton("关闭",null).show()
        }}
    }
    private fun refresh(){
        searching?.cancel();val query=search.text.toString();val onlyFavorites=favorites;val queued=queueMode
        searching=lifecycleScope.launch{
            delay(120)
            val result=withContext(Dispatchers.IO){Triple(if(queued)db.queue().filter{query.isBlank()||it.title.contains(query,true)||it.artist.contains(query,true)} else db.search(query,onlyFavorites),db.count(),db.setting("root_path"))}
            rows=result.first;summary.text="${result.second} 首歌曲";location.text=result.third.ifBlank{"尚未选择 115 曲库目录"}
            loginButton.text=if(drive.loggedIn)"115 已登录" else "115 登录"
            hint.text=if(queued)"已点队列 · ${rows.size} 首 · 点击歌曲可移除" else "本地搜索 · 显示 ${rows.size} 首（最多 200 首，输入关键词缩小范围）"
            list.adapter=object:BaseAdapter(){
                override fun getCount()=rows.size;override fun getItem(i:Int)=rows[i];override fun getItemId(i:Int)=i.toLong()
                override fun getView(i:Int,old:View?,parent:ViewGroup?):View{
                    val song=rows[i];return row().apply{background=panel();setPadding(dp(6),dp(5),dp(6),dp(5))
                        addView(label((if(song.favorite)"★ "else "")+song.title,16f),LinearLayout.LayoutParams(0,-2,0.45f))
                        addView(label(song.artist.ifBlank{"未识别歌手"},13f,muted),LinearLayout.LayoutParams(0,-2,0.25f))
                        addView(label(song.name,11f,muted).apply{maxLines=2},LinearLayout.LayoutParams(0,-2,0.30f))
                    }
                }
            };empty.visibility=if(rows.isEmpty())View.VISIBLE else View.GONE
            if(result.second>0&&rows.isEmpty())empty.text="没有找到匹配歌曲\n试试汉字、歌手或其他关键词"
        }
    }
    private fun busy()=scanning?.isActive==true||transferring?.isActive==true
    private fun runTask(message:String,action:suspend()->String){
        if(busy()){status.text="请先暂停扫描或等待当前操作完成";return}
        status.text=message
        transferring=lifecycleScope.launch{try{status.text=action();refresh()}catch(e:TimeoutCancellationException){status.text="操作超时，未完成的目录导入已回滚，可重试"}catch(e:CancellationException){throw e}catch(e:Exception){status.text=safeError(e)}finally{transferring=null}}
    }
    private fun accountDialog(){
        if(busy()){status.text="请先暂停扫描或等待目录传输完成";return}
        AlertDialog.Builder(this).setTitle("115 扫码登录").setMessage("凭据仅加密保存在本机。会话登录使用 TV 类型，可能使已有 115 TV 登录退出。切换不同账号会清除本机旧曲库。")
            .setPositiveButton(if(drive.loggedIn)"重新扫码 / 切换账号" else "扫码登录"){_,_->loginQr()}
            .apply{if(drive.loggedIn)setNeutralButton("退出登录"){_,_->drive.logout();refresh();status.text="已退出 115；本地索引保留，重新登录原账号后可使用"}}
            .setNegativeButton("取消",null).show()
    }
    private fun loginQr(){
        val box=col().apply{gravity=Gravity.CENTER;setPadding(dp(14),dp(6),dp(14),dp(6))}
        val image=ImageView(this);box.addView(image,LinearLayout.LayoutParams(dp(220),dp(220)))
        val message=label("正在获取二维码…");box.addView(message)
        val dialog=AlertDialog.Builder(this).setTitle("用手机 115 扫码，并确认登录").setView(box).setNegativeButton("关闭",null).create()
          var job:Job?=null;var activeQr:LoginQr?=null
          val confirm=button("已在手机确认，完成登录"){
              val qr=activeQr
              if(qr!=null){job?.cancel();job=lifecycleScope.launch{try{
                  message.text="正在完成登录…"
                  drive.finishLogin(qr);withContext(Dispatchers.IO){db.bindAccount(drive.accountId)}
                  dialog.dismiss();refresh();status.text="115 登录成功，选择曲库扫描，或从目录备份恢复"
              }catch(e:CancellationException){throw e}catch(e:Exception){message.text=safeError(e)+"；可重试或重新扫码"}}}
          };box.addView(confirm)
          dialog.setOnDismissListener{job?.cancel()};dialog.show()
        job=lifecycleScope.launch{try{
              val qr=drive.startLogin(db.setting("appid"));activeQr=qr
            val bitmap=withContext(Dispatchers.Default){
                val matrix=QRCodeWriter().encode(qr.content,BarcodeFormat.QR_CODE,600,600,mapOf(EncodeHintType.MARGIN to 2))
                Bitmap.createBitmap(600,600,Bitmap.Config.RGB_565).apply{val pixels=IntArray(600*600){i->if(matrix[i%600,i/600])Color.BLACK else Color.WHITE};setPixels(pixels,0,600,0,0,600,600)}
            };image.setImageBitmap(bitmap);message.text="等待扫码 · 二维码由 115 提供"
            var failures=0
            repeat(110){
                delay(1800)
                  val result=try{drive.poll(qr).also{failures=0}}catch(e:CancellationException){throw e}catch(e:Exception){failures++;message.text="状态查询暂未返回，正在重试；手机已确认可点下方完成登录";return@repeat}
                when(result){
                    1->message.text="已扫码，请在手机上确认"
                    2->{drive.finishLogin(qr);withContext(Dispatchers.IO){db.bindAccount(drive.accountId)};dialog.dismiss();refresh();status.text="115 登录成功，选择曲库扫描，或从目录备份恢复";return@launch}
                    -1->error("二维码已过期，请重新打开登录")
                    -2->error("已取消登录")
                }
            };message.text="二维码已过期，请关闭后重新扫码"
        }catch(e:CancellationException){throw e}catch(e:Exception){message.text=safeError(e)}}
    }
    private fun browse(purpose:String){
        if(!drive.loggedIn){accountDialog();return};if(busy()){status.text="请先暂停扫描或等待当前操作完成";return}
        val box=col().apply{setPadding(dp(12),dp(5),dp(12),dp(5))};val path=label("115 根目录",14f,gold);box.addView(path)
        val files=ListView(this).apply{
            id=View.generateViewId();isFocusable=true;isFocusableInTouchMode=true
            descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setSelector(android.graphics.drawable.GradientDrawable().apply{setColor(0xff29463c.toInt());setStroke(dp(3),0xff9de5cd.toInt());cornerRadius=dp(8).toFloat()})
            isDrawSelectorOnTop=false
        };box.addView(files,LinearLayout.LayoutParams(-1,dp(240)));val pager=row();box.addView(pager)
        var task=ScanTask("0","/",0);val parents=mutableListOf<ScanTask>();var entries=emptyList<DriveEntry>();var job:Job?=null;var loading=false
        val dialog=AlertDialog.Builder(this).setTitle(when(purpose){"import"->"选择已上传的曲库 JSON";"upload"->"选择目录清单保存位置";else->"选择 KTV 曲库文件夹"}).setView(box)
            .setNegativeButton("关闭",null).apply{if(purpose!="import")setPositiveButton(if(purpose=="upload")"上传到这里" else "扫描当前目录",null)}.create()
        fun load(){
            job?.cancel();loading=true;path.text=task.path+" · 正在加载…";files.adapter=null;pager.removeAllViews()
            job=lifecycleScope.launch{try{
                val page=drive.list(task.cid,task.offset);entries=page.entries
                path.text=task.path+" · ${task.offset+entries.size}/${page.total} 项"
                files.adapter=ArrayAdapter(this@PocketActivity,android.R.layout.simple_list_item_1,entries.map{(if(it.folder)"▸ " else "· ")+it.name})
                if(entries.isNotEmpty())files.post{files.requestFocus();files.setSelection(0)}
                if(parents.isNotEmpty())pager.addView(button("上级"){task=parents.removeAt(parents.lastIndex);load()})
                if(task.offset>0)pager.addView(button("上一页"){task=task.copy(offset=maxOf(0,task.offset-500));load()})
                if(task.offset+entries.size<page.total)pager.addView(button("下一页"){task=task.copy(offset=task.offset+entries.size);load()})
            }catch(e:CancellationException){throw e}catch(e:Exception){path.text=safeError(e)}finally{loading=false}}
        }
        files.setOnItemClickListener{_,_,i,_->if(!loading)entries.getOrNull(i)?.let{entry->
            if(entry.folder){parents.add(task);task=ScanTask(entry.id,task.path.trimEnd('/')+"/"+entry.name,0);load()}
            else if(purpose=="import"&&entry.name.endsWith(".json",true)){
                dialog.dismiss();AlertDialog.Builder(this).setTitle("导入这份目录？").setMessage(entry.name+"\n将合并到本地曲库，只接受同一个 115 账号的目录，不导入登录信息。")
                    .setPositiveButton("导入"){_,_->runTask("正在从 115 下载并导入目录…"){
                        val count=CatalogRepository(db,drive,cacheDir).restore(entry.pickCode){message->runOnUiThread{status.text=message}}
                        "已恢复 $count 首歌曲及备份内的智能偏好，无需重新扫描全库"
                    }}.setNegativeButton("取消",null).show()
            }
        }}
        dialog.setOnDismissListener{job?.cancel()};dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).nextFocusUpId=files.id
        if(purpose!="import")dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(!loading){
            val selected=task;dialog.dismiss()
            if(purpose=="upload")runTask("正在导出并上传曲库目录…"){
                val file=catalogFile();CatalogRepository(db,drive,cacheDir).organize();withContext(Dispatchers.IO){CatalogCodec.export(db,file)}
                drive.uploadCatalog(file,selected.cid);"目录已上传到 ${selected.path}/${file.name}，新设备登录后可直接导入"
            }else AlertDialog.Builder(this).setTitle("扫描 ${selected.path}？").setMessage("只读取文件列表，不下载视频。可暂停并续扫。建议先测试一个小目录。")
                .setPositiveButton("开始扫描"){_,_->lifecycleScope.launch{withContext(Dispatchers.IO){db.startScan(selected.cid,selected.path)};runScan()}}.setNegativeButton("取消",null).show()
        }};load()
    }
    private fun toggleScan(){
        if(scanning?.isActive==true){scanning?.cancel();status.text="正在暂停，保留扫描断点…";return}
        if(!drive.loggedIn){accountDialog();return};if(transferring?.isActive==true)return
        lifecycleScope.launch{if(withContext(Dispatchers.IO){db.nextFolder()}!=null)runScan()else browse("scan")}
    }
    private fun runScan(){
        if(scanning?.isActive==true)return;scanButton.text="暂停扫描"
        scanning=lifecycleScope.launch{try{
            while(true){ensureActive();val task=withContext(Dispatchers.IO){db.nextFolder()}?:break
                status.text="扫描 ${task.path} · 已读取 ${task.offset} 项"
                val page=drive.list(task.cid,task.offset);withContext(Dispatchers.IO){db.commitPage(task,page)};refresh()
            };db.setting("scan_done","1",true);status.text="扫描完成。可搜索曲库，并通过「目录备份」上传清单到 115。"
        }catch(e:CancellationException){status.text="扫描已暂停，断点已保存";throw e}catch(e:Exception){status.text="扫描暂停：${safeError(e)}。可稍后续扫。"}
        finally{scanning=null;scanButton.text="续扫";refresh()}}
    }
    private fun catalogFile()=File(cacheDir,"pocket-ktv-catalog-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(java.util.Date())}-${java.util.UUID.randomUUID().toString().take(8)}.json")
    private fun catalogMenu(){
        if(!drive.loggedIn){accountDialog();return};if(busy()){status.text="请先暂停扫描或等待当前操作完成";return}
        AlertDialog.Builder(this).setTitle("曲库目录 · 跨设备复用").setItems(arrayOf("备份曲库与智能偏好到 115","从 115 导入目录清单","导出 JSON 到本机","从本机导入 JSON","选择其他备份文件夹")){_,which->when(which){
            0->runTask("正在备份整理后的曲库…"){val path=CatalogRepository(db,drive,cacheDir).backup{message->runOnUiThread{status.text=message}};"备份已上传并校验：$path"};1->browse("import");4->browse("upload")
            2->runTask("正在生成目录…"){
                val file=catalogFile();CatalogRepository(db,drive,cacheDir).organize();val count=withContext(Dispatchers.IO){CatalogCodec.export(db,file)};exportFile=file
                exportLauncher.launch(file.name);"已生成 $count 首目录，请选择保存位置"
            }
            3->importLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream"))
        }}.setNegativeButton("取消",null).show()
    }
    private fun settings(){
        AlertDialog.Builder(this).setTitle("设置").setItems(arrayOf("文件命名规则","官方 115 AppID（可选）","开源说明")){_,which->when(which){
            0->AlertDialog.Builder(this).setTitle("选择文件名规则").setSingleChoiceItems(arrayOf("歌手 - 歌名","歌名 - 歌手"),if(db.setting("name_rule")=="title_artist")1 else 0){d,i->db.setting("name_rule",if(i==0)"artist_title"else"title_artist",true);d.dismiss();status.text="规则已保存，重新扫描后应用"}.show()
            1->{val input=field("没有自己的 AppID 可留空").apply{setText(db.setting("appid"))};AlertDialog.Builder(this).setTitle("官方 PKCE 授权（可选）").setMessage("默认使用会话扫码，不借用其他应用密钥。有自己的 115 AppID 时可填写，保存后重新登录。").setView(input).setPositiveButton("保存"){_,_->db.setting("appid",input.text.toString().trim(),true)}.setNegativeButton("取消",null).show()}
            2->{val body=StringBuilder("基于 Home KTV 安卓工程与文件名规则改造。第一版实现 115 扫码、本地曲库扫描和目录备份。播放模块保留在源码，暂不开放。\n\n")
                assets.list("licenses").orEmpty().forEach{name->body.append("\n—— $name ——\n").append(assets.open("licenses/$name").bufferedReader().readText())}
                AlertDialog.Builder(this).setTitle("来源与许可").setView(ScrollView(this).apply{addView(label(body.toString(),12f))}).setPositiveButton("关闭",null).show()}
        }}.show()
    }
    private fun safeError(e:Exception)=when(e){
        is Drive115.DriveError->e.message.orEmpty()
        is java.net.SocketTimeoutException->"网络超时，请重试"
        is java.io.IOException->"网络或文件读取失败，请重试"
        else->e.message?.takeIf{!it.contains("http",true)&&!it.contains("cookie",true)&&!it.contains("token",true)}?.take(140)?:"操作未完成，请重试"
    }
    override fun onDestroy(){scanning?.cancel();transferring?.cancel();super.onDestroy()}
}
