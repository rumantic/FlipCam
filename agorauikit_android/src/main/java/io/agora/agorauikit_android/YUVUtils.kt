package io.agora.agorauikit_android

import android.content.Context
import android.graphics.*
import android.renderscript.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer


object YUVUtils {
    fun encodeI420(i420: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0 // Y start index
        var uIndex = frameSize // U statt index
        var vIndex = frameSize * 5 / 4 // V start index: w*h*5/4
        var a: Int
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                a = argb[index] and -0x1000000 shr 24 //  is not used obviously
                R = argb[index] and 0xff0000 shr 16
                G = argb[index] and 0xff00 shr 8
                B = argb[index] and 0xff shr 0

                // well known RGB to YUV algorithm
                Y = (66 * R + 129 * G + 25 * B + 128 shr 8) + 16
                U = (-38 * R - 74 * G + 112 * B + 128 shr 8) + 128
                V = (112 * R - 94 * G - 18 * B + 128 shr 8) + 128

                // I420(YUV420p) -> YYYYYYYY UU VV
                i420[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    i420[uIndex++] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
                    i420[vIndex++] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                }
                index++
            }
        }
    }

    fun encodeNV21(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var a: Int
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                a = argb[index] and -0x1000000 shr 24 // a is not used obviously
                R = argb[index] and 0xff0000 shr 16
                G = argb[index] and 0xff00 shr 8
                B = argb[index] and 0xff shr 0

                // well known RGB to YUV algorithm
                Y = (66 * R + 129 * G + 25 * B + 128 shr 8) + 16
                U = (-38 * R - 74 * G + 112 * B + 128 shr 8) + 128
                V = (112 * R - 94 * G - 18 * B + 128 shr 8) + 128

                // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                //    pixel AND every other scanline.
                yuv420sp[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                    yuv420sp[uvIndex++] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
                }
                index++
            }
        }
    }

    fun swapYU12toYUV420SP(
        yu12bytes: ByteArray,
        i420bytes: ByteArray,
        width: Int,
        height: Int,
        yStride: Int,
        uStride: Int,
        vStride: Int
    ) {
        System.arraycopy(yu12bytes, 0, i420bytes, 0, yStride * height)
        val startPos = yStride * height
        val yv_start_pos_v = startPos + startPos / 4
        for (i in 0 until startPos / 4) {
            i420bytes[startPos + 2 * i + 0] = yu12bytes[yv_start_pos_v + i]
            i420bytes[startPos + 2 * i + 1] = yu12bytes[startPos + i]
        }
    }

    fun i420ToBitmap(
        width: Int,
        height: Int,
        rotation: Int,
        bufferLength: Int,
        buffer: ByteArray,
        yStride: Int,
        uStride: Int,
        vStride: Int
    ): Bitmap {
        val NV21 = ByteArray(bufferLength)
        swapYU12toYUV420SP(buffer, NV21, width, height, yStride, uStride, vStride)
        val baos = ByteArrayOutputStream()
        val strides = intArrayOf(yStride, yStride)
        val image = YuvImage(NV21, ImageFormat.NV21, width, height, strides)
        image.compressToJpeg(
            Rect(0, 0, image.width, image.height),
            100, baos
        )

        // rotate picture when saving to file
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        val bytes = baos.toByteArray()
        try {
            baos.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun blur(context: Context?, image: Bitmap, radius: Float): Bitmap {
        val rs = RenderScript.create(context)
        val outputBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val `in` = Allocation.createFromBitmap(rs, image)
        val out = Allocation.createFromBitmap(rs, outputBitmap)
        val intrinsicBlur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        intrinsicBlur.setRadius(radius)
        intrinsicBlur.setInput(`in`)
        intrinsicBlur.forEach(out)
        out.copyTo(outputBitmap)
        image.recycle()
        rs.destroy()
        return outputBitmap
    }

    fun bitmapToI420(inputWidth: Int, inputHeight: Int, scaled: Bitmap): ByteArray {
        val argb = IntArray(inputWidth * inputHeight)
        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val yuv = ByteArray(inputWidth * inputHeight * 3 / 2)
        encodeI420(yuv, argb, inputWidth, inputHeight)
        scaled.recycle()
        return yuv
    }

    fun toWrappedI420(
        bufferY: ByteBuffer,
        bufferU: ByteBuffer,
        bufferV: ByteBuffer,
        width: Int,
        height: Int
    ): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lengthY = width * height
        val lengthU = chromaWidth * chromaHeight
        val size = lengthY + lengthU + lengthU
        val out = ByteArray(size)
        for (i in 0 until size) {
            if (i < lengthY) {
                out[i] = bufferY[i]
            } else if (i < lengthY + lengthU) {
                val j = (i - lengthY) / chromaWidth
                val k = (i - lengthY) % chromaWidth
                out[i] = bufferU[j * width + k]
            } else {
                val j = (i - lengthY - lengthU) / chromaWidth
                val k = (i - lengthY - lengthU) % chromaWidth
                out[i] = bufferV[j * width + k]
            }
        }
        return out
    }

    /**
     * I420转nv21
     */
    fun I420ToNV21(data: ByteArray, width: Int, height: Int): ByteArray {
        val ret = ByteArray(data.size)
        val total = width * height
        val bufferY = ByteBuffer.wrap(ret, 0, total)
        val bufferVU = ByteBuffer.wrap(ret, total, total / 2)
        bufferY.put(data, 0, total)
        var i = 0
        while (i < total / 4) {
            bufferVU.put(data[i + total + total / 4])
            bufferVU.put(data[total + i])
            i += 1
        }
        return ret
    }

    fun NV21ToBitmap(context: Context?, nv21: ByteArray, width: Int, height: Int): Bitmap {
        val rs = RenderScript.create(context)
        val yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
        var yuvType: Type.Builder? = null
        yuvType = Type.Builder(rs, Element.U8(rs)).setX(nv21.size)
        val `in` = Allocation.createTyped(rs, yuvType.create(), 1)
        val rgbaType = Type.Builder(rs, Element.RGBA_8888(rs))
            .setX(width).setY(height)
        val out = Allocation.createTyped(rs, rgbaType.create(), 1)
        `in`.copyFrom(nv21)
        yuvToRgbIntrinsic.setInput(`in`)
        yuvToRgbIntrinsic.forEach(out)
        val bmpout = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.copyTo(bmpout)
        return bmpout
    }
}