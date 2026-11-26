package dev.shulhi.rex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

class ServerUrlCheckTest {
    @get:Rule val ui=createComposeRule()

    @Test fun addServerChecksTheDraftWithoutSavingIt() {
        val hello=AtomicReference<WebSocket>()
        val requestedVersion=AtomicReference<Pair<WebSocket,String>>()
        MockWebServer().use {rex->
            rex.enqueue(MockResponse().addHeader("Sec-WebSocket-Protocol","rex.control.v1").withWebSocketUpgrade(object: WebSocketListener() {
                override fun onOpen(webSocket: WebSocket,response: Response) {hello.set(webSocket)}
                override fun onMessage(webSocket: WebSocket,text: String) {
                    val request=JSONObject(text)
                    if(request.optString("type")=="hello") {
                        webSocket.send("""{"type":"welcome","protocol":"rex.control.v1","api_revision":9,"limits":{"max_message_bytes":1048576},"client":{"id":"client:test"}}""")
                    } else if(request.optString("method")=="server.version") {
                        requestedVersion.set(webSocket to request.getString("id"))
                    }
                }
            }))
            rex.start(InetAddress.getByName("127.0.0.1"),0)
            var saves=0
            ui.setContent {MaterialTheme {ServerEditor(null,{}) {_,_,_->saves++;null}}}
            ui.onNodeWithText("HTTPS address or ssh://user@host").assertIsDisplayed()
            val url="http://127.0.0.1:${rex.port}"
            ui.onNodeWithText("Server URL").performTextInput(url)
            ui.waitUntil(8_000) {requestedVersion.get()!=null}
            ui.onNodeWithText("Checking…").assertIsDisplayed()
            val (ws,id)=requestedVersion.get()
            ws.send("""{"type":"response","id":"$id","payload":{"rex_version":"0.1.0-preview.25"}}""")
            val reachable="Reachable · Rex 0.1.0-preview.25"
            ui.waitUntil(8_000) {ui.onAllNodesWithText(reachable).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(reachable).assertIsDisplayed()
            ui.onNodeWithText(url).performTextReplacement("http://127.0.0.1:1")
            ui.onNodeWithText(reachable).assertDoesNotExist()
            ui.waitUntil(8_000) {ui.onAllNodesWithText("Can’t reach server",substring=true).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("Can’t reach server",substring=true).assertIsDisplayed()
            ui.runOnIdle {assertEquals(0,saves)}
            hello.get()?.close(1000,null)
        }
    }
}
