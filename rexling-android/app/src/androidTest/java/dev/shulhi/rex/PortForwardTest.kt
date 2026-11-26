package dev.shulhi.rex

import android.app.NotificationManager
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PortForwardTest {
    @get:Rule val compose=createComposeRule()
    @Test fun addFormValidatesPortsAndKeepsTheSelectedServer() {
        val chosen=AtomicReference<Triple<String,Int,Int>>()
        compose.setContent {MaterialTheme {
            PortForwardSheet(listOf(ForwardServer(ServerProfile.create("Test server","http://127.0.0.1:19701","test"),"session:test",true)),"test",null,{}) {_,host,port,local->chosen.set(Triple(host,port,local))}
        }}
        compose.onNodeWithContentDescription("Add port forward").performClick()
        compose.onNodeWithText("Port forwarding").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to port forwarding").performClick()
        compose.onNodeWithText("Port forwarding").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add port forward").performClick()
        compose.onNodeWithText("Remote host").assertDoesNotExist()
        compose.onNodeWithText("Phone port").assertDoesNotExist()
        compose.onNodeWithText("Forward",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Enter valid ports from 1 to 65535.").assertIsDisplayed()
        compose.onNodeWithText("Remote port").performTextInput("3000")
        compose.onNodeWithText("Forward",useUnmergedTree=true).performClick()
        compose.runOnIdle {assertEquals(Triple("127.0.0.1",3000,3000),chosen.get())}
        compose.onNodeWithText("Port forwarding").assertIsDisplayed()
    }
    @Test fun magicLinksKeepPortsReuseRestartAndFallBackAroundOtherApps() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("forward_server");val session=args.getString("forward_session")
        assumeTrue("Requires an owned disposable Rex session",endpoint!=null && session!=null)
        val remote=args.getString("forward_target_port")?.toInt() ?: 19702
        val context=instrumentation.targetContext;val server=ServerProfile.create("Magic test",endpoint!!,"magic-test")
        val policy=PortForwards.policy;var id=""
        fun active(): PortForward {
            val deadline=SystemClock.uptimeMillis()+15000
            while(SystemClock.uptimeMillis()<deadline) {
                val current=AtomicReference<PortForward>()
                instrumentation.runOnMainSync {current.set(PortForwards.items.firstOrNull {it.id==id})}
                val value=current.get()
                if(value?.phase=="active") return value
                if(value?.phase=="failed") fail(value.error)
                SystemClock.sleep(100)
            }
            error("Forward did not activate")
        }
        fun fetch(url: String): String {
            val connection=URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout=5000;connection.readTimeout=5000
            return try {assertEquals(200,connection.responseCode);connection.inputStream.bufferedReader().readText()} finally {connection.disconnect()}
        }
        try {
            instrumentation.runOnMainSync {
                PortForwards.changePolicy(PortConflictPolicy.ASK)
                PortForwards.openLink(context,server,session!!,LoopbackLink.parse("http://localhost:$remote/deep%2Fpath?q=a%20b#part")!!)
                id=PortForwards.linkNotice!!.id
            }
            val first=active();assertEquals(remote,first.boundPort)
            assertTrue(fetch("${first.url}/").contains("forward-test"))
            instrumentation.runOnMainSync {
                assertEquals("${first.url}/deep%2Fpath?q=a%20b#part",PortForwards.linkNotice!!.url)
                PortForwards.openLink(context,server,session!!,LoopbackLink.parse("http://localhost:$remote/another")!!)
                assertEquals(1,PortForwards.items.count {it.endpoint==server.endpoint && it.port==remote})
                PortForwards.stop(context,id)
                assertEquals("stopped",PortForwards.items.single {it.id==id}.phase)
                PortForwards.requestStart(context,id)
            }
            val restarted=active();assertEquals(remote,restarted.boundPort)
            assertTrue(fetch(restarted.url).contains("forward-test"))
            repeat(4) {
                instrumentation.runOnMainSync {PortForwards.stop(context,id);PortForwards.requestStart(context,id)}
                assertTrue(fetch(active().url).contains("forward-test"))
            }
            instrumentation.runOnMainSync {PortForwards.remove(context,id)}
            var occupied: java.net.ServerSocket?=null
            val deadline=SystemClock.uptimeMillis()+5000
            while(occupied==null && SystemClock.uptimeMillis()<deadline) {
                occupied=runCatching {java.net.ServerSocket().apply {reuseAddress=true;bind(java.net.InetSocketAddress("127.0.0.1",remote))}}.getOrNull()
                if(occupied==null) SystemClock.sleep(50)
            }
            assertNotNull("Old listener should release the phone port",occupied)
            occupied!!.use {
                instrumentation.runOnMainSync {
                    PortForwards.changePolicy(PortConflictPolicy.AUTO)
                    PortForwards.openLink(context,server,session!!,LoopbackLink.parse("http://localhost:$remote/")!!)
                    id=PortForwards.linkNotice!!.id
                }
                val fallback=active();assertNotEquals(remote,fallback.boundPort)
                assertTrue(fetch(fallback.url).contains("forward-test"))
            }
        } finally {instrumentation.runOnMainSync {if(id.isNotEmpty()) PortForwards.remove(context,id);PortForwards.dismissLinkNotice();PortForwards.cancelConflict();PortForwards.changePolicy(policy)}}
    }
    @Test fun serviceKeepsARealForwardAliveAndStopsIt() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val server=args.getString("forward_server")
        val session=args.getString("forward_session")
        assumeTrue("Requires an owned disposable Rex session",server!=null && session!=null)
        val context=instrumentation.targetContext
        instrumentation.runOnMainSync {PortForwards.start(context,ServerProfile.create("Forward test",server!!),session!!,"127.0.0.1",19702,0)}
        var id=""
        try {
            val deadline=SystemClock.uptimeMillis()+15000
            var active: PortForward?=null
            while(SystemClock.uptimeMillis()<deadline) {
                val item=AtomicReference<PortForward>()
                instrumentation.runOnMainSync {item.set(PortForwards.items.firstOrNull())}
                val current=item.get()
                if(current!=null) {id=current.id;assertNotEquals(current.error,"failed",current.phase);if(current.phase=="active") {active=current;break}}
                SystemClock.sleep(100)
            }
            assertNotNull("Forward did not activate",active)
            val connection=URL(active!!.url).openConnection() as HttpURLConnection
            connection.connectTimeout=5000;connection.readTimeout=5000
            try {assertEquals(200,connection.responseCode);assertTrue(connection.inputStream.bufferedReader().readText().contains("Directory listing"))}
            finally {connection.disconnect()}
            val notifications=context.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue(notifications.any {it.notification.channelId=="port_forwards"})
        } finally {
            instrumentation.runOnMainSync {if(id.isNotEmpty()) PortForwards.stop(context,id) else context.stopService(android.content.Intent(context,PortForwardService::class.java))}
        }
    }
}
