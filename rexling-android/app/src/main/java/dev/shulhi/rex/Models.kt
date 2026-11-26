package dev.shulhi.rex

import org.json.JSONArray
import org.json.JSONObject

data class SessionItem(val id: String, val label: String)
data class SessionDetail(val attachment: String, val tabs: Int, val panes: Int)
data class BlockItem(val id: String, val label: String)
data class WindowItem(val id: String, val label: String, val blocks: List<BlockItem>,val layout: List<PanePlacement> = emptyList(),val splits: List<PaneSplitLine> = emptyList(),
    /** The pane Rex shows over the whole window, or blank. */ val zoomed: String="")
data class TerminalScroll(val total: Long=0,val offset: Long=0,val visible: Long=0) {
    val available: Long get()=(total-visible).coerceAtLeast(0)
    val below: Long get()=(available-offset).coerceAtLeast(0)
}
data class TerminalCell(val x: Int, val y: Int, val text: String, val foreground: Int, val background: Int, val attributes: Int, val width: Int, val hyperlink: String?=null)
class TerminalImage(val id: Long,val generation: Long,val width: Int,val height: Int,internal val bitmap: android.graphics.Bitmap?)
data class TerminalImagePlacement(val image: TerminalImage,val layer: Int,val z: Int,val col: Int,val row: Int,
    val offsetX: Int,val offsetY: Int,val width: Int,val height: Int,val sourceX: Int,val sourceY: Int,val sourceWidth: Int,val sourceHeight: Int)
data class TerminalScreen(val columns: Int, val rows: Int, val cursorX: Int?, val cursorY: Int?, val foreground: Int, val background: Int, val cells: List<TerminalCell>, val scroll: TerminalScroll = TerminalScroll(), val lines: List<List<TerminalCell>> = cells.groupBy {it.y}.let {groups->List(rows){groups[it].orEmpty()}}, val inputFlags: Int=0,val images: List<TerminalImagePlacement> = emptyList(),val imageCellWidth: Int=8,val imageCellHeight: Int=16,val above: List<List<TerminalCell>> = emptyList(),val below: List<List<TerminalCell>> = emptyList(),val cursorStyle: Int=1,val cursorBlinking: Boolean=false,val cursorAnchorX: Int?=null,val cursorAnchorY: Int?=null) {
    val hasSemanticPrompt: Boolean get()=inputFlags and 32!=0
    fun isSemanticInputRow(row: Int): Boolean = inputFlags and 16!=0 && scroll.below==0L &&
        row in lines.indices && lines[row].any {it.attributes and (1 shl 30)!=0}
    val mouseTracking: Boolean get()=inputFlags and 1!=0
    val alternateScreen: Boolean get()=inputFlags and 2!=0
    val applicationScroll: Boolean get()=inputFlags and 1!=0 || inputFlags and 6==6
    fun plainText(): String = (0 until rows).joinToString("\n") { y ->
        cells.asSequence().filter { it.y == y && it.width > 0 }.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()
    }.trimEnd()
    companion object {
        fun parse(value: JSONObject): TerminalScreen {
            val columns = value.getInt("columns").coerceIn(1, 65535)
            val rows = value.getInt("rows").coerceIn(1, 65535)
            val cursor = value.optJSONArray("cursor")
            val position=value.optJSONArray("cursor_position")
            val cells = value.optJSONArray("cells").objects { cell ->
                val a = cell as JSONArray
                TerminalCell(a.getInt(0), a.getInt(1), a.getString(2), argb(a.getInt(3)), argb(a.getInt(4)), a.getInt(5), a.getInt(6),if(a.isNull(7)) null else a.optString(7).takeIf {it.isNotEmpty()})
            }
            val scroll=value.optJSONArray("scrollbar")
            return TerminalScreen(columns, rows, cursor?.getInt(0), cursor?.getInt(1), argb(value.getInt("foreground")), argb(value.getInt("background")), cells,
                TerminalScroll(scroll?.optLong(0) ?: 0,scroll?.optLong(1) ?: 0,scroll?.optLong(2) ?: 0),inputFlags=value.optInt("input_flags"),cursorAnchorX=position?.getInt(0),cursorAnchorY=position?.getInt(1))
        }
    }
}
data class TerminalClipboardWrite(val serial: Long,val text: String,val block: String,val connection: Long,val navigation: Long,val local: Boolean=false)
data class ProgramResumeStatus(val state: String,val program: String,val reason: String)

