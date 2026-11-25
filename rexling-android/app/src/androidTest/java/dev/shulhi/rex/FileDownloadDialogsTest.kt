package dev.shulhi.rex

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test

class FileDownloadDialogsTest {
    @get:Rule val ui=createComposeRule()

    @Test fun aRemoteFileRequiresConfirmationBeforeThePickerOpens() {
        val scope=CoroutineScope(Dispatchers.Main)
        val downloads=FileDownloads(InstrumentationRegistry.getInstrumentation().targetContext,scope)
        val server=ServerProfile.create("Mac","http://127.0.0.1:1","test")
        val target=FileDownloadTarget(server,"session:example","block:original","/tmp/Build Files/Rexling.apk")
        var pickerRequests=0
        try {
            ui.setContent {MaterialTheme {FileDownloadDialogs(downloads) {pickerRequests++}}}
            ui.runOnIdle {downloads.offer(target)}
            ui.onNodeWithText("Download file?").assertIsDisplayed()
            ui.onNodeWithText("Save Rexling.apk from Mac?").assertIsDisplayed()
            ui.onNodeWithText(target.path).assertIsDisplayed()
            ui.runOnIdle {assertEquals(0,pickerRequests);assertNull(downloads.active)}
            ui.onNodeWithText("Cancel").performClick()
            ui.onNodeWithText("Download file?").assertDoesNotExist()
            ui.runOnIdle {assertEquals(0,pickerRequests);downloads.offer(target)}
            ui.onNodeWithText("Save as…").performClick()
            ui.runOnIdle {assertEquals(1,pickerRequests)}
        } finally {scope.cancel()}
    }

    @Test fun downloadsThroughNativeRexTransportToAnAndroidDocument() {
        val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("downloadServer")
        assumeNotNull(endpoint)
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q) return
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val resolver=context.contentResolver
        val scope=CoroutineScope(Dispatchers.Main)
        val downloads=FileDownloads(context,scope)
        val target=FileDownloadTarget(ServerProfile.create("Fixture",requireNotNull(endpoint),"test"),
            requireNotNull(args.getString("downloadSession")),requireNotNull(args.getString("downloadBlock")),
            requireNotNull(args.getString("downloadSource")))
        val destination=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,"rexling-test-${System.nanoTime()}.bin")
            put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream")
        }))
        try {
            ui.runOnUiThread {downloads.start(target,destination)}
            ui.waitUntil(15_000) {ui.runOnUiThread {downloads.active==null}}
            ui.runOnUiThread {assertEquals("",downloads.notice)}
            assertEquals("Rex download bytes",resolver.openInputStream(destination)!!.bufferedReader().use {it.readText()})
        } finally {
            scope.cancel()
            resolver.delete(destination,null,null)
        }
    }
}
