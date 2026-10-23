package com.pocketktv.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Explicitly opt-in, read-only live-account smoke test. Never emits URLs or credentials. */
@RunWith(AndroidJUnit4::class)
class OnlineLibrarySmokeTest {
    @Test fun cloudLibraryPlaybackSources()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("online_smoke")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=LibraryDb(context);val drive=Drive115(SecretStore(context))
        val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).followRedirects(false).build()
        try{
            val songs=db.search("").take(23);assertEquals("Expected the selected 23-song test library",23,songs.size)
            for((index,song) in songs.withIndex()){
                val source=drive.source(song.pickCode)
                val request=Request.Builder().url(source.url).header("Range","bytes=0-1023").apply{source.headers.forEach{(k,v)->header(k,v)}}.build()
                client.newCall(request).execute().use{response->
                    assertTrue("Song ${index+1}: HTTP ${response.code}",response.isSuccessful)
                    assertTrue("Song ${index+1}: empty media",response.body!!.byteStream().read()!=-1)
                }
                println("115 media probe: ${index+1}/${songs.size} OK")
            }
        }finally{db.close()}
    }
}
