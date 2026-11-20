package dev.shulhi.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** Fresh 720p image generations, not repeated draws of one cached still. */
class KittyVideoPerfTest {
    /** Optional: run only against an explicitly selected disposable server, with mpv still playing. */
    @Test fun switchAwayDuringLiveVideo() {
        val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("video_server")
        org.junit.Assume.assumeTrue(endpoint!=null)
        val session=requireNotNull(args.getString("video_session"));val video=requireNotNull(args.getString("video_block"))
        val other=requireNotNull(args.getString("text_block"));val harness=InstrumentationRegistry.getInstrumentation()
        val handle=NativeBridge.create();val updates=TerminalUpdates();val canvas=Canvas(Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888));val renderer=TerminalImages()
        fun command(type:String,vararg values:Pair<String,Any>) {
            val j=org.json.JSONObject().put("type",type);values.forEach {j.put(it.first,it.second)};NativeBridge.command(handle,j.toString())
        }
        fun until(stage:String,predicate:(AppState)->Boolean) {
            val end=android.os.SystemClock.elapsedRealtime()+10000
            while(android.os.SystemClock.elapsedRealtime()<end) {
                if(NativeBridge.awaitUpdate(handle,updates.revision,100)<=updates.revision) continue
                val state=updates.decode(NativeBridge.readUpdate(handle,updates.revision))
                if(predicate(state)) return
            };error("Timed out waiting for $stage")
        }
        try {
            command("connect","server" to endpoint!!);until("server connection") {it.connected}
            command("session","id" to session,"block" to video,"navigationId" to 1)
            var seen:TerminalImage?=null;var distinct=0
            until("eight live image generations") { state ->
                val screen=state.screen
                val image=screen?.images?.lastOrNull()?.image
                if(state.selectedBlock==video && screen!=null && image!=null && image!==seen) {
                    harness.runOnMainSync {for(layer in 0..2) renderer.draw(canvas,screen,layer,0f,0f,640f,360f,8f,16f)}
                    seen=image;distinct++
                };distinct>=8
            }
            val start=System.nanoTime()
            harness.runOnMainSync {command("block","id" to other,"navigationId" to 2)}
            until("text tab after navigation") {it.selectedBlock==other && it.screen?.plainText()?.contains("VIDEO_SWITCH_OK")==true}
            val ms=(System.nanoTime()-start)/1e6
            harness.sendStatus(0,Bundle().apply {putString("kitty_switch_ms",ms.toString())})
            org.junit.Assert.assertTrue("Video blocked navigation for $ms ms",ms<2000)
        } finally {NativeBridge.destroy(handle)}
    }
    @Test fun changing720p() {
        val harness=InstrumentationRegistry.getInstrumentation()
        val w=1280; val h=720
        val rgba=ByteArray(w*h*4) { if(it%4==3) (-1).toByte() else it.toByte() }
        val renderer=TerminalImages(); val target=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(target); val draws=mutableListOf<Long>(); val prep=mutableListOf<Long>()
        repeat(72) { i ->
            rgba[0]=i.toByte()
            val start=System.nanoTime()
            val image=decodeTerminalImage(7,i.toLong(),w,h,java.nio.ByteBuffer.wrap(rgba))
            val screen=TerminalScreen(1,1,null,null,-1,0,emptyList(),images=listOf(
                TerminalImagePlacement(image,2,0,0,0,0,0,w,h,0,0,w,h)))
            if(i>=12) prep+=System.nanoTime()-start
            harness.runOnMainSync {
                val t=System.nanoTime(); renderer.draw(canvas,screen,2,0f,0f,w.toFloat(),h.toFloat(),8f,16f)
                if(i>=12) draws+=System.nanoTime()-t
                org.junit.Assert.assertEquals(i,android.graphics.Color.red(target.getPixel(0,0)))
            }
        }
        fun stats(ns:List<Long>)=ns.sorted().let { "p50=${it[it.size/2]/1e6} p95=${it[it.size*95/100]/1e6}" }
        harness.sendStatus(0,Bundle().apply {putString("kitty_video_ms","prepare ${stats(prep)} draw ${stats(draws)}")})
    }
}