data class UndoTerminalClose(val serial: Long,val tab: Boolean)

data class AppState(
    val terminalProgress: Map<String,TerminalProgress> = emptyMap(),
    val programs: Map<String, ProgramIdentity> = emptyMap(),
    val workingBlocks: Set<String> = emptySet(),
    internal val activity: List<ActivityRecord> = emptyList(),
    val activityLoading: Boolean=false,val activityError: String="",val activityPartial: Boolean=false,
    val titles: Map<String, String> = emptyMap(),
    val directories: Map<String, String> = emptyMap(),
    val mirrorWaiting: Set<String> = emptySet(),
    val previewFrames: Map<String, TerminalScreen> = emptyMap(), val sessionDetails: Map<String, SessionDetail> = emptyMap(),
    internal val paneCatalog: PaneCatalogSnapshot? = null, internal val paneCatalogLoading: Boolean = false,
    val revision: Long = 0, val phase: String = "disconnected", val endpoint: String = "",
    internal val clipboardWrite: TerminalClipboardWrite?=null,
    val identity: String = "", val serverVersion: String = "", val error: String = "", val notice: String = "",
    val supportsProgramResume: Boolean=false,val programResume: ProgramResumeStatus?=null,
    val supportsUndoClose: Boolean=false,val undoClose: UndoTerminalClose?=null,
    val sessions: List<SessionItem> = emptyList(), val windows: List<WindowItem> = emptyList(),
    val selectedSession: String = "", val selectedBlock: String = "", val controlled: Boolean = false,
    val screen: TerminalScreen? = null, val creating: Boolean = false, val navigationId: Long = 0,val tabMoveId: Long=0,val paneMoveId: Long=0,val connectionEpoch: Long=0,val otherOwner: Boolean=false,val attentionCount: Int=0,
    internal val staleSession: String = "",
    internal val cachedPrograms: Set<String> = emptySet(),
    internal val awaitingPaneSelection: Boolean = false,
) {
    val connected: Boolean get() = phase == "connected"
    companion object {
        fun parse(text: String,previousActivity: List<ActivityRecord> = emptyList()): AppState {
            val root = JSONObject(text)
            return AppState(
                workingBlocks = root.optJSONArray("working").objects {it as String}.toSet(),
                activity=if(root.has("activity")) root.optJSONArray("activity").objects {ActivityRecord.parse(it as JSONObject)}.filterNotNull().take(1024) else previousActivity,
                activityLoading=root.optBoolean("activityLoading"),activityError=root.optString("activityError"),activityPartial=root.optBoolean("activityPartial"),
                titles = root.optJSONObject("titles")?.let { titles -> titles.keys().asSequence().associateWith { id ->
                    titles.optString(id).filterNot { it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt() }.take(256)
                } } ?: emptyMap(),
                directories = root.optJSONObject("directories")?.let { directories -> directories.keys().asSequence().associateWith { id ->
                    directories.optString(id).filterNot { it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt() }.take(1024)
                } } ?: emptyMap(),
                terminalProgress=root.optJSONObject("terminalProgress")?.let {reports->reports.keys().asSequence().mapNotNull {id->TerminalProgress.parse(reports.optJSONObject(id))?.let {id to it}}.toMap()} ?: emptyMap(),
                programs = root.optJSONObject("programs")?.let { programs -> programs.keys().asSequence().associateWith { id ->
                    ProgramIdentity.resolve(programs.optJSONArray(id).objects { it as String })
                } } ?: emptyMap(),
                staleSession=root.optString("staleSession"), awaitingPaneSelection=root.optBoolean("awaitingPaneSelection"),
                mirrorWaiting=root.optJSONArray("mirrorWaiting").objects {it as String}.toSet(),
                previewFrames = root.optJSONObject("previews")?.let { frames -> frames.keys().asSequence().associateWith { TerminalScreen.parse(frames.getJSONObject(it)) } } ?: emptyMap(),
                sessionDetails = root.optJSONObject("sessionDetails")?.let { details -> details.keys().asSequence().associateWith { key ->
                    val d=details.getJSONObject(key);SessionDetail(d.optString("attachment"),d.optInt("tabs"),d.optInt("panes")) } } ?: emptyMap(),
                paneCatalog=root.optJSONObject("paneCatalog")?.let(PaneCatalogSnapshot::parse),paneCatalogLoading=root.optBoolean("paneCatalogLoading"),
                revision = root.optLong("revision"), navigationId = root.optLong("navigationId"),tabMoveId=root.optLong("tabMoveId"),paneMoveId=root.optLong("paneMoveId"),connectionEpoch=root.optLong("connectionEpoch"),otherOwner=root.optBoolean("otherOwner"),attentionCount=root.optInt("attentionCount").coerceIn(0,1024), phase = root.optString("phase", "disconnected"),
                clipboardWrite=root.optJSONObject("clipboard")?.let {TerminalClipboardWrite(it.optLong("serial"),it.optString("text"),it.optString("block"),it.optLong("connection"),it.optLong("navigation"),it.optBoolean("local"))},
                endpoint = root.optString("endpoint"), identity = root.optString("identity"),
                serverVersion=root.optString("serverVersion"),
                error = root.optString("error"), notice = root.optString("notice"),
                supportsProgramResume=root.optBoolean("supportsProgramResume"),
                programResume=root.optJSONObject("programResume")?.let {
                    ProgramResumeStatus(it.optString("state"),it.optString("program"),it.optString("reason"))
                },
                supportsUndoClose=root.optBoolean("supportsUndoClose"),
                undoClose=root.optJSONObject("undoClose")?.let {UndoTerminalClose(it.getLong("serial"),it.optBoolean("tab"))},
                sessions = root.optJSONArray("sessions").objects { value ->
                    val s = value as JSONObject; SessionItem(s.getString("session_id"), s.optString("label", "Session"))
                },
                windows = root.optJSONArray("windows").objects { value ->
                    val w = value as JSONObject
                    val blocks=w.optJSONArray("blocks").objects { item ->
                        val b = item as JSONObject; BlockItem(b.getString("id"), b.optString("label", "Terminal"))
                    }
                    val ids=blocks.mapTo(hashSetOf()) {it.id}
                    val layout=w.optJSONArray("layout").objects {PanePlacement.parse(it as? JSONObject)}.filterNotNull()
                        .filter {it.id in ids}.sortedBy {it.layer}.asReversed().distinctBy {it.id}.asReversed()
                    val splits=w.optJSONArray("splits").objects {PaneSplitLine.parse(it as? JSONObject)}.filterNotNull()
                    WindowItem(w.optString("id"), w.optString("label", "Window"),blocks,layout,splits,w.optString("zoomed"))
                },
                selectedSession = root.optString("selectedSession"), selectedBlock = root.optString("selectedBlock"),
                controlled = root.optBoolean("controlled"), creating = root.optBoolean("creating"), screen = root.optJSONObject("screen")?.let(TerminalScreen::parse),
            )
        }
    }
}
private fun argb(rgb: Int): Int = rgb or (0xff shl 24)
internal fun <T> JSONArray?.objects(map: (Any) -> T): List<T> = if (this == null) emptyList() else (0 until length()).map { map(get(it)) }

