package dev.shulhi.rex

import java.net.URI

internal data class TerminalLink(val row: Int,val start: Int,val end: Int,val url: String,val explicit: Boolean=false) {
    fun contains(column: Int)=column>=start && column<end
}

/** Links retain physical cell hit ranges while genuine soft wraps share one URL. */
internal object TerminalLinks {
    const val WRAP=1 shl 27
    const val CONTINUATION=1 shl 28
    const val ROW_METADATA=1 shl 29
    private val candidate=Regex("""(?i)(?<![\p{L}\p{N}_@/])(?:https?://|www\.|file://)[^\s\p{Cc}<>"`“”]+|(?<![\p{L}\p{N}_@/:~])/(?:[\p{L}\p{N}_.@+~-]+/)*[\p{L}\p{N}_.@+~-]+\.[\p{L}\p{N}]+(?![\p{L}\p{N}_@+~/%-])""")
    fun webUrl(value: String): String? {
        val normalized=if(value.startsWith("www.",true)) "https://$value" else value
        val uri=runCatching {URI(normalized)}.getOrNull() ?: return null
        return normalized.takeIf {(uri.scheme.equals("https",true) || uri.scheme.equals("http",true)) && !uri.host.isNullOrBlank()}
    }

    /** File links resolve on the terminal server and are never dispatched to Android as file URIs. */
    fun filePath(value: String): String? {
        if(!value.startsWith("file:/",ignoreCase=true)) return null
        val uri=runCatching {URI(value)}.getOrNull() ?: return null
        if(uri.rawQuery!=null || uri.rawUserInfo!=null || uri.port!=-1 ||
            (uri.rawAuthority!=null && uri.host==null)) return null
        return uri.path?.takeIf {it.startsWith('/') && !it.endsWith('/') && it.none(Char::isISOControl)}
    }

    fun openableUrl(value: String): String?=webUrl(value) ?: value.takeIf {filePath(it)!=null}

    private data class Position(val row: Int,val start: Int,val end: Int)
    /** Conservative allocation-free scan of the same logical text used below.
     * Keep state across wrapped rows/cells, but break it at gaps, hidden or OSC 8 cells.
     */
    private fun hasLinkPrefix(source: List<List<TerminalCell>>,columns: Int): Boolean {
        var tail=0L
        for(cells in source) {
            var column=0
            for(cell in cells) {
                if(cell.width<=0 || cell.x<column || cell.x>=columns) continue
                if(cell.x>column) tail=0L
                if(cell.attributes and 16!=0 || cell.hyperlink!=null || cell.text.isEmpty()) tail=0L
                else for(char in cell.text) {
                    val previous=(tail and 0xff).toInt().toChar()
                    if(char=='/' && !previous.isLetterOrDigit() && previous !in "_@/:~") return true
                    val code=when(char) {in 'A'..'Z'->char.code+32;in '\u0000'..'\u007f'->char.code;else->0}
                    tail=(tail shl 8) or code.toLong()
                    if(tail==0x68747470733a2f2fL || tail and 0x00ffffffffffffffL==0x687474703a2f2fL ||
                        tail and 0xffffffffL==0x7777772eL || tail and 0x00ffffffffffffffL==0x66696c653a2f2fL) return true
                }
                column=cell.x+cell.width
            }
        }
        return false
    }
    private fun detect(source: List<List<TerminalCell>>,firstRow: Int,columns: Int,partialStart: Boolean,partialEnd: Boolean): List<TerminalLink> {
        val explicit=ArrayList<TerminalLink>()
        source.forEachIndexed {offset,cells->
            val row=firstRow+offset
            for(cell in cells) {
                if(cell.width<=0 || cell.x<0 || cell.x>=columns || cell.attributes and 16!=0) continue
                val uri=cell.hyperlink?.let(::openableUrl) ?: continue
                val previous=explicit.lastOrNull()
                if(previous!=null && previous.row==row && previous.end==cell.x && previous.url==uri)
                    explicit[explicit.lastIndex]=previous.copy(end=(cell.x+cell.width).coerceAtMost(columns))
                else explicit.add(TerminalLink(row,cell.x,(cell.x+cell.width).coerceAtMost(columns),uri,true))
            }
        }
        if(!hasLinkPrefix(source,columns)) return if(explicit.isEmpty()) emptyList() else explicit.sortedWith(compareBy({it.row},{it.start}))
        val text=StringBuilder();val positions=ArrayList<Position>()
        source.forEachIndexed {offset,cells->
            val row=firstRow+offset
            var column=0
            fun append(value: String,start: Int,end: Int) {
                text.append(value);repeat(value.length) {positions.add(Position(row,start,end))}
            }
            for(cell in cells) {
                if(cell.width<=0 || cell.x<column || cell.x>=columns) continue
                while(column<cell.x) {append(" ",column,column+1);column++}
                append(if(cell.attributes and 16!=0 || cell.hyperlink!=null) " " else cell.text.ifEmpty {" "},cell.x,(cell.x+cell.width).coerceAtMost(columns))
                column=cell.x+cell.width
            }
        }
        val inferred=ArrayList<TerminalLink>()
        for(match in candidate.findAll(text)) {
            var value=match.value
            while(value.isNotEmpty()) {
                val last=value.last();val pair=when(last) {')'->'(';']'->'[';'}'->'{';else->null}
                if(last in ".,;!:'’" || (pair!=null && value.count {it==last}>value.count {it==pair})) value=value.dropLast(1) else break
            }
            val url=if(value.startsWith('/')) URI("file","",value,null).toASCIIString() else openableUrl(value) ?: continue
            val first=positions[match.range.first];val last=positions[match.range.first+value.length-1]
            if(partialStart && first.row==firstRow && first.start==0) continue
            if(partialEnd && last.row==firstRow+source.lastIndex && last.end>=columns) continue
            for(index in match.range.first until match.range.first+value.length) {
                val at=positions[index];val previous=inferred.lastOrNull()
                if(previous!=null && previous.row==at.row && previous.url==url && at.start<=previous.end)
                    inferred[inferred.lastIndex]=previous.copy(end=maxOf(previous.end,at.end))
                else inferred.add(TerminalLink(at.row,at.start,at.end,url))
            }
        }
        return (explicit+inferred).sortedWith(compareBy({it.row},{it.start}))
    }
    fun row(cells: List<TerminalCell>,row: Int,columns: Int): List<TerminalLink> =
        detect(listOf(cells.sortedBy {it.x}),row,columns,false,false)

