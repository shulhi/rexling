package dev.shulhi.rex

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** State is decoder-local: row deltas never cross a connection or an unknown base. */
internal class TerminalUpdates(private val imageDecoder: (Long,Long,Int,Int,ByteBuffer)->TerminalImage = ::decodeTerminalImage) {
    var revision: Long=0;private set
    private var state=AppState()
    fun decode(bytes: ByteArray): AppState {
        require(bytes.size in 17..134217728) {"Invalid terminal packet size"}
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version=data.int
        require(version==0x31545852 || version==0x32545852 || version==0x33545852 || version==0x34545852 || version==0x35545852 || version==0x36545852 || version==0x37545852) {"Unsupported terminal packet"}
        val nextRevision=data.long;require(nextRevision>revision)
        fun text(): String {val n=data.int;require(n>=0 && n<=data.remaining());val raw=ByteArray(n);data.get(raw);return raw.toString(Charsets.UTF_8)}
        val metadata=text()
        var next=if(metadata.isNotEmpty()) AppState.parse(metadata,state.activity) else state.copy(previewFrames=emptyMap())
        fun readFrame(previous: TerminalScreen?): TerminalScreen? = when(val flag=data.get().toInt()) {
            0->previous
            1->null
            2,3->{
                val columns=data.short.toInt() and 0xffff;val rows=data.short.toInt() and 0xffff
                require(columns>0 && rows>0 && columns.toLong()*rows<=2_000_000)
                val x=data.int;val y=data.int;val fg=data.int;val bg=data.int
                val scroll=TerminalScroll(data.long,data.long,data.long)
                val inputFlags=if(version>=0x32545852) data.get().toInt() and 0xff else 0
                val cursorFlags=if(version>=0x36545852) data.get().toInt() and 0xff else 1
                require(cursorFlags in 0..7) {"Invalid cursor flags"}
                if(flag==2) require(previous!=null && previous.columns==columns && previous.rows==rows) {"Missing terminal delta base"}
                // RXT5: rows moved by `shift` (positive: up) before changed rows apply.
                val shift=if(version>=0x35545852) data.short.toInt() else 0
                require(shift==0 || (flag==2 && kotlin.math.abs(shift)<rows)) {"Invalid terminal row shift"}
                val lines=if(flag==2) shifted(previous!!.lines,shift) else MutableList<List<TerminalCell>>(rows){emptyList()}
                val changed=data.short.toInt() and 0xffff;require(changed<=rows)
                val seen=HashSet<Int>()
                fun readRow(row: Int): List<TerminalCell> {
                    val count=data.short.toInt() and 0xffff;require(count<=columns)
                    val cells=List(count) {
                        val col=data.short.toInt() and 0xffff;val width=data.get().toInt() and 0xff
                        val attrs=data.int;val foreground=data.int;val background=data.int
                        require(col<columns && width<=2)
                        TerminalCell(col,row,text(),foreground,background,attrs,width)
                    }
                    return if(version>=0x33545852) {
                        val n=data.short.toInt() and 0xffff;require(n<=columns)
                        var end=0
                        val links=List(n) {
                            val start=data.short.toInt() and 0xffff;val stop=data.short.toInt() and 0xffff
                            require(start>=end && stop>start && stop<=columns);end=stop
                            val uri=text();require(uri.isNotEmpty() && uri.toByteArray(Charsets.UTF_8).size<=65536)
                            Triple(start,stop,uri)
                        }
                        var index=0
                        cells.map {cell->
                            while(index<links.size && links[index].second<=cell.x) index++
                            val link=links.getOrNull(index)
                            if(link!=null && cell.x>=link.first && cell.x<link.second) cell.copy(hyperlink=link.third) else cell
                        }
                    } else cells
                }
                repeat(changed) {
                    val row=data.short.toInt() and 0xffff;require(row<rows && seen.add(row))
                    lines[row]=readRow(row)
                }
                if(flag==2 && shift!=0) {
                    // Rows uncovered by the shift must be among the changed rows.
                    val uncovered=if(shift>0) (rows-shift until rows) else (0 until -shift)
                    require(uncovered.all {it in seen}) {"Missing rows after terminal shift"}
                }
                if(flag==3) require(changed==rows)
                var above=emptyList<List<TerminalCell>>();var below=emptyList<List<TerminalCell>>()
                if(version>=0x35545852) {
                    fun overscan(old: List<List<TerminalCell>>?,row: Int): List<List<TerminalCell>> {
                        val count=data.get().toInt() and 0xff
                        if(count==255) {require(flag==2 && old!=null) {"Missing overscan delta base"};return old}
                        require(count<=16)
                        return List(count) {readRow(row)}
                    }
                    above=overscan(previous?.above,-1);below=overscan(previous?.below,rows)
                }
                var cw=8;var ch=16;var placements=emptyList<TerminalImagePlacement>()
                if(version>=0x34545852) {
                    cw=data.int;ch=data.int;require(cw>0 && ch>0)
                    val images=if(flag==2) previous!!.images.associate {Pair(it.image.id,it.image.generation) to it.image}.toMutableMap() else mutableMapOf()
                    val assets=data.short.toInt() and 0xffff;require(assets<=4096)
                    val newKeys=HashSet<Pair<Long,Long>>()
                    var total=0L
                    repeat(assets) {
                        val id=data.int.toLong() and 0xffffffffL;val generation=data.long
                        val w=data.int;val h=data.int;val n=data.int
                        require(w in 1..8192 && h in 1..8192 && n.toLong()==w.toLong()*h*4 && n<=data.remaining())
                        total+=n;require(total<=64*1024*1024)
                        val key=Pair(id,generation);require(newKeys.add(key))
                        images[key]=imageDecoder(id,generation,w,h,data)
                        data.position(data.position()+n)
                    }
                    val count=data.short.toInt() and 0xffff;require(count<=4096)
                    placements=List(count) {
                        val id=data.int.toLong() and 0xffffffffL;val generation=data.long
                        val image=images[Pair(id,generation)] ?: error("Missing terminal image pixels")
                        val layer=data.get().toInt() and 0xff;val z=data.int;val col=data.int;val row=data.int
                        val ox=data.int;val oy=data.int;val w=data.int;val h=data.int
                        val sx=data.int;val sy=data.int;val sw=data.int;val sh=data.int
                        require(layer in 0..2 && ox>=0 && oy>=0 && w>0 && h>0 && sx>=0 && sy>=0 && sw>0 && sh>0)
                        require(sx.toLong()+sw<=image.width && sy.toLong()+sh<=image.height)
                        TerminalImagePlacement(image,layer,z,col,row,ox,oy,w,h,sx,sy,sw,sh)
                    }.sortedWith(compareBy({it.layer},{it.z}))
                }
                var anchorX: Int?=null;var anchorY: Int?=null
                if(version>=0x37545852) {
                    val ax=data.int;val ay=data.int
                    require((ax==-1 && ay==-1) || (ax in 0 until columns && ay in 0 until rows)) {"Invalid cursor anchor"}
                    anchorX=ax.takeIf {it>=0};anchorY=ay.takeIf {it>=0}
                }
                TerminalScreen(columns,rows,x.takeIf {it>=0},y.takeIf {it>=0},fg,bg,lines.flatten(),scroll,lines,inputFlags,placements,cw,ch,above,below,cursorFlags and 3,cursorFlags and 4!=0,anchorX,anchorY)
            }
            else->error("Invalid terminal frame flag $flag")
        }
        val frame=readFrame(state.screen)
        if(version>=0x34545852) {
            val count=data.short.toInt() and 0xffff;require(count<=32)
            val previews=mutableMapOf<String,TerminalScreen>()
            repeat(count) {val id=text();require(id.isNotEmpty() && !previews.containsKey(id));previews[id]=readFrame(null) ?: error("Missing preview frame")}
            next=next.copy(previewFrames=previews)
        }
        require(!data.hasRemaining()) {"Trailing terminal packet data"}
        next=next.copy(revision=nextRevision,screen=frame)
        state=next;revision=nextRevision
        return next
    }
    /** Move rows by [shift] (positive: content moved up), re-tagging each cell's row. */
    private fun shifted(lines: List<List<TerminalCell>>,shift: Int): MutableList<List<TerminalCell>> {
        if(shift==0) return lines.toMutableList()
        return MutableList(lines.size) {y->
            val from=y+shift
            if(from in lines.indices) lines[from].let {row->if(row.isEmpty() || row[0].y==y) row else row.map {it.copy(y=y)}} else emptyList()
        }
    }
}
