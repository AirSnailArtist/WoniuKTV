package com.pocketktv.app

import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal object QrFit {
    fun size(windowWidth:Int, windowHeight:Int, contentWidth:Int, density:Float):Int {
        val wide=windowWidth>windowHeight*1.2
        val maxWidth=(contentWidth*(if(wide).43 else .88)).toInt()
        val maxHeight=(windowHeight*(if(wide).52 else .42)).toInt()
        return minOf(maxWidth,maxHeight,(300*density).toInt()).coerceAtLeast(1)
    }
}

/** Fits QR codes inside the current visible window, including car split-screen. */
internal class AdaptiveQrContent(private val activity:Activity, val image:ImageView, text:TextView, extra:android.view.View?=null) {
    val view=LinearLayout(activity)
    private val details=LinearLayout(activity)
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density).toInt()
    init {
        view.gravity=Gravity.CENTER
        view.setPadding(dp(10),dp(4),dp(10),dp(4))
        image.adjustViewBounds=true;image.scaleType=ImageView.ScaleType.FIT_CENTER
        image.setBackgroundColor(android.graphics.Color.WHITE)
        details.orientation=LinearLayout.VERTICAL;details.gravity=Gravity.CENTER
        details.addView(text,LinearLayout.LayoutParams(-1,-2))
        extra?.let{details.addView(it)}
        view.addView(image,LinearLayout.LayoutParams(dp(180),dp(180)))
        view.addView(details,LinearLayout.LayoutParams(-1,-2))
        view.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->adjust() }
    }
    private fun adjust(){
        val rect=Rect();activity.window.decorView.getWindowVisibleDisplayFrame(rect)
        val width=rect.width().takeIf{it>0}?:activity.resources.displayMetrics.widthPixels
        val height=rect.height().takeIf{it>0}?:activity.resources.displayMetrics.heightPixels
        val wide=width>height*1.2
        val orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val contentWidth=(view.width-view.paddingLeft-view.paddingRight).coerceAtLeast(1)
        val size=QrFit.size(width,height,contentWidth,activity.resources.displayMetrics.density)
        if(view.orientation!=orientation)view.orientation=orientation
        val p=image.layoutParams as LinearLayout.LayoutParams
        if(p.width!=size||p.height!=size){p.width=size;p.height=size;image.layoutParams=p}
        val d=details.layoutParams as LinearLayout.LayoutParams
        val desiredWidth=if(wide)0 else -1;val weight=if(wide)1f else 0f
        if(d.width!=desiredWidth||d.weight!=weight){d.width=desiredWidth;d.weight=weight;details.layoutParams=d}
    }
}
