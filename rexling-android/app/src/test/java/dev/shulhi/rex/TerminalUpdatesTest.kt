package dev.shulhi.rex

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TerminalUpdatesTest {
    private fun packet(revision: Long,flag: Int,changed: Map<Int,String>,metadata: String="",version: Int=1,links: Map<Int,List<Triple<Int,Int,String>>> = emptyMap()): ByteArray {
        val out=ByteArrayOutputStream()
        fun number(value: Long,size: Int) {repeat(size){out.write((value ushr (8*it)).toInt() and 255)}}
        fun text(value: String) {val bytes=value.toByteArray();number(bytes.size.toLong(),4);out.write(bytes)}
        out.write("RXT$version".toByteArray());number(revision,8);text(metadata);number(flag.toLong(),1)
        if(flag>=2) {
            number(2,2);number(2,2);number(-1,4);number(-1,4);number(-1,4);number(0xff000000,4)
            repeat(3){number(2,8)};if(version>=2) number(3,1);number(changed.size.toLong(),2)
            changed.forEach {(row,value)->
                number(row.toLong(),2);number(1,2);number(0,2);number(1,1);number(0,4);number(-1,4);number(0xff000000,4);text(value)
                if(version>=3) {val ranges=links[row].orEmpty();number(ranges.size.toLong(),2);for((start,end,url) in ranges) {number(start.toLong(),2);number(end.toLong(),2);text(url)}}
            }
        }
        return out.toByteArray()
    }
    @Test fun deltasReuseUntouchedRowsAndPreserveUnicode() {
        val decoder=TerminalUpdates()
        val first=decoder.decode(packet(1,3,mapOf(0 to "λ",1 to "🦀"),"""{"phase":"connected","selectedBlock":"a"}"""))
        val second=decoder.decode(packet(4,2,mapOf(1 to "\uDB80\uDF1D")))
        assertSame(first.screen!!.lines[0],second.screen!!.lines[0])
        assertEquals("\uDB80\uDF1D",second.screen.lines[1][0].text)
        assertEquals("a",second.selectedBlock);assertEquals(4L,decoder.revision)
        val unchanged=decoder.decode(packet(5,0,emptyMap(),"""{"phase":"paused"}"""))
        assertSame(second.screen,unchanged.screen)
        assertNull(decoder.decode(packet(6,1,emptyMap())).screen)
    }
    @Test fun unknownBaseTruncationAndDuplicateRevisionAreRejectedWithoutAdvancing() {
        val decoder=TerminalUpdates()
        assertTrue(runCatching {decoder.decode(packet(1,2,mapOf(0 to "x")))}.isFailure)
        val full=packet(1,3,mapOf(0 to "x",1 to "y"))
        assertTrue(runCatching {decoder.decode(full.copyOf(full.size-1))}.isFailure)
        assertEquals(0L,decoder.revision)
        decoder.decode(full)
        assertTrue(runCatching {decoder.decode(full)}.isFailure)
        assertEquals(1L,decoder.revision)
    }
    @Test fun versionTwoCarriesApplicationInputModes() {
        val legacy=packet(1,3,mapOf(0 to "x",1 to "y"))
        val at=17+44 // frame flag/header, then input-mode byte before changed-row count
        val current=legacy.copyOfRange(0,at)+byteArrayOf(3)+legacy.copyOfRange(at,legacy.size)
        current[3]='2'.code.toByte()
        val frame=TerminalUpdates().decode(current).screen!!
        assertEquals(3,frame.inputFlags);assertTrue(frame.applicationScroll)
    }

    @Test fun versionThreeKeepsAndReplacesSparseHyperlinksWithoutChangingLabelText() {
        val decoder=TerminalUpdates()
        fun ranges(url: String)=mapOf(0 to listOf(Triple(0,1,url)))
        val first=decoder.decode(packet(1,3,mapOf(0 to "Docs",1 to "x"),version=3,links=ranges("https://example.com/one")))
        val second=decoder.decode(packet(2,2,mapOf(0 to "Docs"),version=3,links=ranges("https://example.com/two")))
        assertEquals("https://example.com/one",first.screen!!.lines[0][0].hyperlink)
        assertEquals("Docs",second.screen!!.lines[0][0].text)
        assertEquals("https://example.com/two",second.screen.lines[0][0].hyperlink)
        assertSame(first.screen.lines[1],second.screen.lines[1])
        assertNull(decoder.decode(packet(3,2,mapOf(0 to "Docs"),version=3)).screen!!.lines[0][0].hyperlink)
    }
    @Test fun invalidHyperlinkRangesDoNotAdvanceTheDecoder() {
        for(ranges in listOf(listOf(Triple(1,1,"https://example.com")),listOf(Triple(0,3,"https://example.com")),
            listOf(Triple(0,1,"https://a.example"),Triple(0,1,"https://b.example")))) {
            val decoder=TerminalUpdates()
            assertTrue(runCatching {decoder.decode(packet(1,3,mapOf(0 to "x",1 to "y"),version=3,links=mapOf(0 to ranges)))}.isFailure)
            assertEquals(0L,decoder.revision)
        }
    }

    @Test fun metadataOnlyTitleUpdatesReuseTheActivityCatalogUntilExplicitReplacement() {
        val decoder=TerminalUpdates()
        val first=decoder.decode(packet(1,1,emptyMap(),"""{"activity":[{"session":"s","block":"b","state":"blocked","title":"Task"}]}"""))
        val next=decoder.decode(packet(2,1,emptyMap(),"""{"titles":{"b":"New title"}}"""))
        assertSame(first.activity,next.activity)
        assertTrue(decoder.decode(packet(3,1,emptyMap(),"""{"activity":null}""")).activity.isEmpty())
    }

    private fun graphicsDecoder()=TerminalUpdates { id,g,w,h,_ -> TerminalImage(id,g,w,h,null) }
    private fun graphicsPacket(revision: Long,flag: Int=3,asset: Boolean=true,placements: Boolean=true,badLength: Boolean=false,badCrop: Boolean=false): ByteArray {
        val base=packet(revision,flag,if(flag==3) mapOf(0 to "",1 to "") else emptyMap(),version=3)
        base[3]='4'.code.toByte()
        val out=ByteArrayOutputStream();out.write(base)
        fun n(value: Long,size: Int) {repeat(size) {out.write((value ushr (8*it)).toInt() and 255)}}
        if(flag>=2) {
            n(8,4);n(16,4);n(if(asset) 1 else 0,2)
            if(asset) {n(7,4);n(1,8);n(2,4);n(1,4);n(if(badLength) 7 else 8,4);out.write(byteArrayOf(-1,0,0,-1,0,-1,0,-1))}
            n(if(placements) 1 else 0,2)
            if(placements) {
                n(7,4);n(1,8);n(1,1);n(-1,4);n(0,4);n(0,4)
                for(v in listOf(0,0,16,16,0,0,if(badCrop) 3 else 2,1)) n(v.toLong(),4)
            }
        }
        n(0,2);return out.toByteArray()
    }
    @Test fun imageDeltasReusePixelsAndMetadataOnlyUpdatesKeepTheImage() {
        val decoder=graphicsDecoder();val first=decoder.decode(graphicsPacket(1)).screen!!
        assertEquals(1,first.images.size)
        assertEquals(8,first.imageCellWidth);assertEquals(16,first.imageCellHeight)
        val next=decoder.decode(graphicsPacket(2,2,asset=false)).screen!!
        assertSame(first.images[0].image,next.images[0].image)
        assertSame(next,decoder.decode(graphicsPacket(3,0)).screen)
        assertTrue(decoder.decode(graphicsPacket(4,2,asset=false,placements=false)).screen!!.images.isEmpty())
    }
    @Test fun malformedImagesCannotCommitADecoderRevisionOrReuseAnUnknownBase() {
        for(bytes in listOf(graphicsPacket(1,badLength=true),graphicsPacket(1,badCrop=true),graphicsPacket(1,asset=false))) {
            val decoder=graphicsDecoder();assertTrue(runCatching {decoder.decode(bytes)}.isFailure);assertEquals(0L,decoder.revision)
            assertEquals(1,decoder.decode(graphicsPacket(1)).screen!!.images.size)
        }
    }

    @Test fun cursorShapeAndBlinkDecodeOnFullAndCursorOnlyDelta() {
        fun packet(revision: Long,flag: Int,flags: Int): ByteArray {
            val old=rxt5(revision,flag,2,0,if(flag==3) mapOf(0 to "a",1 to "b") else emptyMap())
            val at=16+1+4+8+8+24+1
            return (old.take(at)+listOf(flags.toByte())+old.drop(at)).toByteArray().also {it[3]='6'.code.toByte()}
        }
        val decoder=TerminalUpdates()
        val first=decoder.decode(packet(1,3,4)).screen!!
        assertEquals(0,first.cursorStyle);assertTrue(first.cursorBlinking)
        val steady=decoder.decode(packet(2,2,2)).screen!!
        assertEquals(2,steady.cursorStyle);assertFalse(steady.cursorBlinking)
        assertEquals(first.lines,steady.lines)
        assertTrue(runCatching {TerminalUpdates().decode(packet(1,3,8))}.isFailure)
    }
    /** RXT5 packet: [shift], changed rows (each one cell of [text]) and overscan rows. */
    private fun rxt5(revision: Long,flag: Int,rows: Int,shift: Int,changed: Map<Int,String>,above: List<String> = emptyList(),below: List<String> = emptyList(),reuseOverscan: Boolean=false): ByteArray {
        val out=ByteArrayOutputStream()
        fun number(value: Long,size: Int) {repeat(size){out.write((value ushr (8*it)).toInt() and 255)}}
        fun text(value: String) {val bytes=value.toByteArray();number(bytes.size.toLong(),4);out.write(bytes)}
        fun row(value: String) {number(1,2);number(0,2);number(1,1);number(0,4);number(-1,4);number(0xff000000,4);text(value);number(0,2)}
        out.write("RXT5".toByteArray());number(revision,8);text("");number(flag.toLong(),1)
        number(2,2);number(rows.toLong(),2);number(-1,4);number(-1,4);number(-1,4);number(0xff000000,4)
        number(100,8);number(10,8);number(rows.toLong(),8);number(0,1)
        number(shift.toLong(),2);number(changed.size.toLong(),2)
        changed.forEach {(y,value)->number(y.toLong(),2);row(value)}
        number(if(reuseOverscan) 255 else above.size.toLong(),1);if(!reuseOverscan) above.forEach(::row)
        number(if(reuseOverscan) 255 else below.size.toLong(),1);if(!reuseOverscan) below.forEach(::row)
        number(8,4);number(16,4);number(0,2);number(0,2)  // image cell size, no assets, no placements
        number(0,2)  // no previews
        return out.toByteArray()
    }
    @Test fun unchangedOverscanSidesReuseOnlyTheSameDeltaBase() {
        val decoder=TerminalUpdates()
        decoder.decode(rxt5(1,3,2,0,mapOf(0 to "a",1 to "b"),above=listOf("z"),below=listOf("c")))
        val next=decoder.decode(rxt5(2,2,2,0,mapOf(0 to "x"),reuseOverscan=true)).screen!!
        assertEquals("z",next.above.single().single().text);assertEquals("c",next.below.single().single().text)
        assertTrue(runCatching {TerminalUpdates().decode(rxt5(1,3,2,0,mapOf(0 to "a",1 to "b"),reuseOverscan=true))}.isFailure)
    }
    @Test fun shiftedRowsMoveWithTheirContentAndOverscanRowsDecode() {
        val decoder=TerminalUpdates()
        val first=decoder.decode(rxt5(1,3,4,0,mapOf(0 to "a",1 to "b",2 to "c",3 to "d"),above=listOf("z"),below=listOf("e"))).screen!!
        assertEquals("z",first.above.single()[0].text);assertEquals("e",first.below.single()[0].text)
        // Content moved up one row: only the new bottom row is sent.
        val next=decoder.decode(rxt5(2,2,4,1,mapOf(3 to "e"))).screen!!
        assertEquals(listOf("b","c","d","e"),next.lines.map {it.single().text})
        assertEquals(listOf(0,1,2,3),next.lines.map {it.single().y})
        assertEquals("b",next.cells[0].text)
        // Scrolling back down by two needs both uncovered top rows.
        val back=decoder.decode(rxt5(3,2,4,-2,mapOf(0 to "y",1 to "z"))).screen!!
        assertEquals(listOf("y","z","b","c"),back.lines.map {it.single().text})
        assertTrue(runCatching {decoder.decode(rxt5(4,2,4,1,emptyMap()))}.isFailure)
        assertTrue(runCatching {decoder.decode(rxt5(4,3,4,1,mapOf(0 to "a",1 to "b",2 to "c",3 to "d")))}.isFailure)
    }
    @Test fun rxt7KeepsHiddenInputAnchorSeparateFromTheVisibleCursor() {
        fun packet(rev: Long,anchorX: Int,anchorY: Int): ByteArray {
            val out=ByteArrayOutputStream()
            fun n(value:Long,size:Int) {repeat(size) {out.write((value ushr (8*it)).toInt() and 255)}}
            out.write("RXT7".toByteArray());n(rev,8);n(2,4);out.write("{}".toByteArray());n(3,1)
            n(2,2);n(2,2);n(-1,4);n(-1,4);n(-1,4);n(0,4)
            n(2,8);n(0,8);n(2,8);n(7,1);n(1,1);n(0,2);n(2,2)
            repeat(2) {row->n(row.toLong(),2);n(0,2);n(0,2)}
            n(0,1);n(0,1);n(8,4);n(16,4);n(0,2);n(0,2)
            n(anchorX.toLong(),4);n(anchorY.toLong(),4);n(0,2)
            return out.toByteArray()
        }
        val decoder=TerminalUpdates();val frame=decoder.decode(packet(1,1,1)).screen!!
        assertNull(frame.cursorX);assertNull(frame.cursorY);assertEquals(1,frame.cursorAnchorX);assertEquals(1,frame.cursorAnchorY)
        val absent=decoder.decode(packet(2,-1,-1)).screen!!;assertNull(absent.cursorAnchorX);assertNull(absent.cursorAnchorY)
        assertTrue(runCatching {decoder.decode(packet(3,2,1))}.isFailure)
        assertEquals(2L,decoder.revision)
    }

}
