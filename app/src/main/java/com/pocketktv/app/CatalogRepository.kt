package com.pocketktv.app
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
/** Application service shared by management UI and future source adapters. No listening port needed. */
class CatalogRepository(private val db:LibraryDb,private val drive:Drive115,private val cacheDir:File){
 suspend fun organize(progress:(Int,Int)->Unit={_,_->})=withContext(Dispatchers.IO){db.reparseMetadata(progress)}
 suspend fun backup(progress:(String)->Unit={}):String {
  val expectedAccount=drive.accountId;require(db.setting("account")==expectedAccount&&expectedAccount.isNotBlank()){"请重新登录当前曲库账号"}
  require(db.setting("scan_done")=="1"){"请等待扫描完成再备份"}
  progress("正在整理曲库…");organize()
  val name="Woniu-catalog-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(java.util.Date())}.json"
  val file=File(cacheDir,name)
  val learningRevision=db.setting("smart_revision","0")
  val count=withContext(Dispatchers.IO){CatalogCodec.export(db,file,expectedAccount)}
  progress("正在上传 $count 首目录…")
  require(db.setting("account")==expectedAccount){"账号已切换，备份已停止"}
  val folder=db.setting("catalog_backup_folder").takeIf{it.matches(Regex("[0-9]{1,30}"))}?:drive.catalogFolder()
  db.setting("catalog_backup_pending",name,true)
  drive.uploadCatalog(file,folder)
  progress("上传完成，正在核验云端目录…")
  var offset=0;var entry:DriveEntry?=null;var pages=0;val deadline=android.os.SystemClock.elapsedRealtime()+60_000
  do{require(++pages<=10&&android.os.SystemClock.elapsedRealtime()<deadline){"文件可能已上传，云端核验达到预算，请在115检查 $name"};val page=drive.list(folder,offset);entry=page.entries.firstOrNull{!it.folder&&it.name==name};offset+=page.entries.size
   if(entry!=null||offset>=page.total||page.entries.isEmpty())break
  }while(true)
  val saved=entry?:error("上传已响应，但云端列表暂未出现文件，请稍后检查 窝牛家庭KTV/$name")
  val digest=withContext(Dispatchers.IO){file.inputStream().use{input->val md=java.security.MessageDigest.getInstance("SHA-256");val buf=ByteArray(65536);while(true){val n=input.read(buf);if(n<0)break;md.update(buf,0,n)};md.digest()}}
  drive.downloadCatalog(saved.pickCode){input->withContext(Dispatchers.IO){val md=java.security.MessageDigest.getInstance("SHA-256");val buf=ByteArray(65536);var bytes=0L;while(true){val n=input.read(buf);if(n<0)break;bytes+=n;require(bytes<=CatalogCodec.MAX_BYTES);md.update(buf,0,n)};require(md.digest().contentEquals(digest)){"云端备份校验不一致，请保留本机目录并重新备份"}}}
  require(db.setting("account")==expectedAccount){"备份已提交原账号，但当前账号已切换，请回原账号检查"}
  db.setting("catalog_backup_path","/窝牛家庭KTV/$name",true);db.setting("catalog_backup_pick",saved.pickCode,true)
  db.setting("catalog_backup_count",count.toString(),true)
  db.setting("catalog_backup_folder",folder,true)
  db.setting("catalog_backup_pending","",true)
  db.setting("smart_backed_revision",learningRevision,true)
  file.delete() // The verified cloud receipt is durable; do not accumulate full catalogs in app cache.
  return "/窝牛家庭KTV/$name"
 }
 suspend fun restore(pickCode:String,progress:(String)->Unit={}):Int=withContext(Dispatchers.IO){
  // Finish bounded network I/O before opening the atomic database transaction.
  val file=File.createTempFile("catalog-restore-",".json",cacheDir)
  try {
   progress("正在从 115 下载目录…")
   drive.downloadCatalog(pickCode){input->file.outputStream().use{output->
    val buffer=ByteArray(65536);var bytes=0L
    while(true){kotlinx.coroutines.currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=CatalogCodec.MAX_BYTES){"目录文件超过 128MB 限制"};output.write(buffer,0,n)}
   }}
   progress("下载完成，正在导入目录…")
   kotlinx.coroutines.withTimeout(10*60*1000L){file.inputStream().use{CatalogCodec.import(db,it,drive.accountId){count->progress("正在导入目录：已处理 $count 首，完成后统一显示")}}}
  } finally { file.delete() }
 }
}
