package com.pocketktv.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/** Official login pages have no native Javascript bridge. Credentials stay encrypted on device. */
class CloudDriveActivity:ComponentActivity(){
    private lateinit var db:LibraryDb
    private lateinit var root:LinearLayout
    private lateinit var status:TextView
    private lateinit var content:LinearLayout
    private var drive:DomesticDrive?=null
    private var web:WebView?=null
    @Volatile private var pendingAliHeaders:Map<String,String> = emptyMap()
    private var job:Job?=null
    private var folder="0";private var path="/";private var offset=0
    private val parents=mutableListOf<Triple<String,String,Int>>()
    override fun onCreate(state:Bundle?){super.onCreate(state);db=LibraryDb(this);root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12);setBackgroundColor(Color.rgb(239,243,248))};setContentView(root)
        val bar=LinearLayout(this);bar.addView(button("‹ 返回"){finish()});for((id,label)in DomesticDrive.labels)bar.addView(button(label){select(id)})
        root.addView(HorizontalScrollView(this).apply{addView(bar)});status=TextView(this).apply{setTextColor(Color.DKGRAY);textSize=15f;text="选择网盘。先扫码登录，再选择存放歌曲的文件夹；与115曲库共存。首次接入，请先验证所用账号的目录和播放权限。"};root.addView(status)
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        intent.getStringExtra("provider")?.takeIf{it in DomesticDrive.labels}?.let{select(it)}
    }
    private fun button(text:String,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;setTextColor(Color.rgb(33,50,72));backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(225,232,240));setOnClickListener{action()}}
    private fun reset(){job?.cancel();web?.stopLoading();web?.destroy();web=null;content.removeAllViews()}
    private fun task(action:suspend()->Unit){if(job?.isActive==true)return;job=lifecycleScope.launch{try{action()}catch(e:CancellationException){throw e}catch(e:Exception){status.text=e.message?.takeUnless{it.contains("http",true)||it.contains("cookie",true)||it.contains("token",true)}?:"网盘操作未完成，请重新登录后重试"}}}
    private fun select(provider:String){reset();drive=DomesticDrive(provider,SecretStore(this,"cloud_$provider"),cursorDb=db);parents.clear();folder="0";path="/";offset=0
        if(drive!!.loggedIn)browse() else login()
    }
    @SuppressLint("SetJavaScriptEnabled") private fun login(){
        val d=drive?:return;reset();pendingAliHeaders=emptyMap();status.text="请用${d.label}App扫描官方页面二维码并确认，再点击“我已登录”。只在本机保存登录，不上传备份。"
        val row=LinearLayout(this);row.addView(button("我已登录，读取目录"){
            fun connect(refresh:String=""){val jar=CookieManager.getInstance();val cookie=jar.getCookie(d.home).orEmpty()
                task{status.text="正在验证${d.label}账号和目录权限…";if(d.provider=="aliyun")d.loginAli(pendingAliHeaders,refresh)else d.login(cookie);if(db.setting("account").isBlank())db.setting("account","cloud:"+d.provider+":"+d.account,true);job=null;browse()}
            }
            if(d.provider=="aliyun"){
                val origin=web?.url?.let{android.net.Uri.parse(it)}
                if(origin?.scheme!="https"||origin.host !in setOf("www.alipan.com","www.aliyundrive.com","alipan.com","aliyundrive.com")){status.text="请先在官方阿里云盘页面完成登录"}
                else web?.evaluateJavascript("(()=>{for(const key of ['token','aliyunpan_token','alipan_token']){try{const v=JSON.parse(localStorage.getItem(key)||'null');if(v&&typeof v.refresh_token==='string')return v.refresh_token}catch{}}return ''})()") {value->val refresh=runCatching{org.json.JSONTokener(value).nextValue() as? String}.getOrNull().orEmpty();connect(refresh)}
            }else connect()
        });row.addView(button("刷新登录页"){web?.reload()});content.addView(row)
        web=WebView(this).apply{
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.userAgentString=DomesticDrive.UA;settings.useWideViewPort=true;settings.loadWithOverviewMode=true;settings.allowFileAccess=false;settings.allowContentAccess=false;settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean= request.url.scheme!="https"
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):android.webkit.WebResourceResponse? {
                    if(d.provider=="aliyun"&&request.url.scheme=="https"&&request.url.host in setOf("api.alipan.com","api.aliyundrive.com")){
                        val source=request.requestHeaders.entries.associate{it.key.lowercase() to it.value};val authorization=source["authorization"].orEmpty()
                        if(authorization.startsWith("Bearer ")&&!source["x-signature"].isNullOrBlank())pendingAliHeaders=mapOf("Authorization" to authorization,"X-Device-Id" to source["x-device-id"].orEmpty(),"X-Signature" to source.getValue("x-signature"),"X-Canary" to source["x-canary"].orEmpty())
                    };return null
                }
            }
            CookieManager.getInstance().setAcceptCookie(true);loadUrl(d.home)
        };content.addView(web,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun browse(){
        val d=drive?:return;web?.destroy();web=null;content.removeAllViews()
        val controls=LinearLayout(this);controls.addView(button("重新扫码登录"){login()})
        controls.addView(button("导入当前文件夹"){
            AlertDialog.Builder(this).setTitle("导入${d.label}歌曲？").setMessage("$path\n仅读取文件名并保存到本机，不下载视频。每次最多读取1000项，未完成可续扫；保留原115曲库。").setPositiveButton("开始导入"){_,_->task{withContext(Dispatchers.IO){DomesticScan(db,d).start(folder,path)};scan()}}.setNegativeButton("取消",null).show()
        });controls.addView(button("继续导入"){task{scan()}});controls.addView(button("暂停"){job?.cancel();status.text="已暂停，已提交页面的断点保留"})
        content.addView(controls)
        val list=ListView(this);content.addView(list,LinearLayout.LayoutParams(-1,0,1f));val paging=LinearLayout(this);content.addView(paging)
        task{
            status.text="正在读取${d.label} $path …";val page=d.list(folder,offset)
            status.text="${d.label} $path · ${offset+page.entries.size}/${page.total}项。选歌曲目录后导入；歌曲会出现在主界面的歌名／歌星点歌。"
            list.adapter=object:ArrayAdapter<String>(this@CloudDriveActivity,android.R.layout.simple_list_item_1,page.entries.map{(if(it.folder)"▸ " else "♪ ")+it.name}){
                override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View=super.getView(position,convertView,parent).apply{setBackgroundColor(Color.WHITE);(this as? TextView)?.setTextColor(Color.rgb(33,50,72))}
            }
            list.setOnItemClickListener{_,_,i,_->val entry=page.entries[i];if(entry.folder){parents.add(Triple(folder,path,offset));folder=entry.id;path=path.trimEnd('/')+"/"+entry.name;offset=0;job=null;browse()}else if(SongNames.supported(entry.name)){task{withContext(Dispatchers.IO){db.writableDatabase.beginTransactionNonExclusive();try{db.rememberDomestic(d.provider,d.account,entry);db.writableDatabase.setTransactionSuccessful()}finally{db.writableDatabase.endTransaction()}};status.text="已加入曲库：${entry.name}，返回点歌页面搜索即可"}}}
            if(parents.isNotEmpty())paging.addView(button("上级"){val prev=parents.removeAt(parents.lastIndex);folder=prev.first;path=prev.second;offset=prev.third;job=null;browse()})
            if(offset>0)paging.addView(button("上一页"){offset=if(d.provider=="aliyun")0 else maxOf(0,offset-100);job=null;browse()})
            if(offset+page.entries.size<page.total)paging.addView(button("下一页"){offset+=100;job=null;browse()})
        }
    }
    private suspend fun scan(){val d=drive?:return;val result=withContext(Dispatchers.IO){val service=DomesticScan(db,d);val n=service.slice{message->runOnUiThread{status.text=message}};n to (service.next()==null)}
        status.text="${d.label}本次读取${result.first}项，"+if(result.second)"导入完成，可返回点歌。" else "已保存断点，点击继续导入。"
    }
    override fun onDestroy(){job?.cancel();web?.destroy();db.close();super.onDestroy()}
}
