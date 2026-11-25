package dev.shulhi.rex

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PreferencesSheet(onAppearance: ()->Unit,onFont: ()->Unit,onAlerts: ()->Unit,onAbout: ()->Unit,onClose: ()->Unit,
    shortcutGesture: ControlShortcutGesture=ControlShortcutGesture.HOLD,onShortcutGesture: (ControlShortcutGesture)->Unit={},onNeovim: ()->Unit={},
    arrowJoystickEnabled: Boolean=true,onArrowJoystick: (Boolean)->Unit={},enterSubmits: Boolean=false,onEnterSubmits: (Boolean)->Unit={},toolbarWithKeyboardOnly: Boolean=false,onToolbarWithKeyboardOnly: (Boolean)->Unit={},tabLayouts: Map<TabWidthClass,TabBarLayout> = emptyMap(),tabWidth: Pair<TabWidthClass,Int> = TabWidthClass.NARROW to 0,onTabLayout: (TabWidthClass,TabBarLayout)->Unit={_,_->},codexTitleAttention: Boolean=false,onCodexTitleAttention: (Boolean)->Unit={},
    paneLayouts: Map<PaneOrientation,Boolean> = emptyMap(),paneOrientation: PaneOrientation=currentPaneOrientation(),
    onPaneLayout: (PaneOrientation,Boolean)->Unit={_,_->},
    minimizeFloatingPanes: Boolean=false,onMinimizeFloatingPanes: (Boolean)->Unit={},onNetwork: ()->Unit={},
    keepScreenAwake: KeepScreenAwake=KeepScreenAwake.SYSTEM,onKeepScreenAwake: (KeepScreenAwake)->Unit={},onToolbar: ()->Unit={},onControlItems: ()->Unit={},onSshKeys: ()->Unit={},hazeState: dev.chrisbanes.haze.HazeState?=null,
    compactHeaderMode: CompactHeaderMode=CompactHeaderMode.NEVER,onCompactHeaderMode: (CompactHeaderMode)->Unit={},
    immersiveStatusBar: Boolean=false,onImmersiveStatusBar: (Boolean)->Unit={},
    toolbarSystemColors: Boolean=false,onToolbarSystemColors: (Boolean)->Unit={}) {
    // Built outside the sheet on purpose: the frost redraws when its host window draws, and inside the
    // sheet that host is the sheet's own window, which then redraws itself every frame while open.
    val frost=Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape)
    DrawerSheet(onClose) {dismiss->
        Column(frost.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
            DrawerHandle(dismiss)
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Preferences",Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold)
                ChromeButton(ChromeIcon.CLOSE,"Close preferences",onClick=dismiss)
            }
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(top=8.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SettingsSection("Display",compact=true) {
                SettingsRow("Appearance","Themes and light or dark mode",SettingsGlyph.APPEARANCE,onAppearance)
                SettingsDivider()
                SettingsRow("Font size","Terminal text size by window width",SettingsGlyph.FONT,onFont)
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Compact top bar",style=SettingsType.paneTitle)
                    PreferenceSegments(CompactHeaderMode.entries,compactHeaderMode,{it.label},showCheck=true,onSelect=onCompactHeaderMode)
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(immersiveStatusBar,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onImmersiveStatusBar)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Immersive mode",style=SettingsType.row)
                        Text("Hide the Android status bar. Swipe down from the top to show it briefly.",
                            style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=immersiveStatusBar,onCheckedChange=null)
                }
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp)) {
                    Text("Keep screen awake",style=SettingsType.row)
                    Text("Applies while Rexling is in the foreground.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    KeepScreenAwake.entries.forEach {mode->
                        Row(Modifier.fillMaxWidth().selectable(mode==keepScreenAwake,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={onKeepScreenAwake(mode)}).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=mode==keepScreenAwake,onClick=null)
                            Column(Modifier.padding(start=8.dp)) {
                                Text(mode.label,style=SettingsType.row)
                                Text(mode.detail,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            TabLayoutPreferences(tabLayouts,tabWidth,onTabLayout)
            PaneLayoutPreferences(paneLayouts,paneOrientation,onPaneLayout,minimizeFloatingPanes,onMinimizeFloatingPanes)
            SettingsSection("Keyboard",compact=true) {
                SettingsRow("Keyboard toolbar","Choose and arrange up to seven buttons",SettingsGlyph.COMMAND,onToolbar)
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(toolbarWithKeyboardOnly,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onToolbarWithKeyboardOnly)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Show toolbar only with keyboard",style=SettingsType.row)
                        Text("Hide the key row when the keyboard closes.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=toolbarWithKeyboardOnly,onCheckedChange=null)
                }
                if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) {
                    SettingsDivider()
                    Row(Modifier.fillMaxWidth().toggleable(toolbarSystemColors,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onToolbarSystemColors)
                        .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text("Use system colors for toolbar",style=SettingsType.row)
                            Text("Fade to Android’s light or dark palette when the keyboard opens.",
                                style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked=toolbarSystemColors,onCheckedChange=null)
                    }
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(arrowJoystickEnabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onArrowJoystick)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Use joystick for arrows",style=SettingsType.row)
                        Text(if(arrowJoystickEnabled) "Hold and slide; double-tap for arrow buttons" else "Tap for arrow buttons",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=arrowJoystickEnabled,onCheckedChange=null)
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(enterSubmits,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onEnterSubmits)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Keyboard Enter sends",style=SettingsType.row)
                        Text(if(enterSubmits) "Submits, like a hardware Enter. Hold toolbar Shift for a newline." else "A newline. Programs such as Claude Code insert a line instead of submitting.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=enterSubmits,onCheckedChange=null)
                }
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Ctrl shortcuts",style=SettingsType.paneTitle)
                    Text("Open the ^ shortcut list with",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    PreferenceSegments(ControlShortcutGesture.entries,shortcutGesture,
                        {if(it==ControlShortcutGesture.HOLD) "Hold" else "Double tap"},showCheck=true,onSelect=onShortcutGesture)
                }
                SettingsDivider()
                SettingsRow("Ctrl shortcut buttons","Customize keys and key sequences",SettingsGlyph.COMMAND,onControlItems)
                SettingsDivider()
                SettingsRow("Neovim shortcuts","Customize the floating key menu",SettingsGlyph.COMMAND,onNeovim)
            }
            SettingsSection("Connections",compact=true) {
                SettingsRow("Network","Built-in Tailscale and control server",SettingsGlyph.NETWORK,onNetwork)
                SettingsDivider()
                SettingsRow("SSH keys","Save and reuse private keys across servers",SettingsGlyph.KEY,onSshKeys)
                SettingsDivider()
                SettingsRow("Background alerts","Task completions and attention alerts",SettingsGlyph.ALERTS,onAlerts)
            }
            SettingsSection("Port forwarding",compact=true) {
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)) {
                    Text("When the phone port is in use",style=SettingsType.row)
                    Text("Prefer the remote port number. Only Rexling forwards can be replaced.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    PortConflictPolicy.entries.forEach {choice->
                        Row(Modifier.fillMaxWidth().selectable(PortForwards.policy==choice,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={PortForwards.changePolicy(choice)}).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=PortForwards.policy==choice,onClick=null)
                            Text(choice.label,Modifier.padding(start=8.dp),style=SettingsType.row)
                        }
                    }
                }
            }
            SettingsSection("Experimental",compact=true) {
                Row(Modifier.fillMaxWidth().toggleable(value=codexTitleAttention,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onCodexTitleAttention)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Codex title attention",style=SettingsType.row)
                        Text("Use Codex’s explicit Action Required title when its status stays Working. Depends on Codex’s title format.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=codexTitleAttention,onCheckedChange=null)
                }
            }
            SettingsSection("App",compact=true) {
                SettingsRow("About Rexling","Version, build and project information",SettingsGlyph.INFO,onAbout)
            }
        }
        }
    }
}

