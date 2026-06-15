package com.example

import android.graphics.Bitmap
import java.util.UUID

data class Stamp(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int,
    val normX: Float, // 0f to 1f relative to page width
    val normY: Float, // 0f to 1f relative to page height
    val scale: Float = 1.0f,
    val aspectRatio: Float = 1.0f,
    val bitmap: Bitmap? = null,
    val isSelected: Boolean = false
)
