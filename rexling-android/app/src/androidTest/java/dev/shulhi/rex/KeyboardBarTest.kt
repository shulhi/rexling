package dev.shulhi.rex

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardBarTest {
    @get:Rule val ui=createComposeRule()
    private val sent=mutableListOf<String>()
    private var keyboardToggles=0
    private fun keyboard(enabled: Boolean=true,width: Dp=360.dp,ready: Boolean=enabled,controlled: ()->Boolean={enabled}) {
        ui.setContent {
            var shift by remember {mutableStateOf(ShiftKeyState())}
            MaterialTheme {
                Box(Modifier.width(width).statusBarsPadding().testTag("Keyboard test area")) {
                    KeyboardBar(controlled(),ready,false,false,shift.active,true,{},{},
                        {shift=ShiftKeyState(latched=!shift.active)},
                        {shift=shift.press()},{shift=shift.release(it)},
                        {sent+=TerminalInput.key(it,shift=shift.active);shift=shift.consume()},onKeyboard={keyboardToggles++})
                }
            }
        }
    }
    @Test fun holdingKeyboardPastesOnceWithoutTogglingAndTapStillToggles() {
        var pasted=0;var toggles=0
        val controlled=mutableStateOf(true)
        ui.setContent {MaterialTheme {
            KeyboardBar(controlled.value,true,false,false,false,false,{},{},{},{},{},{},onPaste={pasted++},onKeyboard={toggles++})
        }}
        ui.onNodeWithContentDescription("Show keyboard").performTouchInput {longClick()}
        ui.runOnIdle {assertEquals(1,pasted);assertEquals(0,toggles)}
        ui.onNodeWithContentDescription("Show keyboard").performClick()
        ui.runOnIdle {assertEquals(1,toggles);controlled.value=false}
        ui.onNodeWithContentDescription("Take control and show keyboard").assert(SemanticsMatcher("no paste while viewing") {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        })
    }
    @Test fun shiftTapAppliesToOneArrowAndShiftTab() {
        keyboard()
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").assertIsSelected()
        joystick("left")
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        joystick("right")
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        ui.onNodeWithContentDescription("Tab key").performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("\u001b[1;2D","\u001b[C","\u001b[Z"),sent)}
    }
    @Test fun holdingShiftWhileSteeringTheJoystickModifiesEveryRepeat() {
        keyboard();ui.mainClock.autoAdvance=false
        val root=ui.onNodeWithTag("Keyboard test area")
        val origin=root.fetchSemanticsNode().boundsInRoot.topLeft
        val shift=ui.onNodeWithContentDescription("Shift").fetchSemanticsNode().boundsInRoot.center-origin
        val arrow=ui.onNodeWithContentDescription("Arrow keys").fetchSemanticsNode().boundsInRoot.center-origin
        root.performTouchInput {down(0,shift);down(1,arrow);moveTo(1,arrow+androidx.compose.ui.geometry.Offset(-40f,0f))}
        ui.mainClock.advanceTimeBy(900)
        ui.runOnIdle {assertTrue(sent.size>=3);assertTrue(sent.all {it=="\u001b[1;2D"})}
        root.performTouchInput {up(1);up(0)};ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
    }
    @Test fun nativeHoldSlideRepeatsAndReleaseDoesNotActivateANeighboringButton() {
        keyboard()
        val bounds=nativeBounds("Arrow keys");val down=SystemClock.uptimeMillis();ui.mainClock.autoAdvance=false
        fun send(action: Int,x: Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,bounds.exactCenterY(),0)
            InstrumentationRegistry.getInstrumentation().sendPointerSync(event);event.recycle()
            repeat(3) {ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
        }
        send(MotionEvent.ACTION_DOWN,bounds.exactCenterX())
        ui.onNodeWithContentDescription("Arrow joystick").assertIsDisplayed()
        send(MotionEvent.ACTION_MOVE,bounds.exactCenterX()+120f)
        ui.mainClock.advanceTimeBy(900)
        ui.runOnIdle {assertTrue(sent.size>=3);assertTrue(sent.all {it=="\u001b[C"})}
        send(MotionEvent.ACTION_UP,bounds.exactCenterX()+120f)
        val stopped=sent.size;ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle {assertEquals(stopped,sent.size);assertEquals(0,keyboardToggles)}
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
    }
    @Test fun joystickPreviewDoesNotMoveTheDockAndUsesOneShotShift() {
        keyboard()
        val tabBefore=ui.onNodeWithContentDescription("Tab key").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        val arrow=ui.onNodeWithContentDescription("Arrow keys")
        arrow.performTouchInput {down(center);moveTo(center+androidx.compose.ui.geometry.Offset(40f,0f))}
        ui.onNodeWithContentDescription("Arrow joystick").assertIsDisplayed()
        assertEquals(tabBefore,ui.onNodeWithContentDescription("Tab key").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        arrow.performTouchInput {moveTo(center+androidx.compose.ui.geometry.Offset(0f,-40f));up()}
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        ui.runOnIdle {assertEquals(listOf("\u001b[1;2C","\u001b[A"),sent)}
    }
    @Test fun joystickTicksOnDirectionChangesWithoutBuzzingDuringRepeat() {
        val feedback=mutableListOf<HapticFeedbackType>()
        val haptics=object: HapticFeedback {override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {feedback+=hapticFeedbackType}}
        ui.setContent {CompositionLocalProvider(LocalHapticFeedback provides haptics) {MaterialTheme {Box(Modifier.width(360.dp)) {
            KeyboardBar(true,true,false,false,false,true,{},{},{},{},{},{sent+=it},onKeyboard={})
        }}}}
        ui.waitForIdle();ui.mainClock.autoAdvance=false
        val arrow=ui.onNodeWithContentDescription("Arrow keys")
        arrow.performTouchInput {down(center);moveTo(center+androidx.compose.ui.geometry.Offset(40f,0f))}
        ui.mainClock.advanceTimeBy(900)
        ui.runOnIdle {assertTrue(sent.size>=3);assertEquals(listOf(HapticFeedbackType.SegmentTick),feedback)}
        arrow.performTouchInput {moveTo(center+androidx.compose.ui.geometry.Offset(41f,0f))}
        ui.runOnIdle {assertEquals(1,feedback.size)}
        arrow.performTouchInput {moveTo(center+androidx.compose.ui.geometry.Offset(0f,-40f))}
        ui.runOnIdle {assertEquals(listOf(HapticFeedbackType.SegmentTick,HapticFeedbackType.SegmentTick),feedback)}
        arrow.performTouchInput {moveTo(center)}
        val count=sent.size;ui.mainClock.advanceTimeBy(700)
        ui.runOnIdle {assertEquals(count,sent.size);assertEquals(2,feedback.size)}
        arrow.performTouchInput {moveTo(center+androidx.compose.ui.geometry.Offset(40f,0f));up()}
        ui.mainClock.advanceTimeBy(700)
        ui.runOnIdle {assertEquals(count+1,sent.size);assertEquals(3,feedback.size)}
    }
    @Test fun cancelClearsHeldShiftAndDoesNotSendAnArrow() {
        keyboard()
        ui.onNodeWithContentDescription("Shift").performTouchInput {down(center);cancel()}
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {down(center);cancel()}
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun arrowsRemainIndividuallyAccessibleAndDisabledControlsDoNotSend() {
        keyboard(enabled=false)
        ui.onNodeWithContentDescription("Shift").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Arrow keys").assertIsNotEnabled().performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        ui.onNodeWithContentDescription("Enter").assertIsNotEnabled().performTouchInput {click()}
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun narrowPhoneKeepsEveryControlOnOneRowAndModifiersFullyVisible() {
        keyboard(width=320.dp)
        val labels=listOf("Escape","Control","Alt","Shift","Tab key","Arrow keys","Enter","Hide keyboard")
        val bounds=labels.map {label->
            ui.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        bounds.forEach {assertEquals(bounds.first().center.y,it.center.y,1f)}
        bounds.zipWithNext().forEach {(a,b)->assertTrue("Groups and buttons must not overlap",a.right<=b.left)}
        assertTrue("Reclaimed chevron space must keep keys at least 34 dp wide",bounds[5].width>=with(ui.density) {34.dp.toPx()}-1f)
        val widths=bounds.take(7).map {it.width}
        assertTrue("No key should lose pixels to clipping",widths.max()-widths.min()<=1f)
        ui.onNodeWithContentDescription("Show extra keys").assertIsNotDisplayed()
    }

    @Test fun controlRowKeepsAllKeysWhenTheKeyboardToggleLivesInTheSidebar() {
        ui.setContent {MaterialTheme {
            KeyboardBar(true,true,false,false,false,true,{},{},{},{},{},{sent+=it},onKeyboard=null)
        }}
        ui.onNodeWithContentDescription("Hide keyboard").assertDoesNotExist()
        for(label in listOf("Escape","Control","Alt","Shift","Tab key","Arrow keys","Enter")) {
            ui.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        ui.onNodeWithContentDescription("Enter").performClick()
        ui.runOnIdle {assertEquals(listOf("enter"),sent)}
    }

    @Test fun viewingConnectedTerminalKeepsIconsCrispWithoutEnablingTyping() {
        val controlled=mutableStateOf(true)
        keyboard(ready=true,controlled={controlled.value})
        val owning=ui.onRoot().captureToImage().toPixelMap()
        ui.runOnIdle {controlled.value=false}
        val viewing=ui.onRoot().captureToImage().toPixelMap()
        assertEquals(owning.width,viewing.width);assertEquals(owning.height,viewing.height)
        for(y in 0 until owning.height) for(x in 0 until owning.width)
            assertEquals("Read-only mode must preserve icon contrast at $x,$y",owning[x,y],viewing[x,y])
        for(label in listOf("Escape","Control","Alt","Shift","Tab key","Arrow keys","Enter"))
            ui.onNodeWithContentDescription(label).assertIsNotEnabled().performTouchInput {click()}
        ui.runOnIdle {assertTrue("Readable icons must not enable terminal input",sent.isEmpty())}
    }

    @Test fun navigationPendingKeepsTheEntireKeyRowCrispWhileInputIsDisabled() {
        val ready=mutableStateOf(true)
        val showTopDivider=mutableStateOf(true)
        ui.setContent {MaterialTheme {Box(Modifier.width(360.dp).testTag("Keyboard test area")) {
            KeyboardBar(ready.value,ready.value,false,false,false,true,{},{},{},{},{},
                {sent+=it},readable=true,showTopDivider=showTopDivider.value,onKeyboard={})
        }}}
        val before=ui.onNodeWithTag("Keyboard test area").captureToImage().toPixelMap()
        assertEquals("The toolbar divider should reach the left edge",before[before.width/2,0],before[0,0])
        assertEquals("The toolbar divider should reach the right edge",before[before.width/2,0],before[before.width-1,0])
        assertNotEquals("The divider should be distinct from the inset beneath it",before[0,0],before[0,before.height/2])
        ui.runOnIdle {ready.value=false}
        val pending=ui.onNodeWithTag("Keyboard test area").captureToImage().toPixelMap()
        for(y in 0 until before.height) for(x in 0 until before.width)
            assertEquals("Navigation must not fade the row at $x,$y",before[x,y],pending[x,y])
        ui.onNodeWithContentDescription("Escape").assertIsNotEnabled().performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").assertIsNotEnabled()
        ui.runOnIdle {assertTrue(sent.isEmpty())}
        ui.runOnIdle {ready.value=true}
        ui.onNodeWithContentDescription("Escape").assertIsEnabled()
        ui.runOnIdle {showTopDivider.value=false}
        val seamless=ui.onNodeWithTag("Keyboard test area").captureToImage().toPixelMap()
        assertEquals(before.height,seamless.height)
        for(x in 0 until seamless.width) assertEquals(seamless[0,seamless.height/2],seamless[x,0])
    }

    @Test fun toolbarColorTracksTheKeyboardInsetsInBothDirections() {
        assumeTrue(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        var enabled by mutableStateOf(false)
        var keyboardBottom by mutableIntStateOf(0)
        var keyboardSource by mutableIntStateOf(0)
        var keyboardTarget by mutableIntStateOf(0)
        var dark by mutableStateOf(false)
        val appSurface=Color.Magenta
        ui.setContent {
            val configuration=Configuration(LocalConfiguration.current).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if(dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme(colorScheme=lightColorScheme(surface=appSurface)) {
                    Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                        Spacer(Modifier.size(12.dp).testTag("Outside keyboard toolbar"))
                        Box(Modifier.testTag("Keyboard toolbar").consumeWindowInsets(WindowInsets(bottom=keyboardBottom))) {
                            KeyboardToolbarTheme(enabled,coverNavigation=true,ime=WindowInsets(bottom=keyboardBottom),
                                source=WindowInsets(bottom=keyboardSource),target=WindowInsets(bottom=keyboardTarget),
                                navigation=WindowInsets(bottom=24)) {
                                KeyboardBar(true,true,false,false,false,keyboardBottom>0,{},{},{},{},{},{},
                                    showTopDivider=false,onKeyboard={})
                            }
                        }
                        Spacer(Modifier.size(12.dp).testTag("Below keyboard toolbar"))
                    }
                }
            }
        }
        fun background(tag: String)=ui.onNodeWithTag(tag).captureToImage().toPixelMap()[0,0]
        fun toolbarPixels()=ui.onNodeWithTag("Keyboard toolbar").captureToImage().toPixelMap()
        fun toolbarColor()=toolbarPixels()[0,0]
        ui.runOnIdle {enabled=true;keyboardTarget=320}
        assertEquals(appSurface,toolbarColor())
        val light=dynamicLightColorScheme(context).surfaceContainer
        val night=dynamicDarkColorScheme(context).surfaceContainer
        val opening=listOf(72,160,248).map {inset->
            ui.runOnIdle {keyboardBottom=inset}
            val pixels=toolbarPixels()
            assertEquals(pixels[0,0],pixels[0,pixels.height-1])
            pixels[0,0]
        }
        assertEquals(opening.size,opening.distinct().size)
        assertTrue(opening.none {it==appSurface || it==light})
        ui.runOnIdle {keyboardBottom=320}
        assertEquals(light,toolbarColor())
        ui.runOnIdle {dark=true;keyboardSource=320;keyboardTarget=380}
        assertEquals(night,toolbarColor())
        ui.runOnIdle {keyboardBottom=380}
        assertEquals(night,toolbarColor())
        val openHeight=ui.onNodeWithTag("Keyboard toolbar").captureToImage().height
        assertEquals(appSurface,background("Outside keyboard toolbar"))
        assertEquals(appSurface,background("Below keyboard toolbar"))
        ui.runOnIdle {keyboardSource=380;keyboardTarget=0}
        assertEquals(night,toolbarColor())
        ui.runOnIdle {keyboardBottom=195}
        val closing=toolbarColor()
        assertNotEquals(night,closing)
        assertNotEquals(appSurface,closing)
        ui.runOnIdle {keyboardBottom=12}
        val handoff=toolbarPixels()
        assertEquals(openHeight+12,handoff.height)
        assertEquals(appSurface,handoff[0,0])
        assertEquals(handoff[0,0],handoff[0,handoff.height-1])
        ui.runOnIdle {keyboardBottom=0}
        val closed=toolbarPixels()
        assertEquals(openHeight+24,closed.height)
        assertEquals(appSurface,closed[0,closed.height-1])
        assertEquals(appSurface,toolbarColor())
        ui.runOnIdle {enabled=false;keyboardBottom=320;keyboardTarget=320}
        assertEquals(appSurface,toolbarColor())
    }

    @Test fun swipeRightRevealsChevronBeforeEscapeAndClosingRestoresTheRow() {
        keyboard()
        val escapeBefore=ui.onNodeWithContentDescription("Escape").fetchSemanticsNode().boundsInRoot
        val keyboardBefore=ui.onNodeWithContentDescription("Hide keyboard").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("Enter").performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("\r"),sent)}
        ui.onNodeWithContentDescription("Show extra keys").assertIsNotDisplayed()
        ui.onNodeWithContentDescription("Function key F1").assertDoesNotExist()
        ui.onNodeWithContentDescription("Terminal keys").performTouchInput {swipeRight()}
        ui.onNodeWithContentDescription("Show extra keys").assertIsDisplayed().performTouchInput {click()}
        ui.onNodeWithContentDescription("Function key F1").assertIsDisplayed()
        ui.onNodeWithContentDescription("Function key F12").assertIsDisplayed()
        val chevron=ui.onNodeWithContentDescription("Hide extra keys").fetchSemanticsNode().boundsInRoot
        val escape=ui.onNodeWithContentDescription("Escape").fetchSemanticsNode().boundsInRoot
        assertTrue(chevron.right<escape.left)
        assertEquals(keyboardBefore,ui.onNodeWithContentDescription("Hide keyboard").fetchSemanticsNode().boundsInRoot)
        capture("hidden-chevron-revealed",ui.onNodeWithTag("Keyboard test area"))
        ui.onNodeWithContentDescription("Hide extra keys").performTouchInput {click()}
        ui.onNodeWithContentDescription("Function key F1").assertDoesNotExist()
        ui.onNodeWithContentDescription("Show extra keys").assertIsNotDisplayed()
        assertEquals(escapeBefore,ui.onNodeWithContentDescription("Escape").fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {assertEquals(listOf("\r"),sent);assertEquals(0,keyboardToggles)}
        capture("hidden-chevron-default",ui.onNodeWithTag("Keyboard test area"))
    }
    @Test fun hidingTheKeyboardCollapsesExtrasAndRestoresEscapeFirst() {
        val visible=mutableStateOf(true)
        ui.setContent {MaterialTheme {Box(Modifier.width(360.dp)) {
            KeyboardBar(true,true,false,false,false,visible.value,{},{},{},{},{},{sent+=it},onKeyboard={visible.value=!visible.value})
        }}}
        val initial=ui.onNodeWithContentDescription("Escape").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("Terminal keys").performTouchInput {swipeRight()}
        // This test isolates the visibility reset; physical reveal/taps are covered above.
        ui.onNodeWithContentDescription("Show extra keys").assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        ui.onNodeWithContentDescription("Function key F1").assertIsDisplayed()
        ui.onNodeWithContentDescription("Hide keyboard").performTouchInput {click()}
        ui.onNodeWithContentDescription("Function key F1").assertDoesNotExist()
        ui.onNodeWithContentDescription("Show extra keys").assertIsNotDisplayed()
        assertEquals(initial,ui.onNodeWithContentDescription("Escape").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithContentDescription("Show keyboard").performTouchInput {click()}
        ui.onNodeWithContentDescription("Show extra keys").assertIsNotDisplayed()
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun changingTheInputTargetCancelsTheJoystick() {
        val target=mutableStateOf("one")
        ui.setContent {val current=target.value;MaterialTheme {Box(Modifier.width(360.dp)) {
            KeyboardBar(true,true,false,false,false,true,{},{},{},{},{},{sent+=current},inputKey=current,onKeyboard={})
        }}}
        ui.mainClock.autoAdvance=false
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {down(center);moveTo(center+androidx.compose.ui.geometry.Offset(40f,0f))}
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Arrow joystick").assertIsDisplayed()
        ui.runOnIdle {target.value="two"}
        ui.mainClock.advanceTimeByFrame();val count=sent.size
        ui.mainClock.advanceTimeBy(600)
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        ui.runOnIdle {assertEquals(count,sent.size);assertTrue(sent.all {it=="one"})}
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {up()}
    }
    @Test fun nativeTapDoesNotOpenAPersistentArrowPopupOrType() {
        keyboard();nativeTap("Arrow keys")
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    private fun nativeBounds(description: String): Rect {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.uiAutomation
        val info=automation.serviceInfo;info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if(node==null) return null
            if(node.contentDescription?.toString()==description) return Rect().also {node.getBoundsInScreen(it)}.takeUnless {it.isEmpty}
            for(i in 0 until node.childCount) find(node.getChild(i))?.let {return it}
            return null
        }
        var found: Rect?=null
        val deadline=SystemClock.uptimeMillis()+4000
        while(found==null && SystemClock.uptimeMillis()<deadline) {
            found=automation.windows.firstNotNullOfOrNull {find(it.root)}
            if(found==null) SystemClock.sleep(20)
        }
        return found ?: error("No native key $description")
    }
    private fun nativeTap(description: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bounds=nativeBounds(description)
        val down=SystemClock.uptimeMillis()
        fun send(action: Int) {val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0);instrumentation.sendPointerSync(event);event.recycle()}
        send(MotionEvent.ACTION_DOWN);SystemClock.sleep(35);send(MotionEvent.ACTION_UP)
        ui.waitForIdle()
    }
    @Test fun joystickOverlaysTheUnchangedRowInBothThemes() {
        val dark=mutableStateOf(false)
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            Box(Modifier.fillMaxSize().padding(bottom=80.dp),contentAlignment=Alignment.BottomCenter) {
                Box(Modifier.width(360.dp).testTag("Keyboard dock")) {
                    KeyboardBar(true,true,false,false,false,true,{},{},{},{},{},{sent+=it},onKeyboard={})
                }
            }
        }}
        capture("arrow-dock",ui.onNodeWithTag("Keyboard dock"))
        val enter=ui.onNodeWithContentDescription("Enter").fetchSemanticsNode().boundsInRoot
        val trigger=ui.onNodeWithContentDescription("Arrow keys")
        trigger.performTouchInput {down(center);moveTo(center+androidx.compose.ui.geometry.Offset(-40f,0f))}
        val popup=ui.onNodeWithContentDescription("Arrow joystick")
        popup.assertIsDisplayed()
        val face=popup.fetchSemanticsNode().boundsInRoot
        assertEquals(face.width,face.height,1f)
        assertEquals(enter,ui.onNodeWithContentDescription("Enter").fetchSemanticsNode().boundsInRoot)
        capture("arrow-joystick-light",popup)
        ui.runOnIdle {dark.value=true}
        popup.assertIsDisplayed();capture("arrow-joystick-dark",popup)
        trigger.performTouchInput {up()}
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        ui.runOnIdle {assertTrue(sent.isNotEmpty());assertTrue(sent.all {it=="left"})}
    }
    @Test fun compactAndFullJoystickStayCenteredAtTheBottomAndSteerFromTheVisibleCenter() {
        val keyboardVisible=mutableStateOf(false)
        ui.setContent {MaterialTheme {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top=40.dp,bottom=if(keyboardVisible.value) 220.dp else 0.dp)
                .semantics {contentDescription="Precision joystick area"},contentAlignment=Alignment.BottomCenter) {
                Box(Modifier.width(360.dp)) {
                    KeyboardBar(true,true,false,false,false,keyboardVisible.value,{},{},{},{},{},{sent+=it},onKeyboard={})
                }
            }
        }}
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        for(visible in listOf(false,true)) {
            ui.runOnIdle {keyboardVisible.value=visible}
            ui.mainClock.autoAdvance=true;ui.waitForIdle()
            val area=nativeBounds("Precision joystick area")
            val logicalArea=ui.onNodeWithContentDescription("Precision joystick area").fetchSemanticsNode().boundsInWindow
            val origin=androidx.compose.ui.geometry.Offset(area.left-logicalArea.left,area.top-logicalArea.top)
            // Compare the drawn anchor rather than expanded accessibility touch bounds.
            val logicalAnchor=ui.onNodeWithContentDescription("Arrow keys").fetchSemanticsNode().boundsInWindow
            val center=logicalAnchor.center+origin
            val left=logicalAnchor.left+origin.x
            val down=SystemClock.uptimeMillis()
            val before=sent.size;ui.mainClock.autoAdvance=false
            fun send(action: Int,x: Float,y: Float) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
                InstrumentationRegistry.getInstrumentation().sendPointerSync(event);event.recycle()
                repeat(3) {ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
                // A new native popup needs a rendered window frame as well as layout.
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(50,1000)
            }
            // Begin near the left edge, not at the button's center.
            send(MotionEvent.ACTION_DOWN,left+4f,center.y)
            val face=nativeBounds("Arrow joystick")
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {shot->File(context.cacheDir,"joystick-native-alignment-$visible.png").outputStream().use {shot.compress(Bitmap.CompressFormat.PNG,100,it)}}
            assertEquals(center.x,face.exactCenterX(),2f)
            assertEquals(center.y,face.exactCenterY(),2f)
            assertEquals((if(visible) 88f else 52f)*density,face.width().toFloat(),2f)
            capture(if(visible) "joystick-full-centered" else "joystick-compact-centered",ui.onNodeWithContentDescription("Arrow joystick"))
            send(MotionEvent.ACTION_MOVE,left+5f,center.y)
            ui.runOnIdle {assertEquals("Tiny movement after an off-center press must stay neutral",before,sent.size)}
            send(MotionEvent.ACTION_MOVE,center.x,center.y-40f)
            ui.runOnIdle {assertEquals(before+1,sent.size);assertEquals("up",sent.last())}
            send(MotionEvent.ACTION_UP,center.x,center.y-40f)
            ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        }
    }
    @Test fun popupTapsUseTheExistingShiftModifier() {
        keyboard()
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {doubleClick()}
        ui.onNodeWithContentDescription("Left arrow").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.onNodeWithContentDescription("Right arrow").performTouchInput {click()}
        ui.onNodeWithContentDescription("Page up").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        ui.onNodeWithContentDescription("Page down").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.runOnIdle {assertEquals(listOf("\u001b[1;2D","\u001b[C","\u001b[5~","\u001b[6;2~"),sent)}
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        capture("arrow-page-keys",ui.onNodeWithContentDescription("Arrow key popup"))
    }
    @Test fun nativeSingleTapClosesPopupAndOutsideKeysLeaveItOpen() {
        keyboard();val bounds=nativeBounds("Arrow keys")
        fun doubleTap() {
            repeat(2) {index->
                val down=SystemClock.uptimeMillis()
                fun send(action: Int) {
                    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                    InstrumentationRegistry.getInstrumentation().sendPointerSync(event);event.recycle()
                }
                send(MotionEvent.ACTION_DOWN);SystemClock.sleep(25);send(MotionEvent.ACTION_UP);ui.waitForIdle()
                if(index==0) SystemClock.sleep(80)
            }
        }
        doubleTap()
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        nativeTap("Escape")
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        nativeTap("Left arrow")
        ui.runOnIdle {assertEquals(listOf("\u001b","\u001b[D"),sent)}
        nativeTap("Arrow keys")
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        ui.runOnIdle {assertEquals(2,sent.size);assertEquals(0,keyboardToggles)}
    }
    private fun joystick(direction: String) {
        val delta=when(direction) {"left"->androidx.compose.ui.geometry.Offset(-40f,0f);"right"->androidx.compose.ui.geometry.Offset(40f,0f);"up"->androidx.compose.ui.geometry.Offset(0f,-40f);else->androidx.compose.ui.geometry.Offset(0f,40f)}
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {down(center);moveTo(center+delta);up()}
    }
    private fun capture(name: String,node: SemanticsNodeInteraction) {
        ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir,"$name.png").outputStream().use {node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