@Composable private fun TabLayoutPreferences(layouts: Map<TabWidthClass,TabBarLayout>,window: Pair<TabWidthClass,Int>,
    onChange: (TabWidthClass,TabBarLayout)->Unit) {
    val (current,widthDp)=window
    var editing by rememberSaveable(current) {mutableStateOf<TabWidthClass?>(current)}
    PreferenceProfiles("Tabs by window width","Follows the width available to Rexling, including in split screen.",
        Modifier.testTag("Tab width profiles")) {
        TabWidthClass.entries.forEachIndexed {index,profile->
            if(index>0) ProfileDivider()
            TabLayoutProfile(profile,layouts[profile] ?: TabBarLayout(),profile==current,widthDp,profile==editing,
                onToggle={editing=if(editing==profile) null else profile},onChange={onChange(profile,it)})
        }
    }
}

@Composable private fun PaneLayoutPreferences(layouts: Map<PaneOrientation,Boolean>,current: PaneOrientation,
    onChange: (PaneOrientation,Boolean)->Unit,minimizeFloatingPanes: Boolean,onMinimizeFloatingPanes: (Boolean)->Unit) {
    var editing by rememberSaveable(current) {mutableStateOf<PaneOrientation?>(current)}
    PreferenceProfiles("Panes by orientation","Applies in full screen on an unfolded foldable or tablet.") {
        PaneOrientation.entries.forEachIndexed {index,orientation->
            if(index>0) ProfileDivider()
            val multiple=layouts[orientation]==true
            val expanded=orientation==editing
            PreferenceProfile(orientation.title,paneLayoutLabel(multiple),orientation==current,expanded,
                onToggle={editing=if(expanded) null else orientation},icon={PaneOrientationGlyph(orientation,expanded)}) {
                PreferenceSegments(listOf(false,true),multiple,::paneLayoutLabel,
                    showCheck=true,onSelect={onChange(orientation,it)})
            }
        }
        ProfileDivider()
        Row(Modifier.fillMaxWidth().toggleable(minimizeFloatingPanes,role=Role.Switch,onValueChange=onMinimizeFloatingPanes)
            .padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text("Minimize floating panes",style=SettingsType.paneTitle)
                Text("Show floating panes in a corner dock. Swipe between tiled panes.",style=SettingsType.detail,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=minimizeFloatingPanes,onCheckedChange=null)
        }
    }
}