object TerminalInput {
    // Shift+Enter has no legacy encoding (it would be a plain \r). The engine
    // turns this kitty sequence into a real Shift+Enter key event, which Rex
    // then encodes for whatever keyboard mode the program enabled.
    const val SHIFT_ENTER = "\u001b[13;2u"
    /**
     * Keys from an on-screen keyboard commit. In a multi-line field Gboard's Enter commits a lone
     * "\n" (LF, Ctrl+J), which TUIs such as Claude Code read as "insert newline" rather than submit.
     * With [enterSubmits], a lone newline is the Enter key and becomes CR. Longer commits (pasted
     * text) keep their LFs so a multi-line paste does not submit every line.
     */
    fun imeKeys(keys: String,enterSubmits: Boolean): String = if(enterSubmits && keys=="\n") "\r" else keys
    private val keys=mapOf(
        "esc" to "\u001b", "tab" to "\t", "enter" to "\r", "backspace" to "\u007f",
        "pageup" to "\u001b[5~", "pagedown" to "\u001b[6~",
        "up" to "\u001b[A", "down" to "\u001b[B", "right" to "\u001b[C", "left" to "\u001b[D",
        "insert" to "\u001b[2~", "delete" to "\u001b[3~", "home" to "\u001b[H", "end" to "\u001b[F",
        "f1" to "\u001bOP", "f2" to "\u001bOQ", "f3" to "\u001bOR", "f4" to "\u001bOS",
        "f5" to "\u001b[15~", "f6" to "\u001b[17~", "f7" to "\u001b[18~", "f8" to "\u001b[19~",
        "f9" to "\u001b[20~", "f10" to "\u001b[21~", "f11" to "\u001b[23~", "f12" to "\u001b[24~")
    fun modified(text: String, control: Boolean, alt: Boolean, shift: Boolean=false): String {
        keys.entries.firstOrNull {it.value==text}?.let {return key(it.key,control,alt,shift)}
        val shifted=if(shift && !text.startsWith("\u001b")) text.map {c->
            val plain="`1234567890-=[]\\;',./"
            val upper="~!@#$%^&*()_+{}|:\"<>?"
            val i=plain.indexOf(c)
            when {i>=0->upper[i];c in 'a'..'z'->c.uppercaseChar();else->c}
        }.joinToString("") else text
        val input=if(control && shifted.length==1 && shifted[0].code in 0x40..0x7f)
            ((shifted[0].code and 0x1f).toChar()).toString() else shifted
        return if(alt) "\u001b$input" else input
    }
    fun key(name: String, control: Boolean=false, alt: Boolean=false, shift: Boolean=false): String {
        val sequence=keys[name] ?: return ""
        val modifier=1+(if(shift) 1 else 0)+(if(alt) 2 else 0)+(if(control) 4 else 0)
        if(modifier==1) return sequence
        if(name=="enter" && modifier==2) return SHIFT_ENTER
        if(name=="tab" && shift) return (if(alt) "\u001b" else "")+"\u001b[Z"
        if(sequence.startsWith("\u001b[") || sequence.startsWith("\u001bO")) {
            val body=sequence.substring(2)
            return if(body.endsWith("~")) "\u001b[${body.dropLast(1)};$modifier~" else "\u001b[1;$modifier${body.last()}"
        }
        val input=if(control && name=="backspace") "\b" else sequence
        return if(alt) "\u001b$input" else input
    }
}

/** A short tap latches one Shift; holding keeps it active until release. */
internal data class ShiftKeyState(val latched: Boolean=false,val held: Boolean=false,val used: Boolean=false) {
    val active: Boolean get()=latched || held
    fun press()=copy(held=true,used=false)
    fun release(cancelled: Boolean=false)=if(!held) this else ShiftKeyState(latched=if(cancelled || used) false else !latched)
    fun consume()=copy(latched=false,used=held)
}
