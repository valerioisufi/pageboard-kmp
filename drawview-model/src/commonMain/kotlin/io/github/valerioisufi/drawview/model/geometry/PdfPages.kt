package io.github.valerioisufi.drawview.model.geometry

import kotlin.math.min

/**
 * Rectangle of [pageRect] where a PDF page of [pdfWidth] x [pdfHeight] is drawn (centered fit, so
 * that a page whose format has been changed is not distorted).
 */
public fun fitPdfPage(pdfWidth: Double, pdfHeight: Double, pageRect: Rect): Rect {
    if (pdfWidth <= 0.0 || pdfHeight <= 0.0 || pageRect.isEmpty) return pageRect
    val scale = min(pageRect.width / pdfWidth, pageRect.height / pdfHeight).toFloat()
    val w = (pdfWidth * scale).toFloat()
    val h = (pdfHeight * scale).toFloat()
    val left = pageRect.left + (pageRect.width - w) / 2f
    val top = pageRect.top + (pageRect.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}
