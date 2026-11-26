package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun EmptyServerContent(onAdd: ()->Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Welcome to Rexling",style=SettingsType.title)
        Text("Add a Rex server to get started. Choose your phone’s network or built-in Tailscale during setup.",style=SettingsType.body,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick=onAdd) {ChromeSymbol(ChromeIcon.ADD,Modifier.size(18.dp),color=LocalContentColor.current);Spacer(Modifier.width(6.dp));Text("Add server")}
    }
}

@Composable internal fun ServersSheet(model: RexModel,hazeState: dev.chrisbanes.haze.HazeState?=null,onDismiss: ()->Unit) {
    var editing by remember {mutableStateOf<ServerProfile?>(null)}
    var editor by remember {mutableStateOf(false)}
    var revealedServer by remember {mutableStateOf<String?>(null)}
    val scroll=rememberLazyListState()
    val subtitle="${model.connectedCount} of ${model.servers.size} connected"+
        if(model.servers.size>1) " · switching keeps others open" else ""
    val activity=model.activitySources.associateBy {it.server.id}
    if(!editor) SettingsDrawer("Servers","Close servers",onDismiss,hazeState,subtitle=subtitle,headerActions={
        ChromeButton(ChromeIcon.ADD,"Add server") {revealedServer=null;editing=null;editor=true}
    }) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(max=560.dp),state=scroll,
            contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if(model.servers.isEmpty()) item {
                Text("Add a Rex server to get started.",Modifier.padding(vertical=12.dp),style=SettingsType.paneDetail,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(model.servers,key={it.id}) {server->
                val source=activity[server.id]?.takeIf {it.connected}
                ServerDrawerRow(
                    server,model.serverStatuses[server.id] ?: ServerStatus(),server.id==model.activeServerId,
                    unread=source?.items?.any {it.notifiable && it.key !in source.seen}==true,
                    working=source?.items?.any {it.state==ActivityState.WORKING}==true,
                    revealed=revealedServer==server.id,onReveal={open->
                        if(open) revealedServer=server.id else if(revealedServer==server.id) revealedServer=null
                    },
                    onSelect={onDismiss();model.connectServer(server.id)},
                    onEdit={editing=server;editor=true},
                    onDisconnect={model.disconnectServer(server.id)},
                    onRemove={model.forgetServer(server.id)},
                )
            }
        }
    }
    if(editor) {
        val server=editing
        ServerEditor(server,onClose={editor=false},status=model.serverStatuses[server?.id] ?: ServerStatus(),
            onRemove=server?.let {profile->{model.forgetServer(profile.id)}},
            onSave={label,endpoint,icon->model.saveServer(server?.id,label,endpoint,icon)})
    }
}

