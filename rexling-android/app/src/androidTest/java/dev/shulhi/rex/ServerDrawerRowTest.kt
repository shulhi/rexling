package dev.shulhi.rex

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ServerDrawerRowTest {
    @get:Rule val ui=createComposeRule()

    @Test fun disconnectingAndRemovingRequireConfirmation() {
        val shipyard=ServerProfile.create("Shipyard","https://shipyard.example")
        val calls=mutableListOf<String>()
        ui.setContent {
            RexTheme(false,ThemeCatalog.defaults[1]) {
                var revealed by remember {mutableStateOf(false)}
                ServerDrawerRow(shipyard,ServerStatus("connected"),selected=true,unread=false,working=false,
                    revealed=revealed,onReveal={revealed=it},onSelect={},onEdit={},
                    onDisconnect={calls+="disconnect"},onRemove={calls+="remove"})
            }
        }
        val row=ui.onNodeWithContentDescription("Server Shipyard")
        for(action in listOf("Disconnect","Remove")) {
            row.swipeForSwitcherActions()
            ui.onNodeWithContentDescription("$action server").performTouchInput {click()}
            ui.onNodeWithText("$action server?").assertIsDisplayed()
            ui.onNodeWithText("Cancel").performClick()
            ui.runOnIdle {
                assertEquals(if(action=="Disconnect") emptyList<String>() else listOf("disconnect"),calls)
            }
            row.swipeForSwitcherActions()
            ui.onNodeWithContentDescription("$action server").performTouchInput {click()}
            ui.onNodeWithText(action).performClick()
        }
        ui.runOnIdle {assertEquals(listOf("disconnect","remove"),calls)}
    }

    @Test fun swipingRevealsOneServersActionsAndTappingTheRowClosesThem() {
        val connected=ServerProfile.create("Shipyard","https://shipyard.example")
        val offline=ServerProfile.create("Build box","https://build.example")
        val calls=mutableListOf<String>()
        ui.setContent {
            RexTheme(false,ThemeCatalog.defaults[1]) {
                var revealed by remember {mutableStateOf<String?>(null)}
                Column {
                    for(server in listOf(connected,offline)) {
                        val active=server==connected
                        ServerDrawerRow(server,if(active) ServerStatus("connected",version="0.1.0-preview.25") else ServerStatus(),
                            selected=active,unread=false,working=false,revealed=revealed==server.id,
                            onReveal={open->if(open) revealed=server.id else if(revealed==server.id) revealed=null},
                            onSelect={calls+="select ${server.label}"},onEdit={calls+="edit ${server.label}"},
                            onDisconnect={calls+="disconnect ${server.label}"},onRemove={calls+="remove ${server.label}"})
                    }
                }
            }
        }
        val shipyard=ui.onNodeWithContentDescription("Server Shipyard").assertIsSelected()
        val buildBox=ui.onNodeWithContentDescription("Server Build box")
        val edit=ui.onNodeWithContentDescription("Edit server")
        val disconnect=ui.onNodeWithContentDescription("Disconnect server")
        edit.assertDoesNotExist()
        shipyard.swipeForSwitcherActions()
        disconnect.assertIsDisplayed()
        shipyard.performTouchInput {click()}
        edit.assertDoesNotExist()
        ui.runOnIdle {assertEquals(emptyList<String>(),calls)}
        shipyard.swipeForSwitcherActions()
        buildBox.swipeForSwitcherActions()
        disconnect.assertDoesNotExist()
        edit.performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("edit Build box"),calls)}
    }
}
