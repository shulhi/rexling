package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class TerminalLinksTest {
    private fun cells(text: String)=text.mapIndexed {i,c->TerminalCell(i,0,c.toString(),-1,0,0,1)}
    private fun links(text: String)=TerminalLinks.row(cells(text),0,200)
    @Test fun webLinksKeepPathsQueriesFragmentsAndLocalAddresses() {
        val urls=listOf("https://github.com/a/b?q=x&y=2#readme","http://localhost:8080/path","http://100.64.0.1:3000/","http://[::1]:8080/","https://example.com/λ")
        assertEquals(urls,links(urls.joinToString(" ")).map {it.url})
    }
    @Test fun punctuationAndMarkdownDoNotBecomePartOfTheUrl() {
        assertEquals(listOf("https://example.com/a_(b)","https://example.com/x","https://www.example.com"),
            links("(https://example.com/a_(b)). [docs](https://example.com/x), 'www.example.com'").map {it.url})
    }
    @Test fun onlyWebSchemesWithHostsAreOpened() {
        for(value in listOf("javascript:alert(1)","file:///etc/passwd","intent://example.com","https://","https://example.com/%zz"))
            assertNull(value,TerminalLinks.webUrl(value))
        assertTrue(links("nothttps://example.com ftp://www.example.com user@www.example.com").isEmpty())
    }
    @Test fun terminalFileLinksKeepTheirRemotePathAcrossOsc8AndSoftWraps() {
        val path="/Users/alex/project/dist/Rexling-arm64-release.apk"
        val file="file://mac/Users/alex/Build%20Artifacts/Rexling.apk#L1"
        val osc=cells("Signed APK").map {it.copy(hyperlink=file)}
        assertEquals(file,TerminalLinks.row(osc,0,200).single().url)
        assertEquals("/Users/alex/Build Artifacts/Rexling.apk",TerminalLinks.filePath(file))
        assertEquals(path,TerminalLinks.filePath(links("APK ($path).").single().url))
        val wrapped=TerminalLinks.Index().update(wrapped(path,18)).flatten()
        assertEquals(listOf(path),wrapped.mapNotNull {TerminalLinks.filePath(it.url)}.distinct())
        assertNull(TerminalLinks.filePath("file:///tmp/a%00.apk"))
        assertNull(TerminalLinks.filePath("file://user@other/tmp/file.apk"))
        assertNull(TerminalLinks.filePath("https://example.com/download.apk"))
        assertTrue(links("ftp://example.com/report.pdf").isEmpty())
    }
    @Test fun sparseAndWideCellsKeepAccurateTapColumns() {
        val prefix=listOf(TerminalCell(0,0,"🦖",-1,0,0,2),TerminalCell(1,0,"",-1,0,0,0))
        val url="https://example.com"
        val link=TerminalLinks.row(prefix+cells(url).map {it.copy(x=it.x+4)},0,100).single()
        assertEquals(4,link.start);assertEquals(4+url.length,link.end)
        assertFalse(link.contains(3));assertTrue(link.contains(4));assertFalse(link.contains(link.end))
    }
    @Test fun hiddenTextAndGapsAreNotClickable() {
        assertTrue(TerminalLinks.row(cells("https://example.com").map {it.copy(attributes=16)},0,100).isEmpty())
        val row=cells("https://example.com")
        val link=TerminalLinks.row(row,0,100).single()
        assertEquals("https://example.com",link.url)
        assertFalse(link.contains(90))
    }
    @Test fun osc8LabelsUseStoredDestinationAndOverrideVisibleUrlText() {
        val row=cells("https://shown.example").map {it.copy(hyperlink="https://actual.example/docs")}
        val link=TerminalLinks.row(row,0,100).single()
        assertEquals("https://actual.example/docs",link.url);assertTrue(link.explicit)
        assertEquals(0,link.start);assertEquals(row.size,link.end)
        assertTrue(TerminalLinks.row(row.map {it.copy(hyperlink="javascript:alert(1)")},0,100).isEmpty())
        assertTrue(TerminalLinks.row(row.map {it.copy(attributes=16)},0,100).isEmpty())
    }

    private fun wrapped(text: String,columns: Int): TerminalScreen {
        val count=(text.length+columns-1)/columns
        val cells=text.mapIndexed {i,c->
            val row=i/columns
            TerminalCell(i%columns,row,c.toString(),-1,0,TerminalLinks.ROW_METADATA or
                (if(row<count-1) TerminalLinks.WRAP else 0) or (if(row>0) TerminalLinks.CONTINUATION else 0),1)
        }
        return TerminalScreen(columns,count,null,null,-1,0,cells)
    }
    @Test fun trueSoftWrappedUrlsHaveOneDestinationAndPhysicalHitRanges() {
        val url="https://example.com/path/to/docs?q=one&next=two#here"
        val frame=wrapped("see: $url",12)
        val rows=TerminalLinks.Index().update(frame)
        assertEquals(listOf(url),rows.flatten().map {it.url}.distinct())
        assertTrue(rows[0].single().start==5);assertEquals(0,rows[1].single().start)
        assertTrue(rows.last().single().contains((5+url.length-1)%12))
    }
    @Test fun hardNewlinesAndIncompleteViewportFragmentsAreNotJoined() {
        val complete=wrapped("https://example.com/abc",12)
        val clipped=complete.copy(rows=1,cells=complete.lines[0],lines=listOf(complete.lines[0]))
        assertTrue(TerminalLinks.Index().update(clipped).flatten().isEmpty())
        val first="https://example.com/a";val second="different"
        val cells=(first.mapIndexed {i,c->TerminalCell(i,0,c.toString(),-1,0,TerminalLinks.ROW_METADATA,1)}+
            second.mapIndexed {i,c->TerminalCell(i,1,c.toString(),-1,0,TerminalLinks.ROW_METADATA,1)})
        val rows=TerminalLinks.Index().update(TerminalScreen(first.length,2,null,null,-1,0,cells))
        assertEquals(first,rows[0].single().url);assertTrue(rows[1].isEmpty())
    }
    @Test fun prefixPrecheckKeepsUppercaseAndPrefixesSplitAcrossWrappedRows() {
        for(url in listOf("HTTP://EXAMPLE.COM/a","HTTPS://example.com/path","www.example.com/docs")) {
            for(columns in 1..9) {
                val found=TerminalLinks.Index().update(wrapped(url,columns)).flatten().map {it.url}.distinct()
                assertEquals("$url wrapped at $columns",listOf(if(url.startsWith("www.")) "https://$url" else url),found)
            }
        }
    }
    @Test fun prefixPrecheckDoesNotBridgeHiddenCellsOrSparseGaps() {
        val row=cells("https://example.com")
        assertTrue(TerminalLinks.row(row.mapIndexed {i,c->if(i==2)c.copy(attributes=16)else c},0,100).isEmpty())
        assertTrue(TerminalLinks.row(row.mapIndexed {i,c->if(i>=2)c.copy(x=c.x+1)else c},0,100).isEmpty())
        val plain=cells("compiler ready www elsewhere http status ok")
        assertTrue(TerminalLinks.row(plain,0,100).isEmpty())
    }
    @Test fun plainOsc8LabelsStayClickableWithoutAnyVisibleWebPrefix() {
        val row=cells("Docs").map {it.copy(hyperlink="https://example.com/docs")}
        val result=TerminalLinks.row(row,0,100).single()
        assertEquals("https://example.com/docs",result.url);assertTrue(result.explicit)
    }

    @Test fun changingWrappedTailUpdatesUnchangedPrefixLinks() {
        val first=wrapped("https://example.com/aaaa",12);val next=wrapped("https://example.com/bbbb",12)
        val lines=next.lines.toMutableList();lines[0]=first.lines[0]
        val second=next.copy(lines=lines,cells=lines.flatten());val index=TerminalLinks.Index()
        assertEquals("https://example.com/aaaa",index.update(first)[0].single().url)
        assertSame(first.lines[0],second.lines[0])
        assertEquals("https://example.com/bbbb",index.update(second)[0].single().url)
    }

}
