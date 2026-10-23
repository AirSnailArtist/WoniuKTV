package com.pocketktv.app
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
class CatalogAuditTest {
 @Test fun audit()=runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("audit")=="true")
  val ctx=ApplicationProvider.getApplicationContext<Context>();val db=LibraryDb(ctx);val drive=Drive115(SecretStore(ctx))
  val dirs=db.readableDatabase.rawQuery("SELECT cid,path FROM folders ORDER BY rowid",null).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
  val report=File(ctx.filesDir,"catalog-audit.txt");report.writeText("")
  var files=0;var skipped=0
  try{for((i,dir) in dirs.withIndex()) {var offset=0
   do{val page=drive.list(dir.first,offset)
    for(e in page.entries.filter{!it.folder}){files++;if(!SongNames.supported(e.name)||e.pickCode.isBlank()){skipped++;report.appendText("${if(!SongNames.supported(e.name)) "unsupported" else "missing_pickcode"}\t${dir.second}/${e.name}\n")}}
    offset+=page.entries.size;require(page.entries.isNotEmpty()||offset>=page.total)
   }while(offset<page.total)
   report.appendText("PROGRESS ${i+1}/${dirs.size} files=$files skipped=$skipped\n")
  };report.appendText("COMPLETE files=$files skipped=$skipped\n")}finally{db.close()}
 }
}
