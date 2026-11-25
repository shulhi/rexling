package dev.shulhi.rex

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class FileDownloadTarget(val server: ServerProfile,val session: String,val block: String,val path: String) {
    val name=path.substringAfterLast('/').takeUnless {it.isBlank() || it=="." || it==".."} ?: "Download"
}

internal class FileDownloads(private val context: Context,private val scope: CoroutineScope) {
    var prompt by mutableStateOf<FileDownloadTarget?>(null)
    var pendingSave: FileDownloadTarget?=null
    var notice by mutableStateOf("")
    var active by mutableStateOf<FileDownloadTarget?>(null)
        private set
    var fraction by mutableStateOf<Float?>(null)
        private set
    private var job: Job?=null

    fun offer(target: FileDownloadTarget) {
        if(active==null && pendingSave==null && prompt==null) prompt=target
    }

    fun cancel() {job?.cancel()}

    fun start(target: FileDownloadTarget,destination: Uri) {
        if(active!=null) return
        active=target
        fraction=null
        job=scope.launch {
            var saved=false
            try {
                withContext(Dispatchers.IO) {saveFile(target,destination)}
                saved=true
                Toast.makeText(context,"Saved ${target.name}",Toast.LENGTH_SHORT).show()
            } catch(canceled: CancellationException) {
                throw canceled
            } catch(error: Exception) {
                notice=error.localizedMessage?.takeIf {it.isNotBlank()} ?: "Could not save the file."
            } finally {
                // ACTION_CREATE_DOCUMENT created this entry for this download. Remove it on failure.
                if(!saved) withContext(NonCancellable+Dispatchers.IO) {
                    runCatching {DocumentsContract.deleteDocument(context.contentResolver,destination)}
                }
                active=null
                fraction=null
                job=null
            }
        }
    }

    private suspend fun saveFile(target: FileDownloadTarget,destination: Uri) {
        val staged=File.createTempFile("rex-download-",".part",context.cacheDir)
        try {
            val request=JSONObject().put("endpoint",target.server.endpoint)
                .put("session",target.session).put("block",target.block)
                .put("source",target.path).put("path",staged.absolutePath).put("ref","download:${UUID.randomUUID()}")
            val handle=NativeBridge.downloadCreate(request.toString())
            check(handle!=0L) {"Could not start the Rex download."}
            try {
                while(true) {
                    val status=JSONObject(NativeBridge.downloadPoll(handle))
                    when(status.getString("phase")) {
                        "done" -> break
                        "unsupported" -> error("Update this Rex server to download files.")
                        "failed","uncertain","canceled" -> error(status.optString("error").ifBlank {"Rex could not download this file."})
                        "receiving" -> {
                            val total=status.optLong("total")
                            val received=status.optLong("sent")
                            if(total>0) fraction=(received.toDouble()/total).toFloat().coerceIn(0f,1f)
                        }
                    }
                    delay(100)
                }
            } finally {NativeBridge.downloadDestroy(handle)}

            val output=context.contentResolver.openOutputStream(destination)
                ?: error("Android couldn’t open the selected destination.")
            output.use {file->
                staged.inputStream().use {input->
                    val buffer=ByteArray(DEFAULT_BUFFER_SIZE)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count=input.read(buffer)
                        if(count<0) break
                        file.write(buffer,0,count)
                    }
                }
            }
        } finally {staged.delete()}
    }
}

@Composable internal fun FileDownloadDialogs(downloads: FileDownloads,beforePicker: (()->Unit)->Unit) {
    val save=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
        val target=downloads.pendingSave
        downloads.pendingSave=null
        val destination=result.data?.data
        if(target!=null && destination!=null) downloads.start(target,destination)
    }
    downloads.prompt?.let {target->
        AlertDialog(onDismissRequest={downloads.prompt=null},title={Text("Download file?")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Save ${target.name} from ${target.server.label}?")
                    Text(target.path,style=SettingsType.detail)
                }
            },
            confirmButton={TextButton(onClick={
                downloads.prompt=null
                beforePicker {
                    downloads.pendingSave=target
                    val mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(target.name.substringAfterLast('.',"").lowercase())
                    val destination=Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(mime ?: "application/octet-stream").putExtra(Intent.EXTRA_TITLE,target.name)
                    runCatching {save.launch(destination)}.onFailure {
                        downloads.pendingSave=null
                        downloads.notice="Android couldn’t open the file picker."
                    }
                }
            }) {Text("Save as…")}},
            dismissButton={TextButton(onClick={downloads.prompt=null}) {Text("Cancel")}})
    }
    downloads.active?.let {target->
        AlertDialog(onDismissRequest={},title={Text("Downloading file")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(target.name)
                    val progress=downloads.fraction
                    if(progress==null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
                }
            },
            confirmButton={TextButton(onClick=downloads::cancel) {Text("Cancel")}})
    }
    if(downloads.notice.isNotBlank()) AlertDialog(onDismissRequest={downloads.notice=""},title={Text("File download")},
        text={Text(downloads.notice)},confirmButton={TextButton(onClick={downloads.notice=""}) {Text("OK")}})
}
