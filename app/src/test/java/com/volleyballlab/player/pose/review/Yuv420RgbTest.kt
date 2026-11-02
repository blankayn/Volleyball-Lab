package com.volleyballlab.player.pose.review

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Yuv420RgbTest {
    private fun plane(values: List<Int>, stride: Int, pixel: Int = 1) =
        Yuv420Rgb.Plane(ByteBuffer.wrap(values.map { it.toByte() }.toByteArray()), stride, pixel)
    private fun gray(y: Yuv420Rgb.Plane, width: Int, height: Int, rotation: Int = 0, left: Int = 0, top: Int = 0,
                     maxWidth: Int = 8, maxHeight: Int = 8) = Yuv420Rgb.convert(y,
        plane(List(32) {128}, 4), plane(List(32) {128}, 4), left, top, width, height,
        rotation, maxWidth, maxHeight, bt709 = false, fullRange = true)

    @Test fun `rotation uses the display orientation without cropping or stretching`() {
        val y = plane(listOf(10,20,30,40,50,60),2)
        val output = gray(y,2,3,90)
        assertEquals(3,output.width); assertEquals(2,output.height)
        assertEquals(listOf(50,30,10,60,40,20),output.argb.map { it and 255 })
        assertEquals(listOf(20,40,60,10,30,50),gray(y,2,3,270).argb.map { it and 255 })
    }

    @Test fun `row padding and crop coordinates do not become image pixels`() {
        val y = plane(listOf(0,10,20,30,255,255,40,50,60,70,255,255,80,90,100,110,255,255,120,130,140,150,255,255),6)
        assertEquals(listOf(100,90,60,50),gray(y,2,2,180,left=1,top=1).argb.map { it and 255 })
        val scaled=gray(y,4,4,maxWidth=2,maxHeight=2)
        assertEquals(listOf(50,70,130,150),scaled.argb.map { it and 255 })
    }

    @Test fun `interleaved chroma strides retain red and blue channel order`() {
        val y=plane(listOf(81,81,81,81),2)
        val u=plane(listOf(90,0),2,2); val v=plane(listOf(240,0),2,2)
        val result=Yuv420Rgb.convert(y,u,v,0,0,2,2,0,2,2,false,false).argb[0]
        assertTrue((result shr 16 and 255)>245)
        assertTrue((result and 255)<10)
        assertTrue((result shr 8 and 255)<10)
    }

    @Test fun `limited range black and white do not look washed out`() {
        val y=plane(listOf(16,235,16,235),2)
        val neutral=plane(listOf(128),1)
        val rgb=Yuv420Rgb.convert(y,neutral,neutral,0,0,2,2,0,2,2,true,false).argb
        assertEquals(0,rgb[0] and 255); assertEquals(255,rgb[1] and 255)
    }

    @Test fun `extended SDR matrix metadata is supported while HDR and vendor values are rejected`() {
        assertEquals(false,SdrColorStandard.bt709(85))
        assertEquals(true,SdrColorStandard.bt709(72))
        assertNull(SdrColorStandard.bt709(6))
        assertNull(SdrColorStandard.bt709(0))
        assertNull(SdrColorStandard.bt709(90))
        assertNull(SdrColorStandard.bt709(0x10001))
    }

    @Test fun `encoded footroom is clamped after color conversion instead of tinting black`() {
        val y=plane(listOf(0,0,0,0),2)
        val u=plane(listOf(128),1); val v=plane(listOf(140),1)
        val rgb=Yuv420Rgb.convert(y,u,v,0,0,2,2,0,2,2,false,false).argb[0]
        assertEquals(1,rgb shr 16 and 255)
        assertEquals(0,rgb and 65535)
    }
}