private fun paneLayoutLabel(multiple: Boolean)=if(multiple) "Multiple panes" else "Single pane"

@Composable private fun PreferenceProfiles(title: String,description: String,modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    val colors=MaterialTheme.colorScheme
    val border=colors.outlineVariant.copy(alpha=.65f)
    Surface(modifier.fillMaxWidth(),
        shape=RoundedCornerShape(16.dp),color=colors.surface,border=BorderStroke(.5.dp,border)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.padding(horizontal=4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(title,style=SettingsType.paneTitle,color=colors.primary)
                Text(description,style=SettingsType.detail,color=colors.onSurfaceVariant)
            }
            Surface(shape=RoundedCornerShape(12.dp),color=colors.surfaceVariant.copy(alpha=.25f),border=BorderStroke(.5.dp,border)) {
                Column(Modifier.fillMaxWidth(),content=content)
            }
        }
    }
}

@Composable private fun ProfileDivider() {
    HorizontalDivider(thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f))
}

@Composable private fun TabLayoutProfile(profile: TabWidthClass,layout: TabBarLayout,current: Boolean,widthDp: Int,
    expanded: Boolean,onToggle: ()->Unit,onChange: (TabBarLayout)->Unit) {
    val description=when(profile) {
        TabWidthClass.NARROW->"Phones and small split-screen windows"
        TabWidthClass.MEDIUM->"Folded foldables and larger split-screen windows"
        TabWidthClass.WIDE->"Tablets and unfolded foldables"
    }
    val summary=if(layout.side) "${layout.position.label} tabs · ${layout.style.label.lowercase()}" else "Bottom tabs"
    PreferenceProfile(profile.title,summary,current,expanded,onToggle,description,icon={TabWidthGlyph(profile,expanded)}) {
        Text("Position",style=SettingsType.paneTitle)
        PreferenceSegments(TabBarPosition.entries,layout.position,{it.label}) {onChange(layout.copy(position=it))}
        if(layout.side) {
            Spacer(Modifier.height(2.dp))
            Text("Sidebar style",style=SettingsType.paneTitle)
            PreferenceSegments(TabRailStyle.entries,layout.style,{it.label}) {onChange(layout.copy(style=it))}
        }
        Text(if(current && widthDp>0) "${profile.range} · This window is $widthDp dp" else profile.range,
            style=SettingsType.paneDetail,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun PreferenceProfile(title: String,summary: String,current: Boolean,expanded: Boolean,
    onToggle: ()->Unit,description: String?=null,icon: @Composable ()->Unit,content: @Composable ColumnScope.()->Unit) {
    val colors=MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(if(expanded) colors.surface else Color.Transparent)) {
        Row(Modifier.fillMaxWidth().clickable(role=Role.Button,onClick=onToggle)
            .semantics {stateDescription=if(expanded) "Expanded" else "Collapsed"}.padding(horizontal=12.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            icon()
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(title,style=SettingsType.paneTitle)
                    if(current) Surface(shape=RoundedCornerShape(6.dp),color=colors.primaryContainer,contentColor=colors.onPrimaryContainer) {
                        Text("Now",Modifier.padding(horizontal=6.dp,vertical=1.dp),style=SettingsType.paneDetail)
                    }
                }
                description?.let {Text(it,style=SettingsType.detail,color=colors.onSurfaceVariant)}
                if(!expanded) Text(summary,style=SettingsType.paneDetail,color=colors.onSurfaceVariant)
            }
            ChromeSymbol(ChromeIcon.CHEVRON,Modifier.size(16.dp).rotate(if(expanded) 180f else 0f),colors.onSurfaceVariant)
        }
        if(expanded) Column(Modifier.fillMaxWidth().padding(start=62.dp,end=12.dp,bottom=12.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp),content=content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun <T> PreferenceSegments(options: List<T>,selected: T,label: (T)->String,showCheck: Boolean=false,onSelect: (T)->Unit) {
    val colors=MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed {index,option->
            val active=option==selected
            SegmentedButton(selected=active,onClick={onSelect(option)},
                shape=SegmentedButtonDefaults.itemShape(index,options.size),icon={if(showCheck) SegmentedButtonDefaults.Icon(active)},
                colors=SegmentedButtonDefaults.colors(activeContainerColor=colors.primaryContainer,activeContentColor=colors.onPrimaryContainer)) {
                Text(label(option),style=SettingsType.paneDetail,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign=TextAlign.Center,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun TabWidthGlyph(width: TabWidthClass,active: Boolean) {
    val colors=MaterialTheme.colorScheme
    val strokeColor=if(active) colors.primary else colors.onSurfaceVariant
    Canvas(Modifier.size(40.dp,32.dp)) {
        val deviceWidth=when(width) {
            TabWidthClass.NARROW->12.dp
            TabWidthClass.MEDIUM->20.dp
            TabWidthClass.WIDE->34.dp
        }.toPx()
        val height=26.dp.toPx()
        val corner=CornerRadius(3.5.dp.toPx())
        val stroke=1.5.dp.toPx()
        val origin=Offset((size.width-deviceWidth)/2,(size.height-height)/2)
        val device=Size(deviceWidth,height)
        if(active) drawRoundRect(colors.primaryContainer,origin,device,corner)
        drawRoundRect(strokeColor,origin,device,corner,style=Stroke(stroke))
        if(width!=TabWidthClass.NARROW) {
            val hinge=if(width==TabWidthClass.WIDE) center.x else origin.x+4.dp.toPx()
            val inset=if(width==TabWidthClass.WIDE) 0.dp else 3.dp
            drawLine(strokeColor,Offset(hinge,origin.y+inset.toPx()),Offset(hinge,origin.y+height-inset.toPx()),stroke)
        }
    }
}

@Composable private fun PaneOrientationGlyph(orientation: PaneOrientation,active: Boolean) {
    val colors=MaterialTheme.colorScheme
    Canvas(Modifier.size(40.dp,32.dp)) {
        val portrait=orientation==PaneOrientation.PORTRAIT
        val device=if(portrait) Size(18.dp.toPx(),28.dp.toPx()) else Size(28.dp.toPx(),18.dp.toPx())
        val origin=Offset((size.width-device.width)/2,(size.height-device.height)/2)
        val corner=CornerRadius(3.5.dp.toPx())
        if(active) drawRoundRect(colors.primaryContainer,origin,device,corner)
        drawRoundRect(if(active) colors.primary else colors.onSurfaceVariant,origin,device,corner,style=Stroke(1.5.dp.toPx()))
    }
}
