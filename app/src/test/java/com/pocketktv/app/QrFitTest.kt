package com.pocketktv.app
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
class QrFitTest {
 @Test fun qrFitsAndDecodesAtRepresentativeWindowSizes(){
  val content="http://192.168.0.1:42705/?token=0123456789abcdef0123456789abcdef"
  val original=QRCodeWriter().encode(content,BarcodeFormat.QR_CODE,600,600,mapOf(EncodeHintType.MARGIN to 4))
  for((w,h,c,d) in listOf(listOf(960,540,700,1),listOf(1920,1080,1120,2),listOf(800,360,600,1))){
   val size=QrFit.size(w,h,c,d.toFloat());assertTrue(size<=c&&size<=h*.52)
   val pixels=IntArray(size*size){i->val x=(i%size)*600/size;val y=(i/size)*600/size;if(original[x,y])0xff000000.toInt() else 0xffffffff.toInt()}
   val qr=BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size,size,pixels)))
   assertEquals(content,QRCodeReader().decode(qr,mapOf(DecodeHintType.TRY_HARDER to true)).text)
  }
 }
}
