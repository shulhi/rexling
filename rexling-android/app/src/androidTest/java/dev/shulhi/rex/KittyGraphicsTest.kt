package dev.shulhi.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KittyGraphicsTest {
    private fun pixelImage(id: Long,g: Long,w: Int,h: Int,data: ByteArray)=decodeTerminalImage(id,g,w,h,java.nio.ByteBuffer.wrap(data))
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String)=instrumentation.context.assets.open("kitty_$name.rxt").use {it.readBytes()}
    @Test fun nativePacketsKeepImagesAcrossDeltasAndPanePreviewsAndRemoveDeletedImages() {
        val updates=TerminalUpdates()
        val first=updates.decode(fixture("full")).screen!!
        assertEquals(1,first.images.size);assertEquals(7L,first.images[0].image.id)
        val next=updates.decode(fixture("delta")).screen!!
        assertSame(first.images[0].image,next.images[0].image)
        val preview=updates.decode(fixture("preview"))
        assertEquals(1,preview.previewFrames.getValue("other").images.size)
        val image=render(first);assertTrue(hasColor(image,Color.RED));assertTrue(hasColor(image,Color.GREEN))
        File(instrumentation.targetContext.cacheDir,"kitty-native-frame.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        assertTrue(updates.decode(fixture("deleted")).screen!!.images.isEmpty())
        image.recycle()
    }
    @Test fun realServerImagesRenderThroughJniAndRestoreAfterReconnection() {
        val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("kitty_server");val session=args.getString("kitty_session");val block=args.getString("kitty_block")
        org.junit.Assume.assumeTrue("Requires an explicitly owned disposable Rex graphics session",endpoint!=null && session!=null && block!=null)
        repeat(2) {
            val handle=NativeBridge.create();val updates=TerminalUpdates()
            fun command(type: String,vararg fields: Pair<String,Any>) {
                val request=org.json.JSONObject().put("type",type);fields.forEach {request.put(it.first,it.second)}
                NativeBridge.command(handle,request.toString())
            }
            fun waitFor(check: (AppState)->Boolean): AppState {
                val deadline=android.os.SystemClock.uptimeMillis()+15000
                while(android.os.SystemClock.uptimeMillis()<deadline) {
                    NativeBridge.awaitUpdate(handle,updates.revision,100)
                    val packet=NativeBridge.readUpdate(handle,updates.revision)
                    if(packet.isNotEmpty()) {val state=updates.decode(packet);if(check(state)) return state}
                }
                error("Timed out waiting for live Kitty graphics")
            }
            try {
                command("connect","server" to endpoint!!);waitFor {it.connected}
                command("session","id" to session!!,"block" to block!!)
                val state=waitFor {it.screen?.images?.any {p->p.image.id==7L}==true}
                val rendered=render(state.screen!!)
                assertTrue(hasColor(rendered,Color.RED));assertTrue(hasColor(rendered,Color.GREEN));rendered.recycle()
                val animation=waitFor {it.screen?.images?.any {p->p.image.id==2L}==true}.screen!!.images.first {p->p.image.id==2L}.image
                val changed=waitFor {it.screen?.images?.any {p->p.image.id==2L && p.image.generation!=animation.generation}==true}
                assertTrue(changed.screen!!.images.any {p->p.image.id==7L})
            } finally {NativeBridge.destroy(handle)}
        }
    }
    private fun render(screen: TerminalScreen): Bitmap {
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val grid=TerminalFonts(instrumentation.targetContext).configure(Paint(),10f)
            val width=(screen.columns*grid.cellWidth).toInt();val height=(screen.rows*grid.cellHeight).toInt()
            val view=TerminalView(instrumentation.targetContext).apply {this.screen=screen}
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
            bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        }
        return bitmap
    }
    private fun hasColor(bitmap: Bitmap,color: Int): Boolean=(0 until bitmap.height).any {y->(0 until bitmap.width).any {x->bitmap.getPixel(x,y)==color}}
    @Test fun imagesRespectBackgroundTextLayersAlphaAndSourceCropping() {
        val green=pixelImage(1,1,1,1,byteArrayOf(0,-1,0,-1))
        val cells=listOf(TerminalCell(0,0,"",Color.WHITE,Color.RED,0,1),TerminalCell(1,0,"█",Color.WHITE,Color.BLACK,0,1))
        fun screen(layer: Int,image: TerminalImage=green,sx: Int=0,sw: Int=1)=TerminalScreen(4,2,null,null,Color.WHITE,Color.BLACK,cells,
            images=listOf(TerminalImagePlacement(image,layer,0,0,0,0,0,32,16,sx,0,sw,1)))
        val below=render(screen(0));assertTrue(hasColor(below,Color.RED));assertTrue(hasColor(below,Color.GREEN));below.recycle()
        val middle=render(screen(1));assertFalse(hasColor(middle,Color.RED));assertTrue(hasColor(middle,Color.WHITE));middle.recycle()
        val above=render(screen(2));assertFalse(hasColor(above,Color.WHITE));assertTrue(hasColor(above,Color.GREEN));above.recycle()
        val pair=pixelImage(2,1,2,1,byteArrayOf(-1,0,0,-1,0,-1,0,-1))
        val cropped=render(screen(2,pair,1,1));assertFalse(hasColor(cropped,Color.RED));assertTrue(hasColor(cropped,Color.GREEN));cropped.recycle()
        val transparent=pixelImage(3,1,1,1,byteArrayOf(0,0,-1,-128))
        val alpha=render(screen(1,transparent));assertTrue((0 until alpha.height).any {y->(0 until alpha.width/4).any {x->
            val color=alpha.getPixel(x,y);Color.red(color) in 120..135 && Color.blue(color) in 120..135
        }});alpha.recycle()
    }
}
