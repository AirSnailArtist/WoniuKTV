package com.pocketktv.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolated databases only: no account, cloud, real library or queue access. */
class CatalogImportConcurrencyTest {
    @Test fun readerSeesCommittedLibraryWhileImportIsRunning() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "catalog-concurrency-${System.nanoTime()}.db"
        val writer = LibraryDb(context, name)
        val reader = LibraryDb(context, name)
        val executor = Executors.newSingleThreadExecutor()
        try {
            writer.bindAccount("test")
            assertEquals(0, reader.count())
            val songs = (1..501).joinToString(",") {
                """{"id":"$it","name":"song.mp4","title":"Song","artist":"Singer","pick_code":"p$it","search":"song"}"""
            }
            val json = """{"format":"pocket-ktv-catalog","version":2,"account_id":"test","songs":[$songs]}"""
            var checked = false
            val count = CatalogCodec.import(writer, json.byteInputStream(), "test") { processed ->
                if (processed == 500) {
                    // Separate helper and thread reproduce the chart reader during an import.
                    assertEquals(0, executor.submit<Int> { reader.count() }.get(2, TimeUnit.SECONDS))
                    checked = true
                }
            }
            assertTrue(checked)
            assertEquals(501, count)
            assertEquals(501, reader.count())
        } finally {
            executor.shutdownNow()
            reader.close()
            writer.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun invalidCatalogRollsBackAllRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "catalog-rollback-${System.nanoTime()}.db"
        val db = LibraryDb(context, name)
        try {
            db.bindAccount("test")
            val json = """{"format":"pocket-ktv-catalog","version":2,"account_id":"test","songs":[{"id":"1","name":"song.mp4","title":"Song","artist":"Singer","pick_code":"p1","search":"song"},{"id":"invalid"}]}"""
            try {
                CatalogCodec.import(db, json.byteInputStream(), "test")
                fail("Invalid catalog must fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, db.count())
            assertEquals("test", db.setting("account"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
