package dev.shulhi.rex

import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class KeySource { PASTE,FILE }

@Composable internal fun SshKeyEditor(selected: String?,enabled: Boolean=true,onSelected: (String?)->Unit) {
    val keys by NetworkSettings.sshKeys.collectAsState()
    val key=keys.firstOrNull {it.id==selected}
    var expanded by remember {mutableStateOf(false)}
    var source by remember {mutableStateOf<KeySource?>(null)}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("SSH key",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextButton(enabled=enabled,onClick={expanded=true},contentPadding=PaddingValues(0.dp)) {
                Text(key?.name ?: "No key",style=SettingsType.row,maxLines=1,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp));ChromeSymbol(ChromeIcon.CHEVRON,Modifier.size(14.dp))
            }
            DropdownMenu(expanded,{expanded=false}) {
                DropdownMenuItem(text={Text("No key (Tailscale SSH)",style=SettingsType.row)},onClick={onSelected(null);expanded=false})
                keys.forEach {item->DropdownMenuItem(text={Column {
                    Text(item.name,style=SettingsType.row)
                    if(item.fingerprint.isNotBlank()) Text(item.fingerprint,style=SettingsType.detail,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }},onClick={onSelected(item.id);expanded=false},trailingIcon={if(item.id==selected) ChromeSymbol(ChromeIcon.CHECK,Modifier.size(16.dp))})}
            }
        }
        Text(if(key==null) "Connect without a key when the host supports it." else key.fingerprint.ifBlank {"Saved securely on this phone"},style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            TextButton(enabled=enabled,onClick={source=KeySource.PASTE},contentPadding=PaddingValues(horizontal=6.dp,vertical=4.dp)) {Text("Paste new key",style=SettingsType.detail)}
            TextButton(enabled=enabled,onClick={source=KeySource.FILE},contentPadding=PaddingValues(horizontal=6.dp,vertical=4.dp)) {Text("Import new key",style=SettingsType.detail)}
        }
    }
    source?.let {mode->SshKeyImportDialog(mode,{source=null}) {id->onSelected(id);source=null}}
}

@Composable internal fun SshKeysSheet(onClose: ()->Unit,hazeState: HazeState?=null) {
    val keys by NetworkSettings.sshKeys.collectAsState()
    val bindings by NetworkSettings.sshKeyBindings.collectAsState()
    val scope=rememberCoroutineScope()
    var adding by remember {mutableStateOf(false)}
    var source by remember {mutableStateOf<KeySource?>(null)}
    var editing by remember {mutableStateOf<SshKeyInfo?>(null)}
    var removing by remember {mutableStateOf<SshKeyInfo?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    SettingsDrawer("SSH keys","Close SSH keys",onClose,hazeState) {
        Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Reuse a key across your servers.",Modifier.weight(1f),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    FilledTonalButton(onClick={adding=true},contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp)) {
                        ChromeSymbol(ChromeIcon.ADD,Modifier.size(16.dp),color=LocalContentColor.current);Spacer(Modifier.width(4.dp));Text("Add key",style=SettingsType.row)
                    }
                    DropdownMenu(adding,{adding=false}) {
                        DropdownMenuItem(text={Text("Paste key",style=SettingsType.row)},onClick={adding=false;source=KeySource.PASTE})
                        DropdownMenuItem(text={Text("Import file",style=SettingsType.row)},onClick={adding=false;source=KeySource.FILE})
                    }
                }
            }
            if(keys.isEmpty()) Text("No SSH keys yet. Add a private key here or during server setup.",Modifier.padding(vertical=16.dp),style=SettingsType.body,color=MaterialTheme.colorScheme.onSurfaceVariant)
            keys.forEach {item->
                val count=bindings.values.count {it==item.id}
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f))
                    .clickable(enabled=!busy) {editing=item}.padding(start=12.dp,top=10.dp,bottom=10.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    SettingsIcon(SettingsGlyph.KEY)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(item.name,style=SettingsType.row,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(item.fingerprint.ifBlank {"Imported key"},style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(count==0) "Not assigned" else "$count ${if(count==1) "server" else "servers"}",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ChromeButton(ChromeIcon.CLOSE,"Delete SSH key ${item.name}",enabled=!busy,onClick={removing=item})
                }
            }
            Text("Private keys are encrypted on this phone. Removing a server keeps its key in this library.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.error)}
        }
    }
    source?.let {mode->SshKeyImportDialog(mode,{source=null}) {source=null}}
    editing?.let {item->
        var name by remember(item.id) {mutableStateOf(item.name)}
        var renameError by remember(item.id) {mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={if(!busy) editing=null},title={Text("Rename SSH key",style=SettingsType.title)},text={Column {
            OutlinedTextField(name,{name=it;renameError=null},singleLine=true,label={Text("Name")},textStyle=SettingsType.body,enabled=!busy)
            renameError?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(enabled=!busy && name.isNotBlank(),onClick={busy=true;scope.launch {
            renameError=runCatching {NetworkSettings.renameKey(item.id,name)}.getOrElse {it.message ?: "Could not rename key"};busy=false;if(renameError==null) editing=null
        }}) {Text("Save")}},dismissButton={TextButton(enabled=!busy,onClick={editing=null}) {Text("Cancel")}})
    }
    removing?.let {item->
        val endpoints=NetworkSettings.keyEndpoints(item.id)
        AlertDialog(onDismissRequest={if(!busy) removing=null},title={Text("Delete SSH key?",style=SettingsType.title)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(item.name,style=SettingsType.row)
            Text(if(endpoints.isEmpty()) "This removes the key from this phone." else "${endpoints.size} ${if(endpoints.size==1) "server uses" else "servers use"} this key. Their saved key selection will be cleared.",style=SettingsType.body)
        }},confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch {
            error=runCatching {NetworkSettings.deleteKey(item.id)}.getOrElse {it.message ?: "Could not delete key"};busy=false;if(error==null) removing=null
        }}) {Text("Delete",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(enabled=!busy,onClick={removing=null}) {Text("Cancel")}})
    }
}

