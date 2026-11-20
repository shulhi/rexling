package dev.shulhi.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer

/** Called by the IO packet decoder; never convert pixels or allocate bitmaps in View.onDraw. */
internal fun decodeTerminalImage(id: Long,generation: Long,w: Int,h: Int,data: ByteBuffer): TerminalImage {
    val rgba=data.array(); val start=data.arrayOffset()+data.position()
    // Native ARGB_8888 memory is RGBA on Android. Opaque pixels are already
    // premultiplied, so video frames need no swizzle or temporary IntArray.
    val end=start+w*h*4
    var alpha=start+3
    while(alpha<end && rgba[alpha]==(-1).toByte()) alpha+=4
    if(alpha>=end) {
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        if(bitmap.rowBytes==w*4) {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba,start,end-start))
            bitmap.setHasAlpha(false)
            return TerminalImage(id,generation,w,h,bitmap)
        }
    }
    val pixels=IntArray(w*h)
    for(i in pixels.indices) {
        val p=start+i*4
        pixels[i]=((rgba[p+3].toInt() and 255) shl 24) or ((rgba[p].toInt() and 255) shl 16) or
            ((rgba[p+1].toInt() and 255) shl 8) or (rgba[p+2].toInt() and 255)
    }
    return TerminalImage(id,generation,w,h,Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888))
}

internal class TerminalImages {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun draw(canvas: Canvas,screen: TerminalScreen,layer: Int,left: Float,top: Float,right: Float,bottom: Float,cw: Float,ch: Float) {
        val sx=cw/screen.imageCellWidth.coerceAtLeast(1);val sy=ch/screen.imageCellHeight.coerceAtLeast(1)
        val save=canvas.save();canvas.clipRect(left,top,right,bottom)
        for(p in screen.images) {
            if(p.layer!=layer) continue
            val x=left+p.col*cw+p.offsetX*sx;val y=top+p.row*ch+p.offsetY*sy
            val target=RectF(x,y,x+p.width*sx,y+p.height*sy)
            if(!RectF.intersects(target,RectF(left,top,right,bottom))) continue
            canvas.drawBitmap(checkNotNull(p.image.bitmap),Rect(p.sourceX,p.sourceY,p.sourceX+p.sourceWidth,p.sourceY+p.sourceHeight),target,paint)
        }
        canvas.restoreToCount(save)
    }
}
