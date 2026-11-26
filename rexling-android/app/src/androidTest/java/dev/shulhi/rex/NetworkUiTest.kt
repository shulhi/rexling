package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class NetworkUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun pastedPrivateKeyUsesEncryptedStorageAndCanBeCancelled() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val endpoint="ssh://paste-key-test@127.0.0.1:2222"
        val pair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
        val key="-----BEGIN PRIVATE KEY-----\n"+android.util.Base64.encodeToString(pair.private.encoded,android.util.Base64.NO_WRAP)+"\n-----END PRIVATE KEY-----\n"
        runBlocking {NetworkSettings.removeKey(endpoint)}
        var selected by androidx.compose.runtime.mutableStateOf<String?>(null)
        compose.setContent {MaterialTheme {Column {SshKeyEditor(selected) {selected=it}}}}
        val previous=clipboard.primaryClip
        try {
            compose.runOnIdle {clipboard.setPrimaryClip(ClipData.newPlainText("Test SSH key",key))}
            compose.onNodeWithText("Paste new key").performClick()
            compose.onNodeWithText("Private key").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertFalse(NetworkSettings.hasKey(endpoint))
            compose.onNodeWithText("Paste new key").performClick()
            compose.onNodeWithText("Key name").performTextInput("Pasted test key")
            compose.onNodeWithText("Save key").assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis=10000) {selected!=null}
            compose.onNodeWithText("Pasted test key").assertIsDisplayed()
            assertFalse("Key selection is a draft until the server is saved",NetworkSettings.hasKey(endpoint))
            val stored=context.getSharedPreferences("network",0).getString("ssh_library","").orEmpty()
            assertFalse(stored.contains(key));assertFalse(stored.contains("PRIVATE KEY"))
        } finally {
            runBlocking {selected?.let {NetworkSettings.deleteKey(it)}}
            compose.runOnIdle {if(previous!=null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()}
        }
    }
    @Test fun emptyClipboardAllowsManualPasteAndRejectsInvalidKeys() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous=clipboard.primaryClip
        var selected by androidx.compose.runtime.mutableStateOf<String?>(null)
        compose.setContent {MaterialTheme {Column {SshKeyEditor(selected) {selected=it}}}}
        try {
            compose.runOnIdle {clipboard.clearPrimaryClip()}
            compose.onNodeWithText("Paste new key").performClick()
            compose.onNodeWithText("Save key").assertIsNotEnabled()
            compose.onNodeWithText("Key name").performTextInput("Invalid key")
            compose.onNodeWithText("Private key").performTextInput("not a private key")
            compose.onNodeWithText("Save key").performClick()
            compose.waitUntil(timeoutMillis=10000) {compose.onAllNodesWithText("ssh: no key found").fetchSemanticsNodes().isNotEmpty()}
            assertFalse(NetworkSettings.hasKey("ssh://invalid-paste@127.0.0.1:2222"))
            compose.onNodeWithText("Cancel").performClick()
        } finally {compose.runOnIdle {if(previous!=null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()}}
    }
    @Test fun networkSettingsExposeOptionalTailscaleAndControlServer() {
        compose.setContent {MaterialTheme {NetworkSheet {}}}
        compose.onNodeWithText("Built-in Tailscale").assertIsDisplayed()
        compose.onNodeWithText("Control server (optional)").assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"network-settings.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun serverEditorOffersPerServerNetworkAndSetup() {
        var dark by mutableStateOf(false)
        var editor by mutableStateOf(false)
        compose.setContent {RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
            EmptyServerContent {editor=true}
            if(editor) ServerEditor(null,{editor=false}) {_,_,_->null}
        }}
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithText("Connection network").assertDoesNotExist()
        compose.onNodeWithText("Name").performTextInput("Shipyard")
        compose.onNodeWithText("Server URL").performTextInput("https://shipyard")
        compose.onNodeWithText("Phone network / VPN").performScrollTo().performClick()
        compose.onNodeWithText("Built-in Tailscale").performClick()
        compose.onNodeWithText("Set up / sign in").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Control server (optional)").assertIsDisplayed()
        compose.onNodeWithText("Save & connect").assertDoesNotExist()
        for(isDark in listOf(false,true)) {
            compose.runOnIdle {dark=isDark}
            val sheet=compose.onNodeWithTag("Frosted overlay").captureToImage().toPixelMap()
            val surface=rexColorScheme(isDark,ThemeCatalog.defaults[if(isDark) 0 else 1].ui).surface
            assertEquals(surface,sheet[sheet.width/4,6])
        }
        compose.onNodeWithContentDescription("Close network settings").performClick()
        compose.onNodeWithText("Shipyard").assertIsDisplayed()
        compose.onNodeWithText("https://shipyard").assertIsDisplayed()
        compose.onNodeWithText("Set up / sign in").assertIsDisplayed()
        compose.onNodeWithText("Built-in Tailscale").performClick()
        compose.onNodeWithText("Phone network / VPN").performClick()
        compose.onNodeWithText("Use this phone’s network and existing VPN.").assertIsDisplayed()
    }
    @Test fun signInStatusHasReadableLabelAndBusyFeedback() {
        var busy by androidx.compose.runtime.mutableStateOf(false)
        var clicks=0
        compose.setContent {MaterialTheme {TailscaleStatusCard("NeedsLogin",busy) {clicks++;busy=true}}}
        compose.onNodeWithText("Sign-in required").assertIsDisplayed()
        compose.onNodeWithText("NeedsLogin").assertDoesNotExist()
        compose.onNodeWithText("Sign in").performClick()
        compose.runOnIdle {assertEquals(1,clicks)}
        compose.onNodeWithText("Sign in").assertDoesNotExist()
    }
    @Test fun connectedStatusReplacesSignInRatherThanOfferingItAgain() {
        var state by androidx.compose.runtime.mutableStateOf("NeedsLogin")
        compose.setContent {MaterialTheme {TailscaleStatusCard(state,false) {}}}
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.runOnIdle {state="Running"}
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithTag("Tailscale connected icon").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText("Sign in again").assertDoesNotExist()
    }
    @Test fun emptyServerStateOpensAddServerInsteadOfQuickTailscaleSetup() {
        var added=0
        compose.setContent {MaterialTheme {EmptyServerContent {added++}}}
        compose.onNodeWithText("Welcome to Rexling").assertIsDisplayed()
        compose.onNodeWithText("Rex server URL").assertDoesNotExist()
        compose.onNodeWithText("Connect over Tailscale").assertDoesNotExist()
        compose.onNodeWithText("Add server").performClick()
        compose.runOnIdle {assertEquals(1,added)}
    }
    @Test fun connectedDevicesAndRoutesShareTheTailnetStatusCard() {
        var dark by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
            androidx.compose.material3.Surface(color=MaterialTheme.colorScheme.background) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().padding(20.dp)) {
                TailscaleStatusCard("Running",false,listOf(TailscalePeerStatus("shipyard-rex","Direct"),TailscalePeerStatus("MacBook","Tailscale relay"))) {}
            }}
        }}
        for(value in listOf(false,true)) {
            compose.runOnIdle {dark=value}
            compose.onNodeWithText("Connected").assertIsDisplayed()
            compose.onNodeWithText("Active connections").assertIsDisplayed()
            compose.onNodeWithText("shipyard-rex").assertIsDisplayed()
            compose.onNodeWithText("Direct").assertIsDisplayed()
            compose.onNodeWithText("Sign in").assertDoesNotExist()
            compose.waitForIdle()
            val image=compose.onRoot().captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"tailnet-card-${if(value) "dark" else "light"}.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun phoneVpnConnectionIsSeparateFromUnconfiguredBuiltInTailscale() {
        compose.setContent {MaterialTheme {Column {
            ServerNetworkStatusCard("Shipyard",ServerNetworkRoute.PHONE,true)
            TailscaleStatusCard("Off",false) {}
        }}}
        compose.onNodeWithText("Phone network / VPN").assertIsDisplayed()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Not set up").assertIsDisplayed()
        compose.onNodeWithText("Starting…").assertDoesNotExist()
    }
    @Test fun pendingSignInCanBeCancelledAndItsBrowserLinkDisappears() {
        var pending by androidx.compose.runtime.mutableStateOf(true)
        var continued=0
        compose.setContent {MaterialTheme {if(pending) PendingTailscaleSignIn(false,{continued++},{pending=false}) else TailscaleStatusCard("Off",false) {}}}
        compose.onNodeWithText("Continue in browser").assertIsDisplayed()
        compose.onNodeWithText("Cancel sign-in").performClick()
        compose.onNodeWithText("Continue in browser").assertDoesNotExist()
        compose.onNodeWithText("Not set up").assertIsDisplayed()
        compose.runOnIdle {assertEquals(0,continued)}
    }
    @Test fun sshEditorOffersKeyImportAndKeylessConnection() {
        compose.setContent {MaterialTheme {ServerEditor(ServerProfile.create("Test SSH","ssh://tester@example.com"),{}) {_,_,_->null}}}
        compose.onNodeWithText("Import new key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No key").assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"ssh-settings.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}