@Composable internal fun ServerDrawerRow(
    server: ServerProfile,status: ServerStatus,selected: Boolean,unread: Boolean,working: Boolean,
    revealed: Boolean,onReveal: (Boolean)->Unit,
    onSelect: ()->Unit,onEdit: ()->Unit,onDisconnect: ()->Unit,onRemove: ()->Unit,
) {
    var confirmation by remember(server.id) {mutableStateOf<ServerConfirmation?>(null)}
    val actions=buildList {
        add(SwitcherRowAction("Edit server",ChromeIcon.EDIT,run=onEdit))
        if(status.maintained) add(SwitcherRowAction("Disconnect server",ChromeIcon.UNPLUG) {
            confirmation=ServerConfirmation.DISCONNECT
        })
        add(SwitcherRowAction("Remove server",ChromeIcon.CLOSE,destructive=true) {
            confirmation=ServerConfirmation.REMOVE
        })
    }
    val colors=MaterialTheme.colorScheme
    val connecting=status.phase in listOf("connecting","reconnecting")
    val failed=status.phase=="error"
    val inactive=!status.connected && !connecting && !failed
    val connection=when(status.phase) {
        "connected" -> "Connected"
        "connecting" -> "Connecting…"
        "reconnecting" -> "Reconnecting…"
        "paused" -> "Paused"
        "error" -> "Connection failed"
        else -> "Disconnected"
    }
    val accessibilityStatus=listOfNotNull(connection,"Viewing".takeIf {selected},"Working".takeIf {working},
        "Unread activity".takeIf {unread}).joinToString(", ")
    val labelColor=if(inactive && !selected) colors.onSurfaceVariant else colors.onSurface
    val rowBackground=colors.surfaceVariant.copy(alpha=if(selected) .8f else .35f).compositeOver(colors.surface)
    val dark=LocalTerminalPalette.current.dark
    val connectionColor=when {
        status.connected -> if(dark) Color(0xff73cd87) else Color(0xff217638)
        connecting -> if(dark) Color(0xffedbc74) else Color(0xff955a0c)
        failed -> colors.error
        else -> colors.outline
    }
    SwitcherSwipeRow(revealed,onReveal,actions,enabled=true) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp)
            .background(rowBackground)
            .selectable(selected,role=Role.RadioButton,onClick={if(revealed) onReveal(false) else onSelect()})
            .semantics {
                contentDescription="Server ${server.label}"
                stateDescription=accessibilityStatus
                customActions=actions.map {action->
                    CustomAccessibilityAction(action.label) {onReveal(false);action.run();true}
                }
            }
            .padding(start=8.dp,end=12.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(3.dp).height(22.dp)
                .then(if(selected) Modifier.background(colors.primary,RoundedCornerShape(2.dp)) else Modifier))
            Box(Modifier.size(36.dp),contentAlignment=Alignment.Center) {
                Box(Modifier.size(22.dp)) {
                    ServerGlyph(server.icon,Modifier.matchParentSize(),if(selected || working) colors.primary else labelColor)
                    if(unread) ServerIconDot(colors.primary,rowBackground,Modifier.align(Alignment.TopEnd).offset(x=2.dp,y=(-2).dp))
                    ServerIconDot(connectionColor,rowBackground,Modifier.align(Alignment.BottomEnd).offset(x=2.dp,y=2.dp))
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(server.label,Modifier.weight(1f),style=SettingsType.paneTitle,
                        color=if(selected) colors.primary else labelColor,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(status.version.takeIf {status.connected && it.isNotBlank()} ?: connection,
                        Modifier.widthIn(max=118.dp),style=SettingsType.paneDetail,
                        color=if(failed) colors.error else colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Text(server.endpoint,style=SettingsType.paneDetail,fontFamily=MonoUi,color=colors.onSurfaceVariant,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
                if(failed && status.error.isNotBlank()) {
                    Text(status.error,style=SettingsType.paneDetail,color=colors.error,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
    confirmation?.let {action->
        ServerConfirmationDialog(server,action,onCancel={confirmation=null},onConfirm={
            confirmation=null
            when(action) {
                ServerConfirmation.DISCONNECT -> onDisconnect()
                ServerConfirmation.REMOVE -> onRemove()
            }
        })
    }
}

@Composable private fun ServerIconDot(color: Color,background: Color,modifier: Modifier) {
    Box(modifier.size(10.dp).border(1.5.dp,background,CircleShape).background(color,CircleShape))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ServerEditor(
    server: ServerProfile?,onClose: ()->Unit,status: ServerStatus=ServerStatus(),
    onRemove: (() -> Unit)?=null,onSave: (String,String,ServerIcon)->String?,
) {
    val networkStatus by NetworkSettings.status.collectAsState()
    val tailnetConnected=networkStatus.optJSONObject("tailscale")?.optString("BackendState")=="Running"
    val savedRoute=remember(server?.endpoint) {NetworkSettings.route(server?.endpoint.orEmpty())}
    val savedKey=remember(server?.endpoint) {NetworkSettings.selectedKey(server?.endpoint.orEmpty())}
    var name by remember(server?.id) {mutableStateOf(server?.label.orEmpty())}
    var endpoint by remember(server?.id) {mutableStateOf(server?.endpoint.orEmpty())}
    var icon by remember(server?.id) {mutableStateOf(server?.icon ?: ServerIcon.GLOBE)}
    var error by remember {mutableStateOf<String?>(null)}
    var route by remember(server?.id) {mutableStateOf(savedRoute)}
    var selectedKey by remember(server?.id) {mutableStateOf(savedKey)}
    var routesOpen by remember {mutableStateOf(false)}
    var networkOpen by remember {mutableStateOf(false)}
    var confirmRemove by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val scroll=rememberScrollState()
    val colors=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(10.dp)
    val normalized=remember(endpoint) {runCatching {ServerProfile.normalizeEndpoint(endpoint)}.getOrNull()}
    val ssh=normalized?.startsWith("ssh://")==true
    val reachable=status.connected && normalized!=null && normalized==server?.endpoint &&
        route==savedRoute && (!ssh || selectedKey==savedKey)

    val checkAllowed=normalized!=null && !busy && !networkOpen
    val checkingKey=selectedKey.takeIf {ssh}
    var urlCheck by remember(normalized,route,checkingKey,checkAllowed,tailnetConnected) {
        mutableStateOf<ServerUrlCheck>(ServerUrlCheck.Editing)
    }
    LaunchedEffect(normalized,route,checkingKey,checkAllowed,tailnetConnected,reachable) {
        if(normalized==null || !checkAllowed || reachable) return@LaunchedEffect
        urlCheck=ServerUrlCheck.Editing
        delay(700)
        if(route==ServerNetworkRoute.BUILTIN && !tailnetConnected) {
            urlCheck=ServerUrlCheck.Error("Connect to built-in Tailscale first")
        } else {
            urlCheck=ServerUrlCheck.Checking
            urlCheck=NetworkSettings.checkServer(normalized,route,checkingKey)
        }
    }
    val urlState=if(reachable) ServerUrlCheck.Reachable(status.version) else urlCheck

    fun save() {
        if(busy) return
        val profile=runCatching {ServerProfile.create(name,endpoint,icon=icon)}.getOrElse {error=it.message;return}
        val requestedRoute=route
        val requestedKey=selectedKey
        busy=true
        error=null
        scope.launch {
            val previousRoute=NetworkSettings.route(profile.endpoint)
            val previousKey=NetworkSettings.selectedKey(profile.endpoint)
            var committed=false
            try {
                error=NetworkSettings.saveRoute(profile.endpoint,requestedRoute) ?:
                    (if(ssh) NetworkSettings.assignKey(profile.endpoint,requestedKey) else null) ?:
                    onSave(profile.label,profile.endpoint,profile.icon)
                committed=error==null
            } catch(cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch(failure: Exception) {
                error=failure.message ?: "Could not save server"
            } finally {
                if(!committed) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if(previousRoute!=requestedRoute) NetworkSettings.saveRoute(profile.endpoint,previousRoute)
                    if(ssh && previousKey!=requestedKey) NetworkSettings.assignKey(profile.endpoint,previousKey)
                }
                busy=false
            }
            if(committed) onClose()
        }
    }

    if(networkOpen) {
        NetworkSheet(onClose={networkOpen=false})
        return
    }

    SettingsDrawer(if(server==null) "Add server" else "Edit server","Close server editor",onClose,
        onBack=onClose.takeIf {server!=null},backDescription="Back to servers",surfaceColor=colors.surface,
        showClose=server==null,dismissEnabled=!busy,headerActions={
            Button(::save,enabled=endpoint.isNotBlank() && !busy,modifier=Modifier.padding(end=8.dp),
                contentPadding=PaddingValues(horizontal=16.dp,vertical=6.dp)) {
                Text(if(busy) "Saving…" else if(server==null) "Save & connect" else "Save",style=SettingsType.row)
            }
        }) {
        HorizontalDivider(color=colors.outlineVariant.copy(alpha=.55f))
        Column(Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(max=560.dp).verticalScroll(scroll)
            .padding(horizontal=16.dp).padding(top=12.dp,bottom=if(server==null) 28.dp else 12.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                ServerIconPicker(icon,compact=true,enabled=!busy) {icon=it}
                OutlinedTextField(name,{name=it;error=null},Modifier.weight(1f),label={Text("Name")},singleLine=true,
                    enabled=!busy,textStyle=SettingsType.body,shape=shape)
            }
            OutlinedTextField(endpoint,{endpoint=it;error=null},Modifier.fillMaxWidth(),label={Text("Server URL")},singleLine=true,
                enabled=!busy,isError=urlState is ServerUrlCheck.Error,textStyle=SettingsType.body.copy(fontFamily=MonoUi),shape=shape,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),
                placeholder={Text("https://host.tailnet.ts.net",style=SettingsType.detail,fontFamily=MonoUi)},
                supportingText={ServerUrlStatus(urlState)})
            if(normalized!=null) {
                Column {
                    ExposedDropdownMenuBox(routesOpen,{if(!busy) routesOpen=it}) {
                        OutlinedTextField(route.label,{},Modifier.fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable,enabled=!busy),
                            readOnly=true,enabled=!busy,singleLine=true,shape=shape,textStyle=SettingsType.body,
                            label={Text("Connection network")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(routesOpen)},
                            supportingText={Text(if(route==ServerNetworkRoute.BUILTIN)
                                "Use a tailnet IP or device name." else "Use this phone’s network and existing VPN.",style=SettingsType.detail)})
                        ExposedDropdownMenu(routesOpen,{routesOpen=false}) {
                            ServerNetworkRoute.entries.forEach {choice->
                                DropdownMenuItem(text={Text(choice.label,style=SettingsType.row)},
                                    onClick={route=choice;routesOpen=false;error=null})
                            }
                        }
                    }
                    if(route==ServerNetworkRoute.BUILTIN) TextButton(onClick={networkOpen=true},enabled=!busy,
                        modifier=Modifier.padding(start=6.dp)) {
                        Text(if(tailnetConnected) "Connected" else "Set up / sign in",style=SettingsType.detail)
                    }
                }
                if(ssh) SshKeyEditor(selectedKey,enabled=!busy) {selectedKey=it}
            }
            error?.let {Text(it,color=colors.error,style=SettingsType.detail)}
            if(onRemove!=null) {
                HorizontalDivider(color=colors.outlineVariant.copy(alpha=.55f))
                TextButton(onClick={confirmRemove=true},enabled=!busy,colors=ButtonDefaults.textButtonColors(contentColor=colors.error)) {
                    ChromeSymbol(ChromeIcon.DELETE,Modifier.size(18.dp),LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text("Remove server",style=SettingsType.row)
                }
            }
        }
    }
    if(confirmRemove && onRemove!=null && server!=null) {
        ServerConfirmationDialog(server,ServerConfirmation.REMOVE,onCancel={confirmRemove=false},
            onConfirm={confirmRemove=false;onRemove();onClose()})
    }
}

@Composable private fun ServerUrlStatus(status: ServerUrlCheck) {
    val colors=MaterialTheme.colorScheme
    val green=if(LocalTerminalPalette.current.dark) Color(0xff73cd87) else Color(0xff217638)
    when(status) {
        ServerUrlCheck.Editing -> Text("HTTPS address or ssh://user@host",style=SettingsType.detail)
        else -> Row(Modifier.padding(top=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            when(status) {
                ServerUrlCheck.Checking -> {
                    CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp,color=colors.onSurfaceVariant)
                    Text("Checking…",style=SettingsType.detail,color=colors.onSurfaceVariant)
                }
                is ServerUrlCheck.Reachable -> {
                    Box(Modifier.size(16.dp).border(1.dp,green,CircleShape),contentAlignment=Alignment.Center) {
                        ChromeSymbol(ChromeIcon.CHECK,Modifier.size(10.dp),green)
                    }
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color=green)) {append("Reachable")}
                        if(status.version.isNotBlank()) withStyle(SpanStyle(color=colors.onSurfaceVariant)) {
                            append(" · Rex ${status.version}")
                        }
                    },style=SettingsType.detail)
                }
                is ServerUrlCheck.Error -> {
                    Box(Modifier.size(14.dp).border(1.dp,colors.error,CircleShape),contentAlignment=Alignment.Center) {
                        Text("!",style=SettingsType.paneDetail,color=colors.error)
                    }
                    Text("Can’t reach server · ${status.reason}",style=SettingsType.detail,color=colors.error)
                }
                ServerUrlCheck.Editing -> Unit
            }
        }
    }
}

private enum class ServerConfirmation(val label: String) { DISCONNECT("Disconnect"), REMOVE("Remove") }

@Composable private fun ServerConfirmationDialog(
    server: ServerProfile,action: ServerConfirmation,onCancel: ()->Unit,onConfirm: ()->Unit,
) {
    AlertDialog(onDismissRequest=onCancel,title={Text("${action.label} server?")},
        text={Text(when(action) {
            ServerConfirmation.DISCONNECT -> "Disconnect from “${server.label}” on this phone?"
            ServerConfirmation.REMOVE -> "Remove “${server.label}” from this phone?"
        })},
        confirmButton={TextButton(onClick=onConfirm) {
            Text(action.label,color=if(action==ServerConfirmation.REMOVE) MaterialTheme.colorScheme.error else LocalContentColor.current)
        }},
        dismissButton={TextButton(onClick=onCancel) {Text("Cancel")}})
}
