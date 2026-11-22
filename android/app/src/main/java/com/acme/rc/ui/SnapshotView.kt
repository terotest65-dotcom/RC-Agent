package com.acme.rc.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acme.rc.model.Element

@Composable
fun SnapshotView(
    image: ImageBitmap,
    elements: List<Element>
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(image.width.toFloat() / image.height.toFloat())
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    ) {
        Image(
            bitmap = image,
            contentDescription = "Device snapshot",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val width = size.width
            val height = size.height

            val numberBg = Paint().apply {
                color = android.graphics.Color.argb(205, 2, 6, 23)
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val numberText = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 11.sp.toPx()
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }

            elements.forEach { element ->
                val left = element.boundsNorm.left * width
                val top = element.boundsNorm.top * height
                val right = element.boundsNorm.right * width
                val bottom = element.boundsNorm.bottom * height
                val boxWidth = right - left
                val boxHeight = bottom - top
                if (boxWidth <= 0f || boxHeight <= 0f) return@forEach

                drawRect(
                    brush = SolidColor(Color(0x2838BDF8)),
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight)
                )
                drawRect(
                    color = Color(0xDC38BDF8),
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    style = Stroke(width = 2.dp.toPx())
                )

                val label = element.index.toString()
                val pad = 3.dp.toPx()
                val textWidth = numberText.measureText(label)
                val native = drawContext.canvas.nativeCanvas
                native.drawRoundRect(
                    left,
                    top,
                    left + textWidth + pad * 2,
                    top + numberText.textSize + pad * 2,
                    3.dp.toPx(),
                    3.dp.toPx(),
                    numberBg
                )
                native.drawText(
                    label,
                    left + pad,
                    top + numberText.textSize + pad * 0.6f,
                    numberText
                )
            }
        }
    }
}
