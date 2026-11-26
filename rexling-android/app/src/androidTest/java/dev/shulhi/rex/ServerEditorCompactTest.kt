package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ServerEditorCompactTest {
    @get:Rule val ui=createComposeRule()
    @Test fun savedKeyActionsStayOnOneRowAndTheEditorFitsBothThemes() {
        val endpoint="ssh://compact-ui@127.0.0.1:2222"
        val pair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
        val key="-----BEGIN PRIVATE KEY-----\n"+android.util.Base64.encodeToString(pair.private.encoded,android.util.Base64.NO_WRAP)+"\n-----END PRIVATE KEY-----\n"
        runBlocking {assertNull(NetworkSettings.importKey(endpoint,key,""))}
        var dark by mutableStateOf(false)
        try {
            ui.setContent {RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
                ServerEditor(ServerProfile.create("Shipyard 2",endpoint),{},onRemove={}) {_,_,_->null}
            }}
            for(value in listOf(false,true)) {
                ui.runOnIdle {dark=value}
                ui.onNodeWithText("Remove server").performScrollTo().assertIsDisplayed();ui.onNodeWithText("Save").assertIsDisplayed()
                val file=ui.onNodeWithText("Import new key").fetchSemanticsNode().boundsInRoot
                val paste=ui.onNodeWithText("Paste new key").fetchSemanticsNode().boundsInRoot
                assertEquals(file.center.y,paste.center.y,2f)
                assertTrue(file.left>=paste.right)
                ui.onNodeWithContentDescription("Choose server icon").performScrollTo().assertIsDisplayed()
                val instrumentation=InstrumentationRegistry.getInstrumentation();ui.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(300)
                File(instrumentation.targetContext.cacheDir,"server-editor-compact-${if(value) "dark" else "light"}.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
                }
            }
        } finally {runBlocking {NetworkSettings.removeKey(endpoint)}}
    }
}
