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
    var interactive = true
        set(value) {if(field!=value) {field=value;if(!value) clearSelection()}}
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
    var onScroll: (Int) -> Unit = {}
    var onWheel: (Int,Int,Int,Int,Int) -> Unit = {lines,_,_,_,_->onScroll(lines)}
    var onActivate: () -> Unit = {}
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
    internal var onKeyboardFocus: (TerminalKeyboardFocus?)->Unit={}
    internal val keyboardFocus: TerminalKeyboardFocus? get() {
        val frame=screen ?: return null
        if(frame.scroll.below>0) return null
        val row=frame.cursorY?.takeIf {it in 0 until frame.rows} ?: return null
        val lastText=frame.lines.indexOfLast {cells->cells.any {cell->cell.width>0 && cell.text.any {it!='\u0000' && !it.isWhitespace()}}}
        val bottom=insetTop+(row+1)*cellHeight+insetBottom
        return TerminalKeyboardFocus(insetTop+row*cellHeight,bottom,
            max(bottom,insetTop+(lastText+1)*cellHeight+insetBottom))
    }
    private fun reportCursorViewport() {onKeyboardFocus(keyboardFocus)}
    private var frameTraceToken: String?=null
    internal fun presentFrame(value: TerminalScreen?,token: String?=null) {setScreen(value,true);frameTraceToken=token}
    private fun setScreen(value: TerminalScreen?,onDisplayFrame: Boolean) {
        if(currentScreen===value) return
        val columns=currentScreen?.columns;currentScreen=value;frameTraceToken=null
        if(columns!=value?.columns) configureRendering()
        reportCursorViewport()
        if(onDisplayFrame) invalidate() else postInvalidateOnAnimation()
    }
    var keyboardSuggestions: Boolean = true
        set(value) {
            if(field!=value) {field=value;if(controlled && hasFocus()) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)}
        }
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
    private val fonts = TerminalFonts(context)
    private val terminalImages=TerminalImages()
    private data class RecordedRow(val cells: List<TerminalCell>,val recording: TerminalRowRecording,val links: List<TerminalLink>)
    private val linkIndex=TerminalLinks.Index()
    private var recorded= arrayOfNulls<RecordedRow>(0)
    private var rowGeometry=""
    private val advances=object: LinkedHashMap<Pair<Int,String>,Float>(256,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int,String>,Float>?)=size>4096
    }
    internal var recordedRowCount=0;private set
    private fun clearRows() {recorded.forEach {it?.recording?.release()};recorded=arrayOfNulls(0);rowGeometry=""}
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
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {clearSelection();configureRendering();reportDimensions();reportCursorViewport()}
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
        if(frame.images.isEmpty()) {
        for(y in 0 until visibleRows) {
            val cells=frame.lines[y]
            var row=recorded[y]
            val links=rowLinks[y]
            if(row==null || !row.recording.valid || row.cells!=cells || row.links!=links) {
                val origin=if(y==0) 0f else insetTop+y*cellHeight
                val top=if(y==0) insetTop else 0f
                val bottom=if(y==visibleRows-1) height-origin else top+cellHeight
                val recording=row?.recording ?: TerminalRowRecording.create(canvas.isHardwareAccelerated)
                recording.record(width,bottom.toInt().coerceAtLeast(1)) { target ->
                    drawRow(target,cells,visibleColumns,top,bottom)
                    target.save();target.clipRect(insetHorizontal,top,width-insetHorizontal,top+cellHeight)
                    backgroundPaint.color=palette.accent;backgroundPaint.strokeWidth=resources.displayMetrics.density.coerceAtLeast(1f)
                    for(link in links) target.drawLine(insetHorizontal+link.start*cellWidth,top+baseline+2,
                        insetHorizontal+link.end*cellWidth,top+baseline+2,backgroundPaint)
                    target.restore()
                }
                row=RecordedRow(cells,recording,links);recorded[y]=row;recordedRowCount++
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
                    drawRow(canvas,frame.lines[y],visibleColumns,top,bottom,pass)
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
        canvas.save()
        canvas.clipRect(insetHorizontal,insetTop,width-insetHorizontal,height-insetBottom)
        canvas.translate(insetHorizontal,insetTop)
        if (selection==null && frame.cursorX != null && frame.cursorY != null) {
            val cell=frame.lines.getOrNull(frame.cursorY)?.firstOrNull {it.x==frame.cursorX && it.width>0}
            val x = frame.cursorX * cellWidth; val y = frame.cursorY * cellHeight
            val cursorWidth=max(1,cell?.width ?: 1)*cellWidth
            backgroundPaint.color=palette.accent
            canvas.drawRect(x,y,x+cursorWidth,y+cellHeight,backgroundPaint)
            if(cell!=null && cell.text.isNotEmpty() && cell.attributes and 16==0) {
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
    private fun drawRow(canvas: Canvas,cells: List<TerminalCell>,columns: Int,top: Float,bottom: Float,pass: Int=0) {
        var start=0;var end=0;var color=0
        fun flush() {
            if(pass==2 || end<=start || start>=columns || color==palette.background) return
            backgroundPaint.color=color
            canvas.drawRect(if(start==0) 0f else insetHorizontal+start*cellWidth,0f,
                if(end>=columns) width.toFloat() else insetHorizontal+end*cellWidth,bottom,backgroundPaint)
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
            paint.color=if(cell.attributes and 8!=0) palette.background(cell) else palette.foreground(cell)
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
    private fun scrollByPixels(pixels: Float) {
        scrollPixels+=pixels
        val lines=(scrollPixels/cellHeight).toInt()
        if(lines!=0) {
            scrollPixels-=lines*cellHeight
            if(screen?.applicationScroll==true && screen?.scroll?.below==0L) onWheel(lines,wheelCol,wheelRow,desiredCellWidth.roundToInt(),desiredCellHeight.roundToInt())
            else if((screen?.scroll?.available ?: 0)>0) onScroll(lines)
        }
    }
    override fun computeScroll() {
        if(scrollFling.computeScrollOffset()) {
            val y=scrollFling.currY;scrollByPixels((y-lastFlingY).toFloat());lastFlingY=y
            postInvalidateOnAnimation()
        }
    }
    override fun onDetachedFromWindow() {terminalImages.clear();clearRows();clearSelection();scrollFling.abortAnimation();touchVelocity?.recycle();touchVelocity=null;super.onDetachedFromWindow()}
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if(selection!=null && event.actionMasked==MotionEvent.ACTION_SCROLL) return true
        if(interactive && event.actionMasked==MotionEvent.ACTION_SCROLL) {
            val lines=(-event.getAxisValue(MotionEvent.AXIS_VSCROLL)*3).roundToInt()
            if(lines!=0) {
                if(screen?.applicationScroll==true && screen?.scroll?.below==0L && event.metaState and KeyEvent.META_SHIFT_ON==0)
                    onWheel(lines,((event.x-insetHorizontal)/desiredCellWidth).toInt(),((event.y-insetTop)/desiredCellHeight).toInt(),desiredCellWidth.roundToInt(),desiredCellHeight.roundToInt())
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
        val url=selectionLink?.url?.let(TerminalLinks::webUrl) ?: return false
        when(action) {
            TerminalLinkAction.OPEN->onOpenLink(url)
            TerminalLinkAction.COPY_ADDRESS->(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Web link",url))
            TerminalLinkAction.SHARE->onShareLink(url)
        }
        clearSelection();return true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!interactive) return false
        if(selectionTouch || selection!=null) return selectionEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {touchVelocity?.recycle();touchVelocity=VelocityTracker.obtain()}
        val sample=MotionEvent.obtain(event)
        sample.offsetLocation(event.rawX-event.x,event.rawY-event.y)
        touchVelocity?.addMovement(sample);sample.recycle()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {pressedMouseTarget=mouseTarget();pressedMouseGrid=screen?.let {it.columns to it.rows};pressedMouse=screen?.mouseTracking==true;pressedControlled=controlled;pressedLink=linkAt(event.x,event.y);pressX=event.x;pressY=event.y;removeCallbacks(longPress);postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong());wheelCol=((event.x-insetHorizontal)/desiredCellWidth).toInt();wheelRow=((event.y-insetTop)/desiredCellHeight).toInt();scrollFling.abortAnimation();startX=event.rawX;startY=event.rawY;lastScrollY=event.rawY;scrollPixels=0f;scrolling=false;dragging=false;paneGesture.begin(event.x, event.y)}
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) { removeCallbacks(longPress);paneGesture.cancel();onPaneDrag?.invoke(0f,false);dragging=false;scrolling=false }
                else {
                    paneGesture.move(event.x,event.y)
                    val dx=event.rawX-startX;val dy=event.rawY-startY
                    val slop=ViewConfiguration.get(context).scaledTouchSlop
                    if(kotlin.math.abs(dx)>slop || kotlin.math.abs(dy)>slop) removeCallbacks(longPress)
                    if(!scrolling && kotlin.math.abs(dx)>slop && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f) dragging=true
                    if(!dragging && kotlin.math.abs(dy)>slop && kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.5f) {scrolling=true;paneGesture.cancel()}
                    if(scrolling) {scrollByPixels(lastScrollY-event.rawY);lastScrollY=event.rawY}
                    else if(dragging) onPaneDrag?.invoke(dx,true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {pressedLink=null;removeCallbacks(longPress);paneGesture.cancel();onPaneDrag?.invoke(0f,false);dragging=false;scrolling=false}
            MotionEvent.ACTION_UP -> if(scrolling) {
                touchVelocity?.computeCurrentVelocity(1000)
                val velocity=-(touchVelocity?.yVelocity ?: 0f).toInt()
                val history=screen?.scroll
                if(history!=null && history.available>0) {
                    val minimum=-(history.offset.toDouble()*cellHeight).coerceAtMost(1000000.0).toInt()
                    val maximum=(history.below.toDouble()*cellHeight).coerceAtMost(1000000.0).toInt()
                    lastFlingY=0;scrollFling.fling(0,0,0,velocity,0,0,minimum,maximum);postInvalidateOnAnimation()
                }
                scrolling=false;paneGesture.cancel()
            } else if(dragging && onPaneDragEnd!=null) {
                touchVelocity?.computeCurrentVelocity(1000)
                onPaneDragEnd?.invoke(event.rawX-startX,touchVelocity?.xVelocity ?: 0f);dragging=false;paneGesture.cancel()
            } else when (paneGesture.finish(event.x, event.y)) {
                PaneGesture.Result.TAP -> {val link=pressedLink;if(link==null) terminalTap(event.x,event.y) else if(link==linkAt(event.x,event.y)) onOpenLink(link.url)}
                PaneGesture.Result.NEXT -> onPaneSwipe(1)
                PaneGesture.Result.PREVIOUS -> onPaneSwipe(-1)
                PaneGesture.Result.NONE -> Unit
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {pressedLink=null;removeCallbacks(longPress);touchVelocity?.recycle();touchVelocity=null}
        return true
    }
    private fun terminalTap(x: Float,y: Float) {
        val frame=screen
        if(pressedMouse && pressedControlled && !controlled) return
        if(controlled && (pressedMouse || frame?.mouseTracking==true)) {
            if(frame?.mouseTracking!=true || !pressedMouse || !pressedControlled || frame.scroll.below>0 ||
                pressedMouseTarget!=mouseTarget() || pressedMouseGrid!=(frame.columns to frame.rows)) return
            val col=floor((x-insetHorizontal)/cellWidth).toInt()
            val row=floor((y-insetTop)/cellHeight).toInt()
            if(x<insetHorizontal || x>=width-insetHorizontal || y<insetTop || y>=height-insetBottom ||
                col !in 0 until frame.columns || row !in 0 until frame.rows) return
            super.performClick()
            onMouseClick(col,row,cellWidth.roundToInt().coerceAtLeast(1),cellHeight.roundToInt().coerceAtLeast(1))
            if(frame.isSemanticInputRow(row)) keyboard()
            else if(frame.cursorX!=null && row==frame.cursorY) onCursorTap()
        } else if(controlled && frame?.hasSemanticPrompt==true) {
            val row=floor((y-insetTop)/cellHeight).toInt()
            if(pressedMouseTarget!=mouseTarget() || pressedMouseGrid!=(frame.columns to frame.rows) ||
                x<insetHorizontal || x>=width-insetHorizontal || y<insetTop || y>=height-insetBottom) return
            super.performClick()
            if(frame.isSemanticInputRow(row)) keyboard()
            else if(frame.scroll.below==0L && frame.cursorX!=null && row==frame.cursorY) onCursorTap()
        } else performClick()
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
                if(selectionLink!=null) {
                    menu.add(0,0x524501,2,"Open link").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                    menu.add(0,0x524502,3,"Copy link address").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    menu.add(0,0x524503,4,"Share link").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
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
    override fun performClick(): Boolean { super.performClick();if(interactive) {if(controlled) keyboard() else onActivate()};return true }
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
            private var sentText=""
            private var sentCursor=0
            private fun active()=controlled && interactive && imageTarget==imageInputTarget() && imeEditable===editor
            override fun getEditable(): android.text.Editable = editor
            private fun sync() {
                val text=editor.toString()
                composing=if(text.startsWith(sentText) && sentCursor==sentText.length) text.substring(sentText.length) else ""
                composingCursor=(android.text.Selection.getSelectionEnd(editor)-sentText.length).coerceIn(0,composing.length)
                invalidate()
                if(batchDepth==0) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).updateSelection(this@TerminalView,
                    android.text.Selection.getSelectionStart(editor),android.text.Selection.getSelectionEnd(editor),
                    getComposingSpanStart(editor),getComposingSpanEnd(editor))
            }
            private fun submit(text: String,cursor: Int) {
                if(text==sentText && cursor==sentCursor) return
                var prefix=0
                while(prefix<minOf(sentText.length,text.length) && sentText[prefix]==text[prefix]) prefix++
                if(prefix>0 && prefix<sentText.length && Character.isLowSurrogate(sentText[prefix])) prefix--
                val keys=buildString {
                    repeat(sentText.codePointCount(sentCursor,sentText.length)) {append("\u001b[C")}
                    repeat(sentText.codePointCount(prefix,sentText.length)) {append('\u007f')}
                    append(text.substring(prefix))
                    repeat(text.codePointCount(cursor,text.length)) {append("\u001b[D")}
                }
                sentText=text;sentCursor=cursor
                if(keys.isNotEmpty()) {
                    deliveringImeInput=true
                    try {onInput(keys)} finally {deliveringImeInput=false}
                }
            }
            fun flush() {
                if(!active()) return
                submit(editor.toString(),android.text.Selection.getSelectionEnd(editor).coerceIn(0,editor.length))
                BaseInputConnection.removeComposingSpans(editor)
                editor.clear();android.text.Selection.setSelection(editor,0);sentText="";sentCursor=0
                sync()
            }
            private fun commitPending() {
                if(!pendingCommit || batchDepth>0) {sync();return}
                pendingCommit=false
                val start=getComposingSpanStart(editor)
                val end=if(start<0) editor.length else start
                submit(editor.substring(0,end),if(start<0) android.text.Selection.getSelectionEnd(editor).coerceIn(0,end) else end)
                if(!keyboardSuggestions || editor.length>8192 || editor.any {it<' '}) {
                    editor.clear();android.text.Selection.setSelection(editor,0);sentText="";sentCursor=0
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
                if(text!=null) {super.commitText(text,newCursorPosition);pendingCommit=true;commitPending()}
                return true
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if(!active()) return false
                super.setComposingText(text ?: "",newCursorPosition);sync();return true
            }
            override fun finishComposingText(): Boolean {
                if(!active()) return false
                super.finishComposingText();pendingCommit=true;commitPending();return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if(!active()) return false
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
        return connection
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
