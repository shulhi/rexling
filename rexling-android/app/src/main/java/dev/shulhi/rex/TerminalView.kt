package dev.shulhi.rex

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.widget.OverScroller
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.max

internal enum class TerminalLinkAction { OPEN, COPY_ADDRESS, SHARE }

class TerminalView(context: Context) : View(context) {
    private var cursorLit=true
    private fun cursorCanBlink(): Boolean = screen?.let {it.cursorBlinking && it.cursorX!=null && it.cursorY!=null && it.scroll.below==0L}==true && interactive && isAttachedToWindow && isShown && hasWindowFocus()
    private val cursorTick=object: Runnable {
        override fun run() {
            if(!cursorCanBlink()) {cursorLit=true;return}
            cursorLit=!cursorLit;invalidate();postDelayed(this,500)
        }
    }
    private fun refreshCursorBlink() {
        removeCallbacks(cursorTick);cursorLit=true
        if(cursorCanBlink()) postDelayed(cursorTick,500)
        invalidate()
    }
    private val awake=TerminalAwake(this)
    internal var keepAwakeMode: KeepScreenAwake
        get()=awake.mode
        set(value) {awake.mode=value}
    override fun onAttachedToWindow() {super.onAttachedToWindow();awake.update();refreshCursorBlink()}
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {super.onWindowFocusChanged(hasWindowFocus);awake.update();refreshCursorBlink()}
    override fun onWindowVisibilityChanged(visibility: Int) {super.onWindowVisibilityChanged(visibility);awake.update();refreshCursorBlink()}
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {if(event.actionMasked==MotionEvent.ACTION_DOWN) awake.activity();return super.dispatchTouchEvent(event)}
    var interactive = true
        set(value) {if(field!=value) {field=value;if(!value) {pointerPress=null;mouseTouch=false;clearSelection()};refreshCursorBlink()}}
    private var selection: TerminalSelection?=null
    private var selectionMode: ActionMode?=null
    private var selectionLink: TerminalLink?=null
    private var selectionInputTarget: String?=null
    private var selectionTouch=false
    private var moveSelectionAnchor=false
    private var pressedLink: TerminalLink?=null
    internal var onOpenLink: (String)->Unit={openWebLink(it)}
    internal var onShareLink: (String)->Unit={shareWebLink(it)}
    private var pressX=0f
    private var pressY=0f
    private val longPress=Runnable {performLongClick()}
    internal val selectedText: String? get()=selection?.text()
    var palette: TerminalPalette = TerminalPalette.Dark
        set(value) { if(field != value) { field=value;clearRows();invalidate() } }
    var onPaneDrag: ((Float, Boolean) -> Unit)? = null
    var onPaneDragEnd: ((Float, Float) -> Unit)? = null
    internal var onPaneControlsTap: ((Float,Float)->Boolean)?=null
    internal var onFocusTap: (()->Unit)?=null
    private val acceptsPointer: Boolean get()=interactive || onFocusTap!=null
    private var startX=0f
    private var startY=0f
    private var dragging=false
    private var scrolling=false
    private var lastScrollY=0f
    private var scrollPixels=0f
    private var lastFlingY=0
    private val scrollFling=OverScroller(context)
    private var touchVelocity: VelocityTracker?=null
    internal var imageInputTarget: ()->String = {mouseTarget()}
    internal var onImage: (Uri,()->Unit)->Boolean = {_,release->release();false}
    internal var onPaste: ()->Boolean = {false}
    var onInput: (String) -> Unit = {}
    internal var onInputConnectionReady: ()->Unit = {}
    var onScroll: (Int) -> Unit = {}
    var onWheel: (Int,Int,Int,Int,Int) -> Unit = {lines,_,_,_,_->onScroll(lines)}
    internal var keyboardPan: TerminalImePanState?=null
    var onActivate: (Boolean) -> Unit = {}
    var onMouseClick: (Int,Int,Int,Int) -> Unit = {_,_,_,_->}
    internal var onSemanticCopy: ((TerminalScreen,Int,Int,Boolean)->Boolean)?=null
    internal var onCursorTap: ()->Unit = {}
    var mouseTarget: ()->String={""}
    private var pressedMouseTarget=""
    private var pressedMouseGrid: Pair<Int,Int>?=null
    private var pressedMouse=false
    private var pressedControlled=false
    private var wheelCol=0
    private var wheelRow=0
    var onPaneSwipe: (Int) -> Unit = {}
    var onDimensions: (Int, Int) -> Unit = { _, _ -> }
        set(value) {val changed=field!=value;field=value;if(changed && width>0 && height>0) reportDimensions(force=true)}
    private var currentScreen: TerminalScreen?=null
    var screen: TerminalScreen?
        get()=currentScreen
        set(value) {setScreen(value,false)}
    internal var allowHiddenCursorInput: Boolean=false
        set(value) {if(field!=value) {field=value;reportCursorViewport();invalidate()}}
    private fun inputCursorRow(frame: TerminalScreen)=frame.cursorY ?: frame.cursorAnchorY.takeIf {allowHiddenCursorInput && frame.cursorAnchorX!=null}
    internal var onKeyboardFocus: ((TerminalKeyboardFocus?)->Unit)?=null
    internal val keyboardFocus: TerminalKeyboardFocus? get() {
        val frame=screen ?: return null
        if(frame.scroll.below>0) return null
        val row=inputCursorRow(frame)?.takeIf {it in 0 until frame.rows} ?: return null
        val lastText=frame.lines.indexOfLast {cells->cells.any {cell->cell.width>0 && cell.text.any {it!='\u0000' && !it.isWhitespace()}}}
        val bottom=insetTop+(row+1)*cellHeight+insetBottom
        return TerminalKeyboardFocus(insetTop+row*cellHeight,bottom,
            max(bottom,insetTop+(lastText+1)*cellHeight+insetBottom))
    }
    private fun reportCursorViewport() {onKeyboardFocus?.invoke(keyboardFocus)}
    private var frameTraceToken: String?=null
    internal fun presentFrame(value: TerminalScreen?,token: String?=null) {setScreen(value,true);frameTraceToken=token}
    private fun setScreen(value: TerminalScreen?,onDisplayFrame: Boolean) {
        if(currentScreen===value) return
        if(value!=null && value!=currentScreen) awake.activity()
        val resetCursor=currentScreen?.let {it.cursorX!=value?.cursorX || it.cursorY!=value?.cursorY || it.cursorStyle!=value?.cursorStyle || it.cursorBlinking!=value?.cursorBlinking || it.scroll.below!=value?.scroll?.below} ?: true
        val columns=currentScreen?.columns;val rows=currentScreen?.rows;currentScreen=value;frameTraceToken=null
        if(columns!=value?.columns || rows!=value?.rows) {scrollMotion.reset();configureRendering()}
        reportCursorViewport()
        if(resetCursor) refreshCursorBlink()
        if(onDisplayFrame) invalidate() else postInvalidateOnAnimation()
    }
    var keyboardSuggestions: Boolean = true
    /** The on-screen keyboard's Enter submits (CR) instead of inserting a newline (LF). */
    var imeEnterSubmits: Boolean = false
    var controlled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {composing="";imeEditable?.clear();imeEditable=null}
                selectionMode?.invalidate()
                // Losing control invalidates active() immediately. Restarting the old
                // editor here makes Gboard show a fallback layout during pane handoff.
                // The incoming focused editor supplies its own connection instead.
                if(value && hasFocus()) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
            }
        }
    var textSp: Float = 10f
        set(value) { if (field != value) { field = value; metrics(); invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint() // Cell fills must meet without antialiased seams.
    private val textColors = TerminalTextColors()
    private val fonts = TerminalFonts(context)
    private val terminalImages=TerminalImages()
    private data class RecordedRow(val cells: List<TerminalCell>,val recording: TerminalRowRecording,val links: List<TerminalLink>,
        val paddingCursorColumn: Int?) {
        val contentHash by lazy(LazyThreadSafetyMode.NONE) { rowContentHash(cells) }
        fun atRow(y: Int): RecordedRow = if(links.isEmpty()) this else copy(links=links.map {it.copy(row=y)})
    }
    /**
     * Smooth scrolling: pixels scrolled past the last whole line that the
     * canvas slides by. Positive means content moves up (scrolling forward).
     * Whole lines go to the replica; the remainder is only drawn here, using
     * the overscan rows at the edges.
     */
    private val scrollMotion=TerminalScrollMotion()
    private var recordedOffset: Long?=null
    private var recordedApplicationLines: List<List<TerminalCell>>?=null
    private var reuseApplicationRowsUntil=0L
    private var suppressScrollTap=false
    private var draggedKeyboardViewport=false
    private val linkIndex=TerminalLinks.Index()
    private var recorded= arrayOfNulls<RecordedRow>(0)
    private var rowGeometry=""
    private val advances=object: LinkedHashMap<Pair<Int,String>,Float>(256,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int,String>,Float>?)=size>4096
    }
    internal var recordedRowCount=0;private set
    private fun clearRows() {recordedOffset=null;recordedApplicationLines=null;recorded.forEach {it?.recording?.release()};recorded=arrayOfNulls(0);rowGeometry=""}
    private fun advance(text: String,style: Int): Float=advances.getOrPut(style to text){paint.measureText(text)}
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baseline = 0f
    private var desiredCellWidth=1f
    private var desiredCellHeight=1f
    private var desiredTextSize=1f
    private var dimensions = 0 to 0
    private var composing = ""
    private var imeEditable: android.text.Editable?=null
    private var composingCursor=0
    private var flushImeEditor: (() -> Unit)?=null
    private var deliveringImeInput=false
    private var insetHorizontal = 0f
    private var insetTop = 0f
    private var insetBottom = 0f
    private val contentWidth: Float get() = (width - insetHorizontal * 2).coerceAtLeast(1f)
    private val contentHeight: Float get() = (height - insetTop - insetBottom).coerceAtLeast(1f)
    fun setTerminalPaddingDp(horizontal: Int, top: Int, bottom: Int) {
        val density = resources.displayMetrics.density
        val h = (horizontal * density).roundToInt().toFloat()
        val t = (top * density).roundToInt().toFloat()
        val b = (bottom * density).roundToInt().toFloat()
        if (h == insetHorizontal && t == insetTop && b == insetBottom) return
        insetHorizontal = h; insetTop = t; insetBottom = b
        configureRendering();clearRows();reportDimensions();reportCursorViewport(); invalidate()
    }
    private val paneGesture = PaneGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), 48f * resources.displayMetrics.density)

    init {
        isFocusable = true; isFocusableInTouchMode = true;isLongClickable=true
        contentDescription = "Terminal output. Tap web links to open them. Tap mouse-enabled applications to click. Tap elsewhere to take control and type. Long press to select and copy text."
        metrics()
    }
    private fun metrics() {
        clearRows();advances.clear()
        val grid = fonts.configure(paint, textSp)
        desiredCellWidth=grid.cellWidth;desiredCellHeight=grid.cellHeight;desiredTextSize=paint.textSize
        configureRendering();reportDimensions();reportCursorViewport()
    }
    private fun configureRendering() {
        // Keep the user's font fixed. Reflow comes from the canonical PTY resize.
        val fitted=desiredCellWidth
        val size=desiredTextSize
        if(cellWidth==fitted && paint.textSize==size) return
        cellWidth=fitted;paint.textSize=size;paint.typeface=fonts.regular;paint.textScaleX=1f
        paint.textScaleX=fitted/paint.measureText("M").coerceAtLeast(.01f)
        val metrics=paint.fontMetrics;cellHeight=ceil(metrics.descent-metrics.ascent).coerceAtLeast(1f);baseline=-metrics.ascent
        clearRows();advances.clear()
    }
    private fun reportDimensions(force: Boolean=false) {
        // Resize uses the requested font grid, never the temporary read-only fit.
        val next = max(1, floor(contentWidth / desiredCellWidth).toInt()) to max(1, floor(contentHeight / desiredCellHeight).toInt())
        if (width > 0 && height > 0 && (force || next != dimensions)) { dimensions = next; onDimensions(next.first, next.second) }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {scrollMotion.reset();clearSelection();configureRendering();reportDimensions();reportCursorViewport()}
    override fun onDraw(canvas: Canvas) {
        val frame = selection?.frame ?: screen ?: return
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(palette.background)
        val visibleColumns = minOf(frame.columns, max(1, ceil(contentWidth / cellWidth).toInt()))
        val visibleRows = minOf(frame.rows, max(1, ceil(contentHeight / cellHeight).toInt()))
        android.os.Trace.beginSection("Rexlet.drawTerminal")
        val token=frameTraceToken.takeIf {selection==null}
        if(token!=null) android.os.Trace.beginSection("Rexlink.draw.$token")
        val geometry="$width:$height:${frame.columns}:$visibleColumns:$visibleRows:$insetHorizontal:$insetTop:$insetBottom:${canvas.isHardwareAccelerated}"
        if(geometry!=rowGeometry) {clearRows();recorded=arrayOfNulls(visibleRows);rowGeometry=geometry}
        android.os.Trace.beginSection("Rexlink.linkIndex")
        val rowLinks=try {linkIndex.update(frame)} finally {android.os.Trace.endSection()}
        // Content that only moved keeps its recording: reuse rows by content
        // when the whole screen shifted, so only edge and uncovered rows need recording.
        reuseShiftedRows(frame,visibleRows)
        val anchorRow=frame.cursorAnchorY ?: -1
        val paddingCursor=if(allowHiddenCursorInput && frame.cursorX==null && frame.cursorY==null &&
            frame.scroll.below==0L && anchorRow in 0 until visibleRows) {
            frame.lines[anchorRow].firstOrNull {cell->
                cell.x==frame.cursorAnchorX && cell.x<visibleColumns && cell.width>0 && cell.attributes and 8!=0 &&
                    (cell.x==0 || cell.x+cell.width>=visibleColumns || anchorRow==0 || anchorRow==visibleRows-1)
            }
        } else null
        val slide=slideFor(frame)
        if(slide!=0f) {canvas.save();canvas.clipRect(0f,insetTop,width.toFloat(),height-insetBottom);canvas.translate(0f,-slide)}
        if(frame.images.isEmpty()) {
        if(slide<0f) frame.above.forEachIndexed {i,row->drawLooseRow(canvas,row,visibleColumns,insetTop-(frame.above.size-i)*cellHeight)}
        if(slide>0f) frame.below.forEachIndexed {i,row->drawLooseRow(canvas,row,visibleColumns,insetTop+(visibleRows+i)*cellHeight)}
        for(y in 0 until visibleRows) {
            val cells=frame.lines[y]
            val cursor=paddingCursor.takeIf {y==anchorRow}
            var row=recorded[y]
            val links=rowLinks[y]
            if(row==null || !row.recording.valid || !sameCells(row.cells,cells) || row.links!=links || row.paddingCursorColumn!=cursor?.x) {
                val origin=if(y==0) 0f else insetTop+y*cellHeight
                val top=if(y==0) insetTop else 0f
                val bottom=if(y==visibleRows-1) height-origin else top+cellHeight
                val recording=row?.recording ?: TerminalRowRecording.create(canvas.isHardwareAccelerated)
                recording.record(width,bottom.toInt().coerceAtLeast(1)) { target ->
                    drawRow(target,cells,visibleColumns,top,bottom,paddingCursor=cursor)
                    target.save();target.clipRect(insetHorizontal,top,width-insetHorizontal,top+cellHeight)
                    backgroundPaint.color=palette.accent;backgroundPaint.strokeWidth=resources.displayMetrics.density.coerceAtLeast(1f)
                    for(link in links) target.drawLine(insetHorizontal+link.start*cellWidth,top+baseline+2,
                        insetHorizontal+link.end*cellWidth,top+baseline+2,backgroundPaint)
                    target.restore()
                }
                row=RecordedRow(cells,recording,links,cursor?.x);recorded[y]=row;recordedRowCount++
            }
            canvas.save();canvas.translate(0f,if(y==0) 0f else insetTop+y*cellHeight)
            row.recording.draw(canvas);canvas.restore()
        }
        } else {
            fun images(layer: Int)=terminalImages.draw(canvas,frame,layer,insetHorizontal,insetTop,width-insetHorizontal,height-insetBottom,cellWidth,cellHeight)
            fun rows(pass: Int) {
                for(y in 0 until visibleRows) {
                    val origin=if(y==0) 0f else insetTop+y*cellHeight
                    val top=if(y==0) insetTop else 0f
                    val bottom=if(y==visibleRows-1) height-origin else top+cellHeight
                    val save=canvas.save();canvas.translate(0f,origin)
                    drawRow(canvas,frame.lines[y],visibleColumns,top,bottom,pass,paddingCursor.takeIf {y==anchorRow})
                    if(pass==2) {
                        canvas.save();canvas.clipRect(insetHorizontal,top,width-insetHorizontal,top+cellHeight)
                        backgroundPaint.color=palette.accent;backgroundPaint.strokeWidth=resources.displayMetrics.density.coerceAtLeast(1f)
                        for(link in rowLinks[y]) canvas.drawLine(insetHorizontal+link.start*cellWidth,top+baseline+2,insetHorizontal+link.end*cellWidth,top+baseline+2,backgroundPaint)
                        canvas.restore()
                    }
                    canvas.restoreToCount(save)
                }
            }
            images(0);rows(1);images(1);rows(2);images(2)
        }
        if(slide!=0f) canvas.restore()
        canvas.save()
        canvas.clipRect(insetHorizontal,insetTop,width-insetHorizontal,height-insetBottom)
        canvas.translate(insetHorizontal,insetTop-slide)
        if (selection==null && frame.cursorX != null && frame.cursorY != null) {
            val cell=frame.lines.getOrNull(frame.cursorY)?.firstOrNull {it.x==frame.cursorX && it.width>0}
            val x = frame.cursorX * cellWidth; val y = frame.cursorY * cellHeight
            val cursorWidth=max(1,cell?.width ?: 1)*cellWidth
            backgroundPaint.color=palette.accent
            if(cursorLit) {
                val thickness=max(1f,resources.displayMetrics.density*1.5f)
                when(frame.cursorStyle) {
                    0->canvas.drawRect(x,y,x+thickness,y+cellHeight,backgroundPaint)
                    2->canvas.drawRect(x,y+cellHeight-thickness,x+cursorWidth,y+cellHeight,backgroundPaint)
                    3->{
                        backgroundPaint.style=Paint.Style.STROKE;backgroundPaint.strokeWidth=thickness
                        canvas.drawRect(x+thickness/2,y+thickness/2,x+cursorWidth-thickness/2,y+cellHeight-thickness/2,backgroundPaint)
                        backgroundPaint.style=Paint.Style.FILL
                    }
                    else->canvas.drawRect(x,y,x+cursorWidth,y+cellHeight,backgroundPaint)
                }
            }
            if(cursorLit && frame.cursorStyle==1 && cell!=null && cell.text.isNotEmpty() && cell.attributes and 16==0) {
                paint.color=palette.cursorText;paint.alpha=255
                paint.typeface=fonts.typefaceFor(cell.attributes and 3,cell.text)
                val advance=advance(cell.text,cell.attributes and 3)
                canvas.save();canvas.clipRect(x,y,x+cursorWidth,y+cellHeight)
                if(advance>cursorWidth) canvas.scale(cursorWidth/advance,1f,x,y)
                canvas.drawText(cell.text,x+((cursorWidth-advance)/2f).coerceAtLeast(0f),y+baseline,paint)
                canvas.restore()
            }
            if (composing.isNotEmpty()) {
                paint.typeface = fonts.typefaceFor(0,composing);paint.color=android.graphics.Color.WHITE
                val composingWidth=max(cellWidth,paint.measureText(composing))
                canvas.drawRect(x,y,x+composingWidth,y+cellHeight,backgroundPaint)
                canvas.drawText(composing, x, y + baseline, paint)
                canvas.drawLine(x, y + baseline + 2, x + composingWidth, y + baseline + 2, paint)
                if(composingCursor<composing.length) {
                    val caret=x+paint.measureText(composing,0,composingCursor.coerceIn(0,composing.length))
                    canvas.drawLine(caret,y+2,caret,y+cellHeight-2,paint)
                }
            }
        }
        canvas.restore()
        drawSelection(canvas)
        canvas.restoreToCount(saved)
        if(token!=null) android.os.Trace.endSection()
        android.os.Trace.endSection()
    }
    /** Rows compare by content only; a row that moved is the same drawing. */
    private fun sameCells(a: List<TerminalCell>,b: List<TerminalCell>): Boolean {
        if(a===b) return true
        if(a.size!=b.size) return false
        for(i in a.indices) {val x=a[i];val y=b[i]
            if(x.x!=y.x || x.width!=y.width || x.attributes!=y.attributes || x.foreground!=y.foreground ||
                x.background!=y.background || x.text!=y.text || x.hyperlink!=y.hyperlink) return false}
        return true
    }
    /**
     * When the grid scrolled, move recordings with their content instead of
     * re-recording every row. Only interior rows (same height) are moved; the
     * first and last rows carry padding and are re-recorded if they changed.
     */
    private fun reuseShiftedRows(frame: TerminalScreen,visibleRows: Int) {
        val previous=recordedOffset
        val previousApplicationLines=recordedApplicationLines
        recordedOffset=frame.scroll.offset
        recordedApplicationLines=frame.lines.takeIf {frame.applicationScroll && frame.scroll.below==0L && frame.images.isEmpty()}
        if(previous==null || recorded.size!=visibleRows || visibleRows<4) return
        val distance=frame.scroll.offset-previous
        if(distance==0L) {
            if(recordedApplicationLines!=null && previousApplicationLines!==frame.lines &&
                android.os.SystemClock.uptimeMillis()<reuseApplicationRowsUntil) reuseApplicationRows(frame,visibleRows)
            return
        }
        if(kotlin.math.abs(distance)>=visibleRows-2) return
        val shift=distance.toInt()
        val next=arrayOfNulls<RecordedRow>(visibleRows)
        val used=BooleanArray(visibleRows)
        for(y in 1 until visibleRows-1) {
            val from=y+shift
            if(from in 1 until visibleRows-1) {next[y]=recorded[from]?.atRow(y);used[from]=true}
        }
        // Keep edge rows in place; release recordings that fell off.
        next[0]=recorded[0];next[visibleRows-1]=recorded[visibleRows-1];used[0]=true;used[visibleRows-1]=true
        for(y in recorded.indices) if(!used[y]) recorded[y]?.recording?.release()
        recorded=next
    }
    /** A full-screen program moves content without changing Ghostty's history offset. */
    private fun reuseApplicationRows(frame: TerminalScreen,visibleRows: Int) {
        val interior=1 until visibleRows-1
        var displaced: Array<RecordedRow?>?=null
        for(y in interior) {
            val row=recorded[y] ?: continue
            if(!sameCells(row.cells,frame.lines[y])) {
                val rows=displaced ?: arrayOfNulls<RecordedRow>(visibleRows).also {displaced=it}
                rows[y]=row
                recorded[y]=null
            }
        }
        val spare=displaced ?: return
        val candidates=HashMap<Int,MutableList<Int>>()
        for(y in interior) spare[y]?.takeIf {it.recording.valid}?.let {row->
            candidates.getOrPut(row.contentHash) {mutableListOf()}.add(y)
        }
        for(y in interior) {
            if(recorded[y]!=null || candidates.isEmpty()) continue
            val cells=frame.lines[y]
            val hash=rowContentHash(cells)
            val matches=candidates[hash] ?: continue
            val match=matches.indexOfFirst {from->sameCells(spare[from]!!.cells,cells)}
            if(match<0) continue
            val from=matches.removeAt(match)
            recorded[y]=spare[from]!!.atRow(y)
            spare[from]=null
            if(matches.isEmpty()) candidates.remove(hash)
        }
        // Reuse remaining recording objects for new content, keeping each object in one row.
        for(y in interior) {
            if(recorded[y]==null && spare[y]!=null) {
                recorded[y]=spare[y]
                spare[y]=null
            }
        }
        val remaining=spare.asSequence().filterNotNull().iterator()
        for(y in interior) if(recorded[y]==null && remaining.hasNext()) recorded[y]=remaining.next()
        while(remaining.hasNext()) remaining.next().recording.release()
    }
    /** An overscan row, drawn directly (it only shows mid-scroll). */
    private fun drawLooseRow(canvas: Canvas,cells: List<TerminalCell>,columns: Int,origin: Float) {
        val save=canvas.save();canvas.translate(0f,origin)
        drawRow(canvas,cells,columns,0f,cellHeight)
        canvas.restoreToCount(save)
    }
    private fun drawRow(canvas: Canvas,cells: List<TerminalCell>,columns: Int,top: Float,bottom: Float,pass: Int=0,
        paddingCursor: TerminalCell?=null) {
        var start=0;var end=0;var color=0
        fun flush() {
            if(pass==2 || end<=start || start>=columns) return
            val left=if(start==0) 0f else insetHorizontal+start*cellWidth
            val right=if(end>=columns) width.toFloat() else insetHorizontal+end*cellWidth
            if(paddingCursor!=null && start==paddingCursor.x && end==start+paddingCursor.width) {
                // An agent can draw its caret with reverse video while keeping the hardware
                // cursor hidden. Extend the original cell background into the padding.
                val base=palette.background(paddingCursor)
                if(base!=palette.background) {
                    backgroundPaint.color=base
                    canvas.drawRect(left,0f,right,bottom,backgroundPaint)
                }
                backgroundPaint.color=color
                canvas.drawRect(insetHorizontal+start*cellWidth,top,
                    minOf(insetHorizontal+end*cellWidth,width-insetHorizontal),minOf(top+cellHeight,bottom),backgroundPaint)
            } else if(color!=palette.background) {
                backgroundPaint.color=color
                canvas.drawRect(left,0f,right,bottom,backgroundPaint)
            }
        }
        for(cell in cells) {
            if(cell.width==0 && cell.x<end) continue
            val next=if(cell.attributes and 8!=0) palette.foreground(cell) else palette.background(cell)
            if(cell.x!=end || next!=color) {flush();start=cell.x;color=next}
            end=cell.x+max(1,cell.width)
        }
        flush()
        if(pass==1) return
        canvas.save();canvas.clipRect(insetHorizontal,top,width-insetHorizontal,top+cellHeight)
        canvas.translate(insetHorizontal,top)
        var index=0
        while(index<cells.size) {
            val cell=cells[index++]
            if(cell.x>=columns || cell.width==0 || cell.text.isEmpty() || cell.text.codePointAt(0)==0x10eeee || cell.attributes and 16!=0) continue
            val style=cell.attributes and 3
            paint.typeface=fonts.typefaceFor(style,cell.text)
            paint.color=textColors.foreground(palette,cell)
            paint.alpha=if(cell.attributes and 4!=0) 150 else 255
            var text=cell.text;var width=cell.width
            val ascii=cell.width==1 && cell.text.length==1 && cell.text[0] in ' '..'~'
            if(ascii) {
                val run=StringBuilder(text)
                while(index<cells.size) {
                    val next=cells[index]
                    if(next.x!=cell.x+width || next.x>=columns || next.width!=1 || next.text.length!=1 || next.text[0] !in ' '..'~' ||
                        next.attributes!=cell.attributes || next.foreground!=cell.foreground || next.background!=cell.background) break
                    run.append(next.text);width++;index++
                }
                text=run.toString()
            }
            val x=cell.x*cellWidth;val allocated=width*cellWidth
            if(text.any {it!=' '}) {
                if(ascii) canvas.drawText(text,x,baseline,paint)
                else {
                    val advance=advance(text,style)
                    canvas.save();canvas.clipRect(x,0f,x+allocated,cellHeight)
                    if(advance>allocated) canvas.scale(allocated/advance,1f,x,0f)
                    canvas.drawText(text,x+((allocated-advance)/2f).coerceAtLeast(0f),baseline,paint);canvas.restore()
                }
            }
            paint.strokeWidth=resources.displayMetrics.density.coerceAtLeast(1f)
            if(cell.attributes and 64!=0) canvas.drawLine(x,baseline+2,x+allocated,baseline+2,paint)
            if(cell.attributes and 32!=0) canvas.drawLine(x,cellHeight/2,x+allocated,cellHeight/2,paint)
            paint.alpha=255
        }
        canvas.restore()
    }
    private fun sendWheel(lines: Int,col: Int,row: Int) {
        // The program may return several frames over the network for one gesture.
        reuseApplicationRowsUntil=android.os.SystemClock.uptimeMillis()+500
        onWheel(lines,col,row,desiredCellWidth.roundToInt(),desiredCellHeight.roundToInt())
    }
    internal fun scrollByPixels(pixels: Float) {
        val frame=screen ?: return
        if(frame.applicationScroll && frame.scroll.below==0L) {
            scrollMotion.reset()
            scrollPixels+=pixels
            val lines=(scrollPixels/cellHeight).toInt()
            if(lines!=0) {scrollPixels-=lines*cellHeight;sendWheel(lines,wheelCol,wheelRow)}
        } else {
            val lines=scrollMotion.drag(pixels,frame.scroll,cellHeight)
            if(lines!=0) onScroll(lines)
            invalidate()
        }
    }
    private fun dragScroll(pixels: Float) {
        val frame=screen ?: return
        // Scrollback can reveal earlier text on its own. Application wheel events cannot expose
        // the clipped part of this terminal view, so give the local viewport first use of the drag.
        val remaining=if(pixels>0 || frame.scroll.offset==0L || (frame.applicationScroll && frame.scroll.below==0L))
            keyboardPan?.consumeDrag(pixels) ?: pixels else pixels
        if(remaining!=pixels) draggedKeyboardViewport=true
        if(remaining!=0f) scrollByPixels(remaining)
    }
    internal val scrollTranslation: Float get()=screen?.let {slideFor(it)} ?: 0f
    private fun slideFor(frame: TerminalScreen): Float {
        // Image placements outside the viewport are not yet available. Keep their
        // existing whole-row rendering rather than expose an incomplete image edge.
        if(selection!=null || frame.images.isNotEmpty()) return 0f
        return scrollMotion.slide(frame.scroll,cellHeight,frame.above.size,frame.below.size,scrolling || !scrollFling.isFinished)
    }
    override fun computeScroll() {
        if(scrollFling.computeScrollOffset()) {
            val y=scrollFling.currY;scrollByPixels((y-lastFlingY).toFloat());lastFlingY=y
            postInvalidateOnAnimation()
            if(scrollFling.isFinished) settleScroll()
        }
    }
    /** At rest the grid snaps to whole lines; lines still in flight finish sliding in. */
    private fun settleScroll() {
        if(scrollMotion.remainder!=0f || scrollPixels!=0f) {scrollPixels=0f;scrollMotion.settle();invalidate()}
    }
    override fun onDetachedFromWindow() {pointerPress=null;mouseTouch=false;removeCallbacks(cursorTick);awake.detach();clearRows();clearSelection();scrollFling.abortAnimation();touchVelocity?.recycle();touchVelocity=null;super.onDetachedFromWindow()}
    private data class PointerPress(val x: Float,val y: Float)
    private var pointerPress: PointerPress?=null
    private var mouseTouch=false

    private fun rememberPress(x: Float,y: Float) {
        pressedMouseTarget=mouseTarget()
        pressedMouseGrid=screen?.let {it.columns to it.rows}
        pressedMouse=screen?.mouseTracking==true
        pressedControlled=controlled
        pressedLink=linkAt(x,y)
    }

    private fun finishTap(x: Float,y: Float,showKeyboard: Boolean) {
        if(pressedMouseTarget!=mouseTarget()) return
        if(!interactive) {
            onFocusTap?.invoke()
            return
        }
        val link=pressedLink
        if(link==null) terminalTap(x,y,showKeyboard)
        else if(link==linkAt(x,y)) onOpenLink(link.url)
    }

    internal fun primaryPointerButton(pressed: Boolean,x: Float,y: Float) {
        if(pressed) {
            // Some devices also dispatch DOWN/UP. Let the touch gesture handle that click.
            if(!mouseTouch && selection==null) {
                awake.activity()
                rememberPress(x,y)
                pointerPress=PointerPress(x,y)
            }
        } else {
            val press=pointerPress
            pointerPress=null
            val slop=ViewConfiguration.get(context).scaledTouchSlop
            if(press!=null && selection==null &&
                kotlin.math.abs(x-press.x)<=slop && kotlin.math.abs(y-press.y)<=slop) {
                finishTap(x,y,showKeyboard=false)
            }
            if(!mouseTouch) pressedLink=null
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if(acceptsPointer && event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.actionButton==MotionEvent.BUTTON_PRIMARY &&
            event.actionMasked in MotionEvent.ACTION_BUTTON_PRESS..MotionEvent.ACTION_BUTTON_RELEASE) {
            primaryPointerButton(event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS,event.x,event.y)
            return true
        }
        if(selection!=null && event.actionMasked==MotionEvent.ACTION_SCROLL) return true
        if(acceptsPointer && event.actionMasked==MotionEvent.ACTION_SCROLL) {
            val lines=(-event.getAxisValue(MotionEvent.AXIS_VSCROLL)*3).roundToInt()
            if(lines!=0) {
                scrollMotion.reset()
                if(screen?.applicationScroll==true && screen?.scroll?.below==0L && event.metaState and KeyEvent.META_SHIFT_ON==0)
                    sendWheel(lines,((event.x-insetHorizontal)/desiredCellWidth).toInt(),((event.y-insetTop)/desiredCellHeight).toInt())
                else onScroll(lines)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }
    fun keyboard() {
        if (!controlled) return
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
    }
    private fun linkAt(x: Float,y: Float): TerminalLink? {
        val frame=screen ?: return null
        if(x<insetHorizontal || x>=width-insetHorizontal || y<insetTop || y>=height-insetBottom) return null
        val column=((x-insetHorizontal)/cellWidth).toInt();val row=((y-insetTop)/cellHeight).toInt()
        val links=linkIndex.update(frame).getOrNull(row) ?: return null
        return links.firstOrNull {it.contains(column)}
    }
    private fun openWebLink(url: String) {
        val safe=TerminalLinks.webUrl(url) ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context,"No app can open this web link",Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context,"Could not open this web link",Toast.LENGTH_SHORT).show()
        }
    }
    private fun shareWebLink(url: String) {
        val safe=TerminalLinks.webUrl(url) ?: return
        val send=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,safe)
        try {context.startActivity(Intent.createChooser(send,"Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        catch (_: ActivityNotFoundException) {Toast.makeText(context,"No app can share this link",Toast.LENGTH_SHORT).show()}
        catch (_: SecurityException) {Toast.makeText(context,"Could not share this link",Toast.LENGTH_SHORT).show()}
    }
    internal fun selectedLinkAction(action: TerminalLinkAction): Boolean {
        val url=selectionLink?.url?.let(TerminalLinks::openableUrl) ?: return false
        when(action) {
            TerminalLinkAction.OPEN->onOpenLink(url)
            TerminalLinkAction.COPY_ADDRESS->(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Terminal link",url))
            TerminalLinkAction.SHARE->onShareLink(url)
        }
        clearSelection();return true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!acceptsPointer) return false
        val fromMouse=event.isFromSource(InputDevice.SOURCE_MOUSE)
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            pointerPress=null
            mouseTouch=fromMouse
        }
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) mouseTouch=false
        if(selectionTouch || selection!=null) return selectionEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            touchVelocity?.recycle();touchVelocity=VelocityTracker.obtain();draggedKeyboardViewport=false
        }
        val sample=MotionEvent.obtain(event)
        sample.offsetLocation(event.rawX-event.x,event.rawY-event.y)
        touchVelocity?.addMovement(sample);sample.recycle()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {suppressScrollTap=!scrollFling.isFinished || scrollTranslation!=0f;scrollFling.abortAnimation();scrollMotion.reset();invalidate();rememberPress(event.x,event.y);pressX=event.x;pressY=event.y;removeCallbacks(longPress);postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong());wheelCol=((event.x-insetHorizontal)/desiredCellWidth).toInt();wheelRow=((event.y-insetTop)/desiredCellHeight).toInt();scrollFling.abortAnimation();startX=event.rawX;startY=event.rawY;lastScrollY=event.rawY;scrollPixels=0f;scrollMotion.reset();scrolling=false;dragging=false;paneGesture.begin(event.x, event.y)}
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) { removeCallbacks(longPress);paneGesture.cancel();onPaneDrag?.invoke(0f,false);dragging=false;scrolling=false }
                else {
                    paneGesture.move(event.x,event.y)
                    val dx=event.rawX-startX;val dy=event.rawY-startY
                    val slop=ViewConfiguration.get(context).scaledTouchSlop
                    if(kotlin.math.abs(dx)>slop || kotlin.math.abs(dy)>slop) removeCallbacks(longPress)
                    if(!scrolling && kotlin.math.abs(dx)>slop && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f) dragging=true
                    if(!dragging && kotlin.math.abs(dy)>slop && kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.5f) {scrolling=true;paneGesture.cancel()}
                    if(scrolling) {dragScroll(lastScrollY-event.rawY);lastScrollY=event.rawY}
                    else if(dragging) onPaneDrag?.invoke(dx,true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {scrollFling.abortAnimation();scrollMotion.reset();invalidate();pressedLink=null;removeCallbacks(longPress);paneGesture.cancel();onPaneDrag?.invoke(0f,false);dragging=false;scrolling=false}
            MotionEvent.ACTION_UP -> if(scrolling) {
                touchVelocity?.computeCurrentVelocity(1000)
                val velocity=-(touchVelocity?.yVelocity ?: 0f).toInt()
                val frame=screen
                val history=frame?.scroll?.takeUnless {frame.applicationScroll && it.below==0L}
                if(!draggedKeyboardViewport && history!=null && history.available>0) {
                    val minimum=-(history.offset.toDouble()*cellHeight).coerceAtMost(1000000.0).toInt()
                    val maximum=(history.below.toDouble()*cellHeight).coerceAtMost(1000000.0).toInt()
                    lastFlingY=0;scrollFling.fling(0,0,0,velocity,0,0,minimum,maximum);postInvalidateOnAnimation()
                } else settleScroll()
                scrolling=false;paneGesture.cancel()
            } else if(dragging && onPaneDragEnd!=null) {
                touchVelocity?.computeCurrentVelocity(1000)
                onPaneDragEnd?.invoke(event.rawX-startX,touchVelocity?.xVelocity ?: 0f);dragging=false;paneGesture.cancel()
            } else when (paneGesture.finish(event.x, event.y)) {
                PaneGesture.Result.TAP -> if(!suppressScrollTap) {
                    val openedControls=interactive && !fromMouse && pressedLink==null && onPaneControlsTap?.invoke(event.x,event.y)==true
                    if(!openedControls) finishTap(event.x,event.y,showKeyboard=!fromMouse)
                }
                PaneGesture.Result.NEXT -> onPaneSwipe(1)
                PaneGesture.Result.PREVIOUS -> onPaneSwipe(-1)
                PaneGesture.Result.NONE -> Unit
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {pressedLink=null;removeCallbacks(longPress);touchVelocity?.recycle();touchVelocity=null}
        return true
    }
    private fun terminalTap(x: Float,y: Float,showKeyboard: Boolean) {
        val frame=screen
        if(pressedMouse && pressedControlled && !controlled) return
        val col=floor((x-insetHorizontal)/cellWidth).toInt()
        val row=floor((y-insetTop)/cellHeight).toInt()
        val hit=frame?.takeIf {
            pressedMouseGrid==(it.columns to it.rows) &&
                x>=insetHorizontal && x<width-insetHorizontal && y>=insetTop && y<height-insetBottom &&
                col in 0 until it.columns && row in 0 until it.rows
        }
        if(controlled && (pressedMouse || frame?.mouseTracking==true)) {
            if(hit?.mouseTracking!=true || !pressedMouse || !pressedControlled || hit.scroll.below>0) return
            onMouseClick(col,row,cellWidth.roundToInt().coerceAtLeast(1),cellHeight.roundToInt().coerceAtLeast(1))
        }
        super.performClick()
        val input=hit?.takeIf {showKeyboard && it.scroll.below==0L}
        val cursorTap=input!=null && row==inputCursorRow(input)
        val promptTap=input!=null && (input.isSemanticInputRow(row) ||
            cursorTap && !input.hasSemanticPrompt && !input.mouseTracking && !input.alternateScreen)
        if(!controlled) onActivate(promptTap || cursorTap && allowHiddenCursorInput)
        else if(promptTap) keyboard()
        else if(cursorTap) onCursorTap()
    }
    internal fun copySemanticSelection(output: Boolean): Boolean {
        val selected=selection ?: return false
        if(!selected.frame.hasSemanticPrompt || selectionInputTarget!=imageInputTarget()) return false
        if(onSemanticCopy?.invoke(selected.frame,selected.first.column,selected.first.row,output)!=true) return false
        selectionMode?.finish();return true
    }
    private fun clearSelection() {
        removeCallbacks(longPress)
        selection=null;selectionLink=null;selectionInputTarget=null;selectionTouch=false
        val mode=selectionMode;selectionMode=null;mode?.finish();invalidate()
    }
    private fun selectionEvent(event: MotionEvent): Boolean {
        val current=selection
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectionTouch=true
                if(current!=null) {
                    fun distance(point: TerminalSelection.Point,end: Boolean): Float {
                        val x=insetHorizontal+(if(end) current.endColumn(point) else point.column)*cellWidth
                        val y=insetTop+(point.row+1)*cellHeight
                        return kotlin.math.hypot(event.x-x,event.y-y)
                    }
                    val a=distance(current.anchor,false);val b=distance(current.extent,true)
                    if(minOf(a,b)<=28*resources.displayMetrics.density) moveSelectionAnchor=a<b
                    else {clearSelection();selectionTouch=true}
                }
            }
            MotionEvent.ACTION_MOVE -> if(current!=null && event.pointerCount==1) {
                current.move(((event.x-insetHorizontal)/cellWidth).toInt(),((event.y-insetTop)/cellHeight).toInt(),moveSelectionAnchor)
                val link=selectionLink
                if(link!=null && (current.first>TerminalSelection.Point(link.end-1,link.row) || current.last<TerminalSelection.Point(link.start,link.row))) {
                    selectionLink=null;selectionMode?.invalidate()
                }
                selectionMode?.invalidateContentRect();invalidate()
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {selectionTouch=false;touchVelocity?.recycle();touchVelocity=null}
        }
        return true
    }
    private fun canPasteSelection(): Boolean = selection!=null && controlled && interactive &&
        selectionInputTarget==imageInputTarget() && (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).hasPrimaryClip()
    internal fun pasteSelection(): Boolean {
        if(!canPasteSelection() || !onPaste()) return false
        clearSelection();return true
    }
    override fun performLongClick(): Boolean {
        val frame=screen ?: return false
        if(!interactive) return false
        clearSelection()
        selectionLink=linkAt(pressX,pressY)
        pressedLink=null
        removeCallbacks(longPress);paneGesture.cancel();dragging=false;scrolling=false;scrollFling.abortAnimation();onPaneDrag?.invoke(0f,false)
        selectionInputTarget=imageInputTarget()
        selection=TerminalSelection(frame,((pressX-insetHorizontal)/cellWidth).toInt(),((pressY-insetTop)/cellHeight).toInt()).also {it.word()}
        selectionTouch=true;moveSelectionAnchor=false;parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        selectionMode=startActionMode(object: ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode,menu: Menu): Boolean {
                menu.add(0,android.R.id.copy,0,"Copy").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0,android.R.id.paste,1,"Paste").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.findItem(android.R.id.paste).isVisible=canPasteSelection()
                menu.add(0,android.R.id.selectAll,2,"Select all").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                if(frame.hasSemanticPrompt && onSemanticCopy!=null) {
                    menu.add(0,0x524504,3,"Copy command").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                    menu.add(0,0x524505,4,"Copy output").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                }
                selectionLink?.let {link->
                    val isFile=TerminalLinks.filePath(link.url)!=null
                    val openLabel=if(isFile) "Download file" else "Open link"
                    menu.add(0,0x524501,2,openLabel).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                    menu.add(0,0x524502,3,"Copy link address").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    if(!isFile) menu.add(0,0x524503,4,"Share link").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                }
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode,menu: Menu): Boolean {
                menu.findItem(android.R.id.paste)?.isVisible=canPasteSelection()
                for(id in listOf(0x524501,0x524502,0x524503)) menu.findItem(id)?.isVisible=selectionLink!=null
                return true
            }
            override fun onActionItemClicked(mode: ActionMode,item: MenuItem): Boolean = when(item.itemId) {
                android.R.id.copy -> {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal selection",selection?.text().orEmpty()))
                    mode.finish();true
                }
                android.R.id.paste -> pasteSelection()
                android.R.id.selectAll -> {selection?.all();selectionLink=null;invalidate();mode.invalidate();mode.invalidateContentRect();true}
                0x524504,0x524505 -> copySemanticSelection(item.itemId==0x524505)
                0x524501 -> selectedLinkAction(TerminalLinkAction.OPEN)
                0x524502 -> selectedLinkAction(TerminalLinkAction.COPY_ADDRESS)
                0x524503 -> selectedLinkAction(TerminalLinkAction.SHARE)
                else -> false
            }
            override fun onDestroyActionMode(mode: ActionMode) {if(selectionMode===mode) {selectionMode=null;selection=null;selectionLink=null;selectionInputTarget=null;invalidate()}}
            override fun onGetContentRect(mode: ActionMode,view: View,outRect: android.graphics.Rect) {
                val selected=selection ?: return super.onGetContentRect(mode,view,outRect)
                outRect.set((insetHorizontal+selected.first.column*cellWidth).toInt(),(insetTop+selected.first.row*cellHeight).toInt(),
                    (insetHorizontal+selected.endColumn(selected.last)*cellWidth).toInt().coerceAtMost(width),
                    (insetTop+(selected.last.row+1)*cellHeight).toInt().coerceAtMost(height))
            }
        },ActionMode.TYPE_FLOATING)
        invalidate();return true
    }
    private fun drawSelection(canvas: Canvas) {
        val selected=selection ?: return
        backgroundPaint.color=(palette.accent and 0xffffff) or (0x55 shl 24)
        for(row in selected.first.row..selected.last.row) {
            val left=if(row==selected.first.row) selected.first.column else 0
            val right=if(row==selected.last.row) selected.endColumn(selected.last) else selected.frame.columns
            canvas.drawRect(insetHorizontal+left*cellWidth,insetTop+row*cellHeight,
                (insetHorizontal+right*cellWidth).coerceAtMost(width-insetHorizontal),insetTop+(row+1)*cellHeight,backgroundPaint)
        }
        backgroundPaint.color=palette.accent
        for((point,end) in listOf(selected.first to false,selected.last to true)) {
            val x=insetHorizontal+(if(end) selected.endColumn(point) else point.column)*cellWidth
            val y=insetTop+(point.row+1)*cellHeight
            canvas.drawCircle(x,y,5*resources.displayMetrics.density,backgroundPaint)
        }
    }
    internal fun flushComposingText(restartIme: Boolean=true) {
        if(deliveringImeInput) return
        val hadContext=imeEditable?.isNotEmpty()==true || composing.isNotEmpty()
        flushImeEditor?.invoke()
        composing="";composingCursor=0
        if(restartIme && hadContext) {
            imeEditable=null;flushImeEditor=null
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
        }
    }
    override fun performClick(): Boolean {
        super.performClick()
        if(!interactive) onFocusTap?.invoke()
        else if(controlled) keyboard()
        else onActivate(true)
        return true
    }
    override fun onCheckIsTextEditor() = controlled
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!controlled) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or (if(keyboardSuggestions) InputType.TYPE_TEXT_FLAG_AUTO_CORRECT else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        outAttrs.contentMimeTypes=imageMimeTypes.copyOf()
        val imageTarget=imageInputTarget()
        val pending=composing
        val editor=android.text.SpannableStringBuilder(pending)
        android.text.Selection.setSelection(editor,editor.length)
        imeEditable=editor
        outAttrs.initialSelStart=editor.length;outAttrs.initialSelEnd=editor.length
        if(android.os.Build.VERSION.SDK_INT>=30) outAttrs.setInitialSurroundingText(editor)
        val connection=object : BaseInputConnection(this, true) {
            private var batchDepth=0
            private var pendingCommit=false
            private val imeLine=TerminalImeLine()
            private fun active()=controlled && interactive && imageTarget==imageInputTarget() && imeEditable===editor
            override fun getEditable(): android.text.Editable = editor
            private fun sync() {
                val text=editor.toString()
                composing=if(text.startsWith(imeLine.text) && imeLine.cursor==imeLine.text.length) text.substring(imeLine.text.length) else ""
                composingCursor=(android.text.Selection.getSelectionEnd(editor)-imeLine.text.length).coerceIn(0,composing.length)
                invalidate()
                if(batchDepth==0) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).updateSelection(this@TerminalView,
                    android.text.Selection.getSelectionStart(editor),android.text.Selection.getSelectionEnd(editor),
                    getComposingSpanStart(editor),getComposingSpanEnd(editor))
            }
            private fun submit(text: String,cursor: Int) {
                val keys=TerminalInput.imeKeys(imeLine.edit(text,cursor),imeEnterSubmits)
                if(keys.isNotEmpty()) {
                    deliveringImeInput=true
                    try {onInput(keys)} finally {deliveringImeInput=false}
                }
            }
            fun flush() {
                if(!active()) return
                submit(editor.toString(),android.text.Selection.getSelectionEnd(editor).coerceIn(0,editor.length))
                BaseInputConnection.removeComposingSpans(editor)
                editor.clear();android.text.Selection.setSelection(editor,0);imeLine.reset()
                sync()
            }
            private fun commitPending() {
                if(!pendingCommit || batchDepth>0) {sync();return}
                pendingCommit=false
                val start=getComposingSpanStart(editor)
                val end=if(start<0) editor.length else start
                submit(editor.substring(0,end),if(start<0) android.text.Selection.getSelectionEnd(editor).coerceIn(0,end) else end)
                if(!keyboardSuggestions || editor.length>8192 || editor.any {it<' '}) {
                    editor.clear();android.text.Selection.setSelection(editor,0);imeLine.reset()
                }
                sync()
            }
            private fun edited() {
                if(getComposingSpanStart(editor)<0) {pendingCommit=true;commitPending()} else sync()
            }
            override fun beginBatchEdit(): Boolean {if(!active()) return false;batchDepth++;return true}
            override fun endBatchEdit(): Boolean {if(!active()) return false;if(batchDepth>0) batchDepth--;commitPending();return batchDepth>0}
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?,flags: Int): android.view.inputmethod.ExtractedText? {
                if(!active()) return null
                return android.view.inputmethod.ExtractedText().apply {
                    text=editor.toString();startOffset=0;partialStartOffset=-1;partialEndOffset=-1
                    selectionStart=android.text.Selection.getSelectionStart(editor);selectionEnd=android.text.Selection.getSelectionEnd(editor)
                }
            }
            override fun setSelection(start: Int,end: Int): Boolean {
                if(!active() || start !in 0..editor.length || end !in 0..editor.length) return false
                return super.setSelection(start,end).also {sync()}
            }
            override fun setComposingRegion(start: Int,end: Int): Boolean {
                if(!active()) return false
                return super.setComposingRegion(start,end).also {sync()}
            }
            override fun commitContent(info: android.view.inputmethod.InputContentInfo,flags: Int,opts: android.os.Bundle?): Boolean {
                if(!controlled || !interactive || imageTarget!=imageInputTarget() || imageMimeTypes.none {info.description.hasMimeType(it)}) return false
                val granted=flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION!=0
                try {if(granted) info.requestPermission()} catch(_: Exception) {return false}
                val released=java.util.concurrent.atomic.AtomicBoolean(false)
                val release={if(granted && released.compareAndSet(false,true)) runCatching {info.releasePermission()};Unit}
                return try {onImage(info.contentUri,release).also {if(!it) release()}} catch(_: Exception) {release();false}
            }
            override fun performContextMenuAction(id: Int): Boolean = if(id==android.R.id.paste || id==android.R.id.pasteAsPlainText) controlled && interactive && imageTarget==imageInputTarget() && onPaste() else super.performContextMenuAction(id)
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if(!active()) return false
                awake.activity();refreshCursorBlink()
                if(text!=null) {super.commitText(text,newCursorPosition);pendingCommit=true;commitPending()}
                return true
            }
            // Gboard's space-triggered autocorrection uses this API on Android 14+.
            // BaseInputConnection edits the buffer directly, bypassing commitText.
            @androidx.annotation.RequiresApi(34)
            override fun replaceText(start: Int,end: Int,text: CharSequence,newCursorPosition: Int,textAttribute: android.view.inputmethod.TextAttribute?): Boolean {
                if(!active()) return false
                awake.activity();refreshCursorBlink()
                val result=super.replaceText(start,end,text,newCursorPosition,textAttribute)
                if(result) {pendingCommit=true;commitPending()}
                return result
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if(!active()) return false
                awake.activity();refreshCursorBlink()
                super.setComposingText(text ?: "",newCursorPosition);sync();return true
            }
            override fun finishComposingText(): Boolean {
                if(!active()) return false
                super.finishComposingText();pendingCommit=true;commitPending();return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if(!active()) return false
                awake.activity();refreshCursorBlink()
                if(editor.isNotEmpty()) {super.deleteSurroundingText(beforeLength.coerceAtLeast(0),afterLength.coerceAtLeast(0));edited();return true}
                repeat(beforeLength.coerceIn(0,128)) {onInput("\u007f")}
                repeat(afterLength.coerceIn(0,128)) {onInput("\u001b[3~")};return true
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int,afterLength: Int): Boolean {
                if(!active()) return false
                if(editor.isNotEmpty()) return super.deleteSurroundingTextInCodePoints(beforeLength.coerceAtLeast(0),afterLength.coerceAtLeast(0)).also {edited()}
                return deleteSurroundingText(beforeLength,afterLength)
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if(!active()) return false
                if(event.action!=KeyEvent.ACTION_DOWN) return true
                if(editor.isNotEmpty() && event.keyCode in listOf(KeyEvent.KEYCODE_DEL,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    val start=android.text.Selection.getSelectionStart(editor);val end=android.text.Selection.getSelectionEnd(editor)
                    if(event.keyCode==KeyEvent.KEYCODE_DEL) {
                        if(start!=end) editor.delete(minOf(start,end),maxOf(start,end))
                        else if(start>0) editor.delete(Character.offsetByCodePoints(editor,start,-1),start)
                    } else {
                        val cursor=if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT) minOf(start,end) else maxOf(start,end)
                        val next=if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT && cursor>0) Character.offsetByCodePoints(editor,cursor,-1)
                            else if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT && cursor<editor.length) Character.offsetByCodePoints(editor,cursor,1) else cursor
                        android.text.Selection.setSelection(editor,next)
                    }
                    edited();return true
                }
                if(editor.isNotEmpty()) flushComposingText(restartIme=false)
                return onKeyDown(event.keyCode,event)
            }
            override fun performEditorAction(actionCode: Int): Boolean {
                if(!active()) return false
                batchDepth=0;finishComposingText();flush();onInput("\r");return true
            }
        }
        flushImeEditor={connection.flush()}
        if(pending.isNotEmpty()) connection.setComposingRegion(0,editor.length)
        // Run after IMM has accepted the connection, before retiring its predecessor.
        post {if(imeEditable===editor && controlled && hasFocus()) onInputConnectionReady()}
        return connection
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        awake.activity();refreshCursorBlink()
        if(event.isShiftPressed && keyCode in listOf(KeyEvent.KEYCODE_PAGE_UP,KeyEvent.KEYCODE_PAGE_DOWN)) {
            onScroll((if(keyCode==KeyEvent.KEYCODE_PAGE_UP) -1 else 1)*(screen?.rows ?: 24));return true
        }
        if (!controlled) return super.onKeyDown(keyCode, event)
        val named = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            KeyEvent.KEYCODE_DEL -> "backspace"; KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
            KeyEvent.KEYCODE_PAGE_UP -> "pageup"; KeyEvent.KEYCODE_PAGE_DOWN -> "pagedown"
            KeyEvent.KEYCODE_TAB -> "tab"; KeyEvent.KEYCODE_ESCAPE -> "esc"
            KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_MOVE_HOME -> "home"; KeyEvent.KEYCODE_MOVE_END -> "end"
            else -> null
        }
        if (named != null) { onInput(TerminalInput.key(named,event.isCtrlPressed,event.isAltPressed,event.isShiftPressed)); return true }
        val point = event.unicodeChar
        if (point != 0 && point and KeyCharacterMapCompat.COMBINING_ACCENT == 0) {
            onInput(TerminalInput.modified(String(Character.toChars(point)), event.isCtrlPressed, event.isAltPressed)); return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
private object KeyCharacterMapCompat { const val COMBINING_ACCENT: Int = Int.MIN_VALUE }

private fun rowContentHash(cells: List<TerminalCell>): Int {
    var hash=cells.size
    for(cell in cells) {
        hash=31*hash+cell.x
        hash=31*hash+cell.width
        hash=31*hash+cell.attributes
        hash=31*hash+cell.foreground
        hash=31*hash+cell.background
        hash=31*hash+cell.text.hashCode()
        hash=31*hash+(cell.hyperlink?.hashCode() ?: 0)
    }
    return hash
}