    /** Cache whole logical lines, so changed tails update unchanged URL prefixes. */
    class Index {
        private data class Group(val source: List<List<TerminalCell>>,val columns: Int,val partialStart: Boolean,val partialEnd: Boolean,val links: List<TerminalLink>)
        private var frame: TerminalScreen?=null
        private var groups=emptyMap<Int,Group>()
        private var rows=emptyList<List<TerminalLink>>()
        fun update(next: TerminalScreen): List<List<TerminalLink>> {
            if(next===frame) return rows
            val result=MutableList<List<TerminalLink>>(next.rows) {emptyList()};val updated=HashMap<Int,Group>()
            fun flags(row: Int)=next.lines.getOrNull(row)?.firstOrNull()?.attributes ?: 0
            var start=0
            while(start<next.rows) {
                var end=start
                while(end+1<next.rows && flags(end) and (ROW_METADATA or WRAP)==(ROW_METADATA or WRAP) &&
                    flags(end+1) and (ROW_METADATA or CONTINUATION)==(ROW_METADATA or CONTINUATION)) end++
                val source=next.lines.subList(start,end+1).toList()
                val partialStart=flags(start) and CONTINUATION!=0
                val partialEnd=flags(end) and WRAP!=0 || (flags(end) and ROW_METADATA==0 &&
                    next.lines.getOrNull(end+1)?.firstOrNull {it.width>0 && it.x==0}?.text?.any { !it.isWhitespace() }==true)
                val old=groups[start]
                val group=if(old!=null && old.columns==next.columns && old.source==source && old.partialStart==partialStart && old.partialEnd==partialEnd) old
                    else Group(source,next.columns,partialStart,partialEnd,detect(source,start,next.columns,partialStart,partialEnd))
                for(row in start..end) result[row]=group.links.filter {it.row==row}
                updated[start]=group;start=end+1
            }
            frame=next;groups=updated;rows=result;return rows
        }
    }
}
