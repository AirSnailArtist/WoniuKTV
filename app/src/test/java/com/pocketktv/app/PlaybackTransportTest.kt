package com.pocketktv.app
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Test
import org.junit.Assert.*
import java.net.HttpURLConnection
import java.net.URL
class PlaybackTransportTest {
 @Test fun rangeAndHeadersAreForwardedWithoutForeignCookies(){
  MockWebServer().use{upstream->upstream.start();PlaybackTransport().use{proxy->
   upstream.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 2-4/6").setHeader("Content-Type","video/mpeg").setBody("cde"))
   val uri=proxy.open(upstream.url("/media").toString(),mapOf("User-Agent" to "WoniuTest","Cookie" to "private","X-Test" to "test"))
   val response=URL(uri).openConnection() as HttpURLConnection;response.setRequestProperty("Range","bytes=2-4")
   assertEquals(206,response.responseCode);assertEquals("bytes 2-4/6",response.getHeaderField("Content-Range"));assertEquals("cde",response.inputStream.bufferedReader().readText())
   val request=upstream.takeRequest();assertEquals("bytes=2-4",request.getHeader("Range"));assertEquals("WoniuTest",request.getHeader("User-Agent"));assertNull(request.getHeader("Cookie"));response.disconnect()
   val wrong=URL(uri+"wrong").openConnection() as HttpURLConnection;assertEquals(404,wrong.responseCode);wrong.disconnect()
  }}
 }
}
