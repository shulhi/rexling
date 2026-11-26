package dev.shulhi.rex

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.HazeState

private class DrawerPull(val start: ()->Unit,val move: (Float)->Unit,val finish: (Boolean)->Unit)
private val LocalDrawerPull=staticCompositionLocalOf<DrawerPull?> {null}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DrawerSheet(onClose: ()->Unit,animated: Boolean=true,dismissEnabled: Boolean=true,content: @Composable ColumnScope.(() -> Unit)->Unit) {
    val close by rememberUpdatedState(onClose)
    var closing by remember {mutableStateOf(false)}
    var entered by remember {mutableStateOf(!animated)}
    var returning by remember {mutableStateOf(false)}
    var dragOffset by remember {mutableFloatStateOf(0f)}
    var drawerHeight by remember {mutableIntStateOf(0)}
    val progress=remember {Animatable(if(animated) 0f else 1f)}
    LaunchedEffect(closing) {
        if(animated || closing) progress.animateTo(if(closing) 0f else 1f,tween(if(closing) 160 else 220))
        if(closing) close() else entered=true
    }
    LaunchedEffect(returning) {
        if(returning) {
            animate(dragOffset,0f,animationSpec=spring(dampingRatio=1f,stiffness=650f)) {value,_->
                if(returning) dragOffset=value
            }
            returning=false
        }
    }
    val dismiss: ()->Unit={if(dismissEnabled) {returning=false;closing=true}}
    val threshold=with(LocalDensity.current) {52.dp.toPx()}
    val pull=DrawerPull(
        start={returning=false},
        move={delta->if(dismissEnabled && entered && !closing) dragOffset=(dragOffset+delta).coerceIn(0f,drawerHeight.toFloat())},
        finish={released->
            if(released && dragOffset>=minOf(threshold,drawerHeight*.3f) && dragOffset>0f) dismiss()
            else if(!closing && dragOffset>0f) returning=true
        },
    )
    val scrim=BottomSheetDefaults.ScrimColor
    val immersive=LocalImmersiveStatusBar.current
    Dialog(onDismissRequest=onClose,properties=DialogProperties(
        dismissOnBackPress=false,dismissOnClickOutside=false,usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val window=(LocalView.current.parent as? DialogWindowProvider)?.window
        val light=MaterialTheme.colorScheme.surface.luminance()>.5f
        SideEffect {
            window?.let {
                it.setDimAmount(0f)
                WindowCompat.getInsetsController(it,it.decorView).apply {
                    isAppearanceLightStatusBars=light
                    isAppearanceLightNavigationBars=light
                }
            }
        }
        LaunchedEffect(window,immersive) {window?.applyImmersiveStatusBar(immersive)}
        Box(Modifier.fillMaxSize().drawBehind {
            val shown=(1f-dragOffset/drawerHeight.coerceAtLeast(1)).coerceIn(0f,1f)
            drawRect(scrim.copy(alpha=scrim.alpha*progress.value*shown))
        }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).imePadding()) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface,LocalDrawerPull provides pull) {
                Column(Modifier.align(Alignment.BottomCenter).widthIn(max=BottomSheetDefaults.SheetMaxWidth).fillMaxWidth()
                    .onSizeChanged {drawerHeight=it.height}
                    .graphicsLayer {translationY=size.height-progress.value*(size.height-dragOffset.coerceAtMost(size.height))}) {
                    content(dismiss)
                }
            }
        }
    }
}

@Composable internal fun DrawerHandle(onDismiss: ()->Unit) {
    val dismissDrawer by rememberUpdatedState(onDismiss)
    val pull by rememberUpdatedState(LocalDrawerPull.current)
    Box(Modifier.fillMaxWidth().height(18.dp),contentAlignment=Alignment.Center) {
        Box(Modifier.width(80.dp).fillMaxHeight().semantics {
            contentDescription="Drawer handle"
            dismiss {dismissDrawer();true}
        }.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart={pull?.start?.invoke()},
                onDragEnd={pull?.finish?.invoke(true)},
                onDragCancel={pull?.finish?.invoke(false)},
                onVerticalDrag={change,amount->change.consume();pull?.move?.invoke(amount)},
            )
        },contentAlignment=Alignment.Center) {
            Box(Modifier.width(28.dp).height(3.dp).background(MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(3.dp)))
        }
    }
}

/** Shared drawer chrome; scrolling stays in the content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsDrawer(title: String,closeDescription: String,onClose: ()->Unit,
    hazeState: HazeState?=null,onBack: (() -> Unit)?=null,fixed: Boolean=false,backDescription: String="Back to appearance",
    subtitle: String?=null,surfaceColor: Color=Color.Transparent,showClose: Boolean=true,dismissEnabled: Boolean=true,headerActions: @Composable RowScope.()->Unit={},content: @Composable ColumnScope.()->Unit) {
    val localHaze=remember {HazeState()}
    val shape=BottomSheetDefaults.ExpandedShape
    // Built outside the sheet on purpose: drawerFrost redraws when its host window draws, and inside the
    // sheet that host is the sheet's own window, which then redraws itself every frame while open.
    val frost=if(!fixed) Modifier.drawerFrost(hazeState,shape)
        else Modifier.paneFrost(hazeState ?: localHaze,shape,edge=false,opacity=.72f,blur=24.dp)
    val surface=if(surfaceColor==Color.Transparent) frost else Modifier.background(surfaceColor,shape).then(frost)
    DrawerSheet(onClose,animated=!fixed,dismissEnabled=dismissEnabled) {dismiss->
        Column(surface.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
            DrawerHandle(dismiss)
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                onBack?.let {ChromeButton(ChromeIcon.LEFT,backDescription,enabled=dismissEnabled,onClick=it)}
                Column(Modifier.weight(1f)) {
                    if(subtitle==null) Text(title,style=SettingsType.row,fontWeight=FontWeight.Bold)
                    else {
                        Text(title,style=SettingsType.row,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(subtitle,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
                headerActions()
                if(showClose) ChromeButton(ChromeIcon.CLOSE,closeDescription,enabled=dismissEnabled,onClick=dismiss)
            }
            content()
        }
    }
}
