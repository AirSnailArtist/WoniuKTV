package com.pocketktv.app

import android.content.ContentValues
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit selected-folder job, persistent page checkpoint. Each foreground slice <=10 pages/1000
 * entries/60s; resumes only at user request. Account + done index bounds empty-queue checks. */
class DomesticScan(private val db:LibraryDb,private val drive:DomesticDrive){
    private val sql get()=db.writableDatabase
    init {
        sql.execSQL("CREATE TABLE IF NOT EXISTS domestic_scan(provider TEXT NOT NULL,account TEXT NOT NULL,folder TEXT NOT NULL,path TEXT NOT NULL,offset INTEGER NOT NULL DEFAULT 0,done INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(provider,account,folder))")
        sql.execSQL("CREATE INDEX IF NOT EXISTS domestic_scan_pending ON domestic_scan(provider,account,done)")
    }
    fun start(folder:String,path:String){
        require(drive.loggedIn&&folder.matches(Regex("[a-zA-Z0-9_-]{1,128}"))&&path.length<=4096)
        sql.beginTransactionNonExclusive();try{
            sql.delete("domestic_scan","provider=? AND account=?",arrayOf(drive.provider,drive.account))
            add(folder,path);sql.setTransactionSuccessful()
        }finally{sql.endTransaction()}
    }
    private fun add(folder:String,path:String){sql.insertWithOnConflict("domestic_scan",null,ContentValues().apply{put("provider",drive.provider);put("account",drive.account);put("folder",folder);put("path",path)},android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)}
    fun next():ScanTask?=sql.rawQuery("SELECT folder,path,offset FROM domestic_scan WHERE provider=? AND account=? AND done=0 LIMIT 1",arrayOf(drive.provider,drive.account)).use{if(it.moveToFirst())ScanTask(it.getString(0),it.getString(1),it.getInt(2)) else null}
    suspend fun slice(progress:(String)->Unit):Int {
        val owner=drive.account;require(owner.isNotBlank());var processed=0;val deadline=android.os.SystemClock.elapsedRealtime()+60_000
        repeat(10){
            currentCoroutineContext().ensureActive();if(android.os.SystemClock.elapsedRealtime()>=deadline)return processed
            val task=next()?:return processed;progress("正在读取 ${drive.label} ${task.path} · ${task.offset}项")
            val page=drive.list(task.cid,task.offset);require(drive.account==owner){"网盘账号已切换，已停止导入"}
            currentCoroutineContext().ensureActive()
            sql.beginTransactionNonExclusive();try{
                for(entry in page.entries){currentCoroutineContext().ensureActive();if(entry.folder){val path=task.path.trimEnd('/')+"/"+entry.name;require(path.length<=4096);add(entry.id,path)}else if(SongNames.supported(entry.name)){db.rememberDomestic(drive.provider,owner,entry)}}
                val offset=task.offset+page.entries.size
                sql.update("domestic_scan",ContentValues().apply{put("offset",if(offset>=page.total)offset else task.offset+100);put("done",if(offset>=page.total)1 else 0)},"provider=? AND account=? AND folder=?",arrayOf(drive.provider,owner,task.cid))
                sql.setTransactionSuccessful()
            }finally{sql.endTransaction()}
            processed+=page.entries.size
        }
        return processed
    }
}
