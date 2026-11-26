package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServerIconPickerTest {
    @get:Rule val ui=createComposeRule()
    @Test fun pickerOffersAllIconsAndChangesOnlyTheSelection() {
        val selected=mutableStateOf(ServerIcon.GLOBE)
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {ServerIconPicker(selected.value) {selected.value=it}}}
        ServerIcon.entries.forEach {icon->
            ui.onNodeWithContentDescription("Choose server icon").performClick()
            if(icon==ServerIcon.GLOBE) {
                ui.onNode(isPopup()).captureToImage().asAndroidBitmap().let {bitmap->
                    File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"server-icon-picker-light.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
            }
            ui.onNode(hasText(icon.label) and hasAnyAncestor(isPopup())).performClick()
            ui.onNode(isPopup()).assertDoesNotExist()
            ui.runOnIdle {assertEquals(icon,selected.value)}
        }
    }
    @Test fun editorSavesTheChosenIconWithTheExistingServerDetails() {
        val server=ServerProfile.create("Work Mac","https://work.example","work",ServerIcon.MAC_MINI)
        var saved: Triple<String,String,ServerIcon>?=null;var closed=0
        ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {
            ServerEditor(server,{closed++}) {name,url,icon->saved=Triple(name,url,icon);null}
        }}
        ui.onNodeWithContentDescription("Choose server icon").performClick()
        ui.onNode(hasText("MacBook") and hasAnyAncestor(isPopup())).performClick()
        ui.runOnIdle {assertNull(saved);assertEquals(0,closed)}
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {assertEquals(Triple(server.label,server.endpoint,ServerIcon.MACBOOK),saved);assertEquals(1,closed)}
    }
    @Test fun reachabilityIsForTheSavedConnectionAndRemovalRequiresConfirmation() {
        val server=ServerProfile.create("Shipyard","https://shipyard.example","fixture")
        var removes=0
        val open=mutableStateOf(true)
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            if(open.value) ServerEditor(server,{open.value=false},
                status=ServerStatus("connected",version="0.1.0-preview.25"),onRemove={removes++}) {_,_,_->null}
        }}
        ui.onNodeWithText("Reachable · Rex 0.1.0-preview.25").assertIsDisplayed()
        ui.onNodeWithText(server.endpoint).performTextReplacement("https://other.example")
        ui.onNodeWithText("Reachable · Rex 0.1.0-preview.25").assertDoesNotExist()
        ui.onNodeWithText("Remove server").performScrollTo().performClick()
        ui.onNodeWithText("Remove server?").assertIsDisplayed()
        ui.runOnIdle {assertEquals(0,removes)}
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("Remove server").performScrollTo().performClick()
        ui.onNodeWithText("Remove").performClick()
        ui.runOnIdle {assertEquals(1,removes)}
        ui.onNodeWithText("Edit server").assertDoesNotExist()
    }
}
