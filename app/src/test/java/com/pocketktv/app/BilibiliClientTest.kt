package com.pocketktv.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BilibiliClientTest {
    private fun response(data:String,code:Int=0)=MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":$code,\"data\":$data}")
    private fun client(server:MockWebServer,load:()->String?={null},save:(String?)->Unit={})=BilibiliClient(load,save,apiBase=server.url("/").toString(),passportBase=server.url("/").toString())
    @Test fun wbiCanonicalEncodingAndUntrustedMedia(){
        val signed=BilibiliClient.sign(mapOf("keyword" to "A & B!测试"),"7cd084941338484aae1ad9425b84077c4932caff0ff746eab6f01bf08b70ac45",1702204169)
        assertEquals("32f5bb324721de7f332bdbb1ca48ab87",signed["w_rid"])
        assertTrue(BilibiliClient.allowedMedia("https://upos-sz-mirror.bilivideo.com/v.mp4?x=test"))
        assertFalse(BilibiliClient.allowedMedia("https://bilivideo.com.attacker.example/v.mp4"))
        assertFalse(BilibiliClient.allowedMedia("http://127.0.0.1/private"))
        assertEquals("A & B",BilibiliClient.cleanTitle("<em class=\"keyword\">A</em> &amp; B"))
    }
    @Test fun searchSignsAndPagesWithoutFetchingEveryVideo(){MockWebServer().use{server->server.start()
        server.enqueue(response("{\"isLogin\":false,\"wbi_img\":{\"img_url\":\"https://i/7cd084941338484aae1ad9425b84077c.png\",\"sub_url\":\"https://i/4932caff0ff746eab6f01bf08b70ac45.png\"}}",-101))
        server.enqueue(response("{\"numPages\":3,\"result\":[{\"bvid\":\"BV1GJ411x7h7\",\"title\":\"<em>MV</em>\",\"author\":\"官方音乐\"}]}"))
        val page=client(server).search("MV",2)
        assertTrue(page.getBoolean("hasMore"));assertEquals("MV",page.getJSONArray("items").getJSONObject(0).getString("title"));assertEquals(2,server.requestCount)
        server.takeRequest();val req=server.takeRequest();assertEquals("2",req.requestUrl!!.queryParameter("page"));assertEquals("20",req.requestUrl!!.queryParameter("page_size"));assertNotNull(req.requestUrl!!.queryParameter("w_rid"))
    }}
    @Test fun verificationAndRateLimitAreExplicitAndNotRetried(){MockWebServer().use{server->server.start()
        server.enqueue(MockResponse().setResponseCode(412));val api=client(server)
        try{api.video("BV1GJ411x7h7");fail("must reject challenge")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("限制"))};assertEquals(1,server.requestCount)
    }}
    @Test fun ownFoldersStayPagedAndPrivateDenialIsNotAnEmptyList(){MockWebServer().use{server->server.start()
        server.enqueue(response("{\"list\":[{\"id\":99,\"title\":\"音乐\",\"media_count\":150}],\"has_more\":true}"))
        val api=client(server,{"DedeUserID=123; SESSDATA=private"})
        assertTrue(api.folders("",2).getBoolean("hasMore"));val req=server.takeRequest();assertEquals("123",req.requestUrl!!.queryParameter("up_mid"));assertEquals("20",req.requestUrl!!.queryParameter("ps"));assertEquals("2",req.requestUrl!!.queryParameter("pn"))
        server.enqueue(response("{}",-101));try{api.favorites("99",1);fail("private denial must not be empty success")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("登录"))}
    }}
    @Test fun loginPersistsCredentialsButReturnsOnlyState(){MockWebServer().use{server->server.start()
        server.enqueue(response("{\"qrcode_key\":\"private-key\",\"url\":\"https://account.bilibili.com/h5/account-h5/auth/scan-web?test=1\"}"))
        server.enqueue(response("{\"code\":0}").addHeader("Set-Cookie","SESSDATA=private-session; Path=/").addHeader("Set-Cookie","DedeUserID=123; Path=/"))
        var saved:String?=null;val api=client(server,save={saved=it});api.startLogin();val state=api.pollLogin().toString()
        assertTrue(saved!!.contains("SESSDATA=private-session"));assertFalse(state.contains("private"));assertFalse(state.contains("cookie"));assertTrue(state.contains("success"));api.logout();assertNull(saved)
    }}
    @Test fun selectedVideoHasStableReferenceAndPlayResolutionDoesNotReturnCookies(){MockWebServer().use{server->server.start()
        server.enqueue(response("{\"bvid\":\"BV1GJ411x7h7\",\"aid\":1,\"cid\":137649199,\"title\":\"MV\",\"owner\":{\"name\":\"音乐\"},\"duration\":213}"))
        server.enqueue(response("{\"durl\":[{\"url\":\"https://upos.bilivideo.com/mv.mp4?expires=test\"}]}"))
        val api=client(server,{"SESSDATA=private"});val video=api.video("BV1GJ411x7h7");assertEquals("8000000000137649199",video.id)
        val source=api.source(video.bvid,video.cid);assertFalse(source.headers.containsKey("Cookie"));assertTrue(source.headers["Referer"]!!.endsWith("${video.bvid}/"))
        assertFalse(video.json().toString().contains("expires"));assertFalse(video.json().toString().contains("private"))
        server.takeRequest();assertEquals("html5",server.takeRequest().requestUrl!!.queryParameter("platform"))
    }}
}