@Composable private fun SshKeyImportDialog(mode: KeySource,onClose: ()->Unit,onAdded: (String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var name by remember {mutableStateOf("")}
    var text by remember {mutableStateOf("")}
    var filename by remember {mutableStateOf("")}
    var passphrase by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) {busy=true;scope.launch {
            runCatching {withContext(Dispatchers.IO) {
                val filename=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {c->if(c.moveToFirst()) c.getString(0) else null}.orEmpty()
                val contents=context.contentResolver.openInputStream(uri)?.use {stream->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true) {val count=stream.read(buffer);if(count<0) break;require(output.size()+count<=131072) {"SSH key is too large"};output.write(buffer,0,count)}
                    output.toString(Charsets.UTF_8.name())
                } ?: throw IllegalArgumentException("Could not read the file")
                filename to contents
            }}.onSuccess {(file,contents)->filename=file;text=contents;if(name.isBlank()) name=file.take(80);error=null}.onFailure {error=it.message ?: "Could not read SSH key"}
            busy=false
        }}
    }
    LaunchedEffect(mode) {
        if(mode==KeySource.FILE) picker.launch(arrayOf("*/*"))
        else runCatching {
            val clip=(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val contents=if(clip!=null && clip.itemCount>0) clip.getItemAt(0).text?.toString().orEmpty() else ""
            require(contents.toByteArray().size<=131072) {"SSH key is too large"};text=contents
        }.onFailure {error=it.message}
    }
    AlertDialog(onDismissRequest={if(!busy) onClose()},title={Text(if(mode==KeySource.PASTE) "Paste SSH key" else "Import SSH key",style=SettingsType.title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name,{name=it;error=null},Modifier.fillMaxWidth(),label={Text("Key name")},placeholder={Text("Work key")},singleLine=true,textStyle=SettingsType.body,enabled=!busy)
            if(mode==KeySource.PASTE) OutlinedTextField(text,{value->if(value.toByteArray().size<=131072) {text=value;error=null} else error="SSH key is too large"},Modifier.fillMaxWidth(),label={Text("Private key")},supportingText={Text("Include the BEGIN and END lines.",style=SettingsType.detail)},minLines=3,maxLines=5,enabled=!busy,textStyle=SettingsType.detail,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
            else TextButton(enabled=!busy,onClick={picker.launch(arrayOf("*/*"))}) {Text(filename.ifBlank {"Choose file"},style=SettingsType.row)}
            OutlinedTextField(passphrase,{passphrase=it;error=null},Modifier.fillMaxWidth(),label={Text("Passphrase (optional)")},singleLine=true,textStyle=SettingsType.body,enabled=!busy,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
            Text("Saved encrypted on this phone. The same key can be selected for multiple servers.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=SettingsType.detail)}
        }
    },confirmButton={TextButton(enabled=!busy && name.isNotBlank() && text.isNotBlank(),onClick={busy=true;scope.launch {
        val result=runCatching {NetworkSettings.addKey(name,text,passphrase)}.getOrElse {SshKeyImportResult(error=it.message ?: "Could not save SSH key")}
        busy=false;error=result.error
        result.id?.let {id->text="";passphrase="";onAdded(id)}
    }}) {Text(if(busy) "Saving…" else "Save key")}},dismissButton={TextButton(enabled=!busy,onClick=onClose) {Text("Cancel")}})
}
