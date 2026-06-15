package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun SignaturePad(
    onSignatureCaptured: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPathPoints by remember { mutableStateOf(listOf<Offset>()) }
    val paths = remember { mutableStateListOf<List<Offset>>() }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ارسم توقيعك بالأسفل",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentPathPoints = listOf(offset)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val newPoint = change.position
                                    currentPathPoints = currentPathPoints + newPoint
                                },
                                onDragEnd = {
                                    if (currentPathPoints.isNotEmpty()) {
                                        paths.add(currentPathPoints)
                                        currentPathPoints = emptyList()
                                    }
                                }
                            )
                        }
                ) {
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        // Draw all finished paths in classic blue ink
                        paths.forEach { path ->
                            if (path.size > 1) {
                                for (i in 0 until path.size - 1) {
                                    drawLine(
                                        color = Color(0xFF0F4C81), // Solid Classic Royal Blue
                                        start = path[i],
                                        end = path[i+1],
                                        strokeWidth = 6f,
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                        }
                        // Draw active drawing path
                        if (currentPathPoints.size > 1) {
                            for (i in 0 until currentPathPoints.size - 1) {
                                drawLine(
                                    color = Color(0xFF0F4C81),
                                    start = currentPathPoints[i],
                                    end = currentPathPoints[i+1],
                                    strokeWidth = 6f,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }

                    if (paths.isEmpty() && currentPathPoints.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "وقّع بإصبعك هنا",
                                color = Color.Gray.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = { paths.clear() },
                        enabled = paths.isNotEmpty()
                    ) {
                        Text("مسح اللوحة", color = MaterialTheme.colorScheme.error)
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text("إلغاء")
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Button(
                            onClick = {
                                if (paths.isNotEmpty()) {
                                    // Make a signature bitmap with transparent background
                                    val bitmap = Bitmap.createBitmap(550, 250, Bitmap.Config.ARGB_8888)
                                    val canvas = Canvas(bitmap)
                                    canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                                    val paint = Paint().apply {
                                        color = android.graphics.Color.parseColor("#0F4C81") // Traditional Arabic Blue
                                        strokeWidth = 8f
                                        strokeCap = Paint.Cap.ROUND
                                        strokeJoin = Paint.Join.ROUND
                                        style = Paint.Style.STROKE
                                        isAntiAlias = true
                                    }

                                    // Find stroke bounding coordinates to trim / crop empty spacing
                                    val bounds = RectF()
                                    paths.forEach { path ->
                                        path.forEach { pt ->
                                            if (bounds.isEmpty) {
                                                bounds.set(pt.x, pt.y, pt.x, pt.y)
                                            } else {
                                                bounds.union(pt.x, pt.y)
                                            }
                                        }
                                    }

                                    if (!bounds.isEmpty) {
                                        val targetW = 480f
                                        val targetH = 180f
                                        val scaleX = targetW / bounds.width()
                                        val scaleY = targetH / bounds.height()
                                        val scale = minOf(scaleX, scaleY, 1.2f)

                                        val dx = 275f - bounds.centerX() * scale
                                        val dy = 125f - bounds.centerY() * scale

                                        paths.forEach { path ->
                                            if (path.size > 1) {
                                                val p = android.graphics.Path()
                                                p.moveTo(path[0].x * scale + dx, path[0].y * scale + dy)
                                                for (i in 1 until path.size) {
                                                    p.lineTo(path[i].x * scale + dx, path[i].y * scale + dy)
                                                }
                                                canvas.drawPath(p, paint)
                                            }
                                        }
                                    }

                                    onSignatureCaptured(bitmap)
                                }
                            },
                            enabled = paths.isNotEmpty()
                        ) {
                            Text("حفظ التوقيع")
                        }
                    }
                }
            }
        }
    }
}
