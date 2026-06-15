package com.example

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class PdfViewModel : ViewModel() {

    private val _pdfUri = MutableStateFlow<Uri?>(null)
    val pdfUri: StateFlow<Uri?> = _pdfUri.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _signatureBitmap = MutableStateFlow<Bitmap?>(null)
    val signatureBitmap: StateFlow<Bitmap?> = _signatureBitmap.asStateFlow()

    private val _stamps = MutableStateFlow<List<Stamp>>(emptyList())
    val stamps: StateFlow<List<Stamp>> = _stamps.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveSuccessMessage = MutableStateFlow<String?>(null)
    val saveSuccessMessage: StateFlow<String?> = _saveSuccessMessage.asStateFlow()

    private val _currentRenderedPage = MutableStateFlow<Bitmap?>(null)
    val currentRenderedPage: StateFlow<Bitmap?> = _currentRenderedPage.asStateFlow()

    private val _pageDimensions = MutableStateFlow<List<Pair<Int, Int>>>(emptyList())
    val pageDimensions: StateFlow<List<Pair<Int, Int>>> = _pageDimensions.asStateFlow()

    // Flag if loaded document is our sample
    private val _isSampleDoc = MutableStateFlow(false)
    val isSampleDoc: StateFlow<Boolean> = _isSampleDoc.asStateFlow()

    // File descriptor of current PDF to load pages dynamically
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    fun initSignature(context: Context) {
        viewModelScope.launch {
            val savedBitmap = loadSignatureFromPrefs(context)
            if (savedBitmap != null) {
                _signatureBitmap.value = savedBitmap
            }
        }
    }

    fun loadPdf(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                closeCurrentRenderer()
                _stamps.value = emptyList()
                _currentPage.value = 0
                _pdfUri.value = uri
                _isSampleDoc.value = false

                val contentResolver = context.contentResolver
                parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                
                parcelFileDescriptor?.let { pfd ->
                    val renderer = PdfRenderer(pfd)
                    pdfRenderer = renderer
                    _pageCount.value = renderer.pageCount

                    val dims = mutableListOf<Pair<Int, Int>>()
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        dims.add(Pair(page.width, page.height))
                        page.close()
                    }
                    _pageDimensions.value = dims
                    
                    renderPage(0)
                }
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Error loading PDF", e)
            }
        }
    }

    fun setSignature(context: Context, bitmap: Bitmap) {
        _signatureBitmap.value = bitmap
        saveSignatureToPrefs(context, bitmap)
    }

    fun clearSignature(context: Context) {
        _signatureBitmap.value = null
        val prefs = context.getSharedPreferences("pdf_signer_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("saved_sig_base64").apply()
    }

    fun renderPage(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= _pageCount.value) return
        _currentPage.value = pageIndex

        viewModelScope.launch(Dispatchers.IO) {
            val renderer = pdfRenderer ?: return@launch
            val dims = _pageDimensions.value
            if (pageIndex >= dims.size) return@launch

            try {
                val page = renderer.openPage(pageIndex)
                
                // Render at a responsive, sharp display width (e.g. 1000px)
                val targetWidth = 1000
                val scale = targetWidth.toFloat() / page.width.toFloat()
                val targetHeight = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                _currentRenderedPage.value = bitmap
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Error rendering page $pageIndex", e)
            }
        }
    }

    fun addStampToCurrentPage() {
        val sig = _signatureBitmap.value ?: return
        val pageIdx = _currentPage.value
        val aspect = sig.width.toFloat() / sig.height.toFloat()

        val newStamp = Stamp(
            pageIndex = pageIdx,
            normX = 0.5f,  // Centered initially
            normY = 0.5f,
            scale = 1.0f,
            aspectRatio = aspect,
            bitmap = sig,
            isSelected = true
        )

        // Unselect others on selection change
        val currentStamps = _stamps.value.map { it.copy(isSelected = false) }
        _stamps.value = currentStamps + newStamp
    }

    fun updateStamp(updated: Stamp) {
        _stamps.value = _stamps.value.map {
            if (it.id == updated.id) updated else {
                if (updated.isSelected) it.copy(isSelected = false) else it
            }
        }
    }

    fun deleteStamp(stamp: Stamp) {
        _stamps.value = _stamps.value.filter { it.id != stamp.id }
    }

    fun selectStamp(stampId: String) {
        _stamps.value = _stamps.value.map {
            it.copy(isSelected = it.id == stampId)
        }
    }

    fun clearStampSelection() {
        _stamps.value = _stamps.value.map { it.copy(isSelected = false) }
    }

    fun autoPositionStampBelowTeacher() {
        // Auto position at the typical "المعلم" spot:
        // By examining Mauritanian cards, "المعلم" is usually at the bottom-right (X=0.8f, Y=0.88f)
        // Let's drop a stamp there directly on current page!
        val sig = _signatureBitmap.value ?: return
        val pageIdx = _currentPage.value
        val aspect = sig.width.toFloat() / sig.height.toFloat()

        // Place stamp at typical Bottom Right teacher card coordinate
        val helperStamp = Stamp(
            pageIndex = pageIdx,
            normX = 0.8f,
            normY = 0.88f,
            scale = 1.0f,
            aspectRatio = aspect,
            bitmap = sig,
            isSelected = true
        )

        val currentStamps = _stamps.value.map { it.copy(isSelected = false) }
        _stamps.value = currentStamps + helperStamp
    }

    fun generateSamplePdf(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                closeCurrentRenderer()
                _stamps.value = emptyList()
                _currentPage.value = 0
                _isSampleDoc.value = true

                val sampleFile = File(context.cacheDir, "sample_results.pdf")
                val doc = PdfDocument()

                // Generate a highly polished, realistic 2-page Mauritanian grade sheets document (3AP)
                val paintHeader = Paint().apply {
                    color = Color.BLACK
                    textSize = 14f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                }
                val paintSubHeader = Paint().apply {
                    color = Color.DKGRAY
                    textSize = 10f
                    typeface = Typeface.DEFAULT
                    isAntiAlias = true
                }
                val paintText = Paint().apply {
                    color = Color.BLACK
                    textSize = 12f
                    typeface = Typeface.DEFAULT
                    isAntiAlias = true
                }
                val paintLabel = Paint().apply {
                    color = Color.BLACK
                    textSize = 11f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                }

                val originalWidth = 595 // A4 width
                val originalHeight = 842 // A4 height

                for (pageIdx in 0..1) {
                    val pageInfo = PdfDocument.PageInfo.Builder(originalWidth, originalHeight, pageIdx).create()
                    val page = doc.startPage(pageInfo)
                    val canvas = page.canvas

                    // Background white
                    canvas.drawColor(Color.WHITE)

                    // Draw decorative outer border
                    val borderPaint = Paint().apply {
                        color = Color.BLACK
                        strokeWidth = 2f
                        style = Paint.Style.STROKE
                    }
                    canvas.drawRect(Rect(15, 15, originalWidth - 15, originalHeight - 15), borderPaint)

                    // Header Right
                    canvas.drawText("الجمهورية الإسلامية الموريتانية", 40f, 45f, paintHeader)
                    canvas.drawText("وزارة التربية وإصلاح النظام التعليمي", 40f, 65f, paintSubHeader)
                    canvas.drawText("مفتشية مقاطعة لكصيبه 1", 40f, 85f, paintSubHeader)
                    canvas.drawText("مدرسة : الطلحايه 1", 40f, 105f, paintSubHeader)

                    // Header Left
                    canvas.drawText("شرف - إخاء - عدل", originalWidth - 180f, 45f, paintHeader)
                    canvas.drawText("السنة الدراسية: 2025 - 2026", originalWidth - 180f, 65f, paintSubHeader)
                    canvas.drawText("القسم: 3AP", originalWidth - 180f, 85f, paintSubHeader)
                    canvas.drawText("الفصل الدراسي: الثالث", originalWidth - 180f, 105f, paintSubHeader)

                    // Title
                    val titlePaint = Paint().apply {
                        color = Color.BLACK
                        textSize = 20f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText("نتائج امتحان التجاوز", originalWidth / 2f, 150f, titlePaint)

                    // Student details Box
                    val studentName = if (pageIdx == 0) "الاسم الكامل: تياتو شعيب صو" else "الاسم الكامل: عيسة آدم صو"
                    val studentNum = if (pageIdx == 0) "الرقم المدرسي: 2213577" else "الرقم المدرسي: 2218193"
                    canvas.drawText(studentName, 50f, 200f, paintLabel)
                    canvas.drawText(studentNum, originalWidth - 250f, 200f, paintLabel)

                    // Draw a lovely score table
                    val tablePaint = Paint().apply {
                        color = Color.BLACK
                        strokeWidth = 1.5f
                        style = Paint.Style.STROKE
                    }
                    val tableYStart = 230f
                    val colWidths = floatArrayOf(200f, 150f, 150f) // Material, Grade, Comments
                    val rowHeight = 35f
                    val rows = arrayOf(
                        arrayOf("اللغة العربية", "30 / 22", "جيد جداً"),
                        arrayOf("التربية الإسلامية", "20 / 15", "جيد"),
                        arrayOf("الرياضيات", "30 / 24", "جيد جداً"),
                        arrayOf("العلوم الطبيعية", "10 / 8", "ممتاز"),
                        arrayOf("الفرنسية", "20 / 14", "مستحسن"),
                        arrayOf("التربية البدنية", "10 / 9", "ممتاز"),
                        arrayOf("المعدل العام", "20 / 15.6", "ناجح")
                    )

                    // Draw table frame
                    val containerRect = RectF(40f, tableYStart, originalWidth - 40f, tableYStart + (rows.size + 1) * rowHeight)
                    canvas.drawRect(containerRect, tablePaint)

                    // Headers
                    val tableHeaderY = tableYStart + 22f
                    canvas.drawText("المادة", 70f, tableHeaderY, paintLabel)
                    canvas.drawText("النقاط", 260f, tableHeaderY, paintLabel)
                    canvas.drawText("الملاحظات", 420f, tableHeaderY, paintLabel)

                    // Divider below header
                    canvas.drawLine(40f, tableYStart + rowHeight, originalWidth - 40f, tableYStart + rowHeight, tablePaint)

                    // Draw row elements
                    for (r in rows.indices) {
                        val currentY = tableYStart + (r + 1) * rowHeight
                        val textY = currentY + 22f
                        canvas.drawText(rows[r][0], 70f, textY, paintText)
                        canvas.drawText(rows[r][1], 260f, textY, paintText)
                        canvas.drawText(rows[r][2], 420f, textY, paintText)
                        
                        // Line divider
                        canvas.drawLine(40f, currentY + rowHeight, originalWidth - 40f, currentY + rowHeight, tablePaint)
                    }

                    // Bottom Signatures Areas
                    val footerY = originalHeight - 110f
                    canvas.drawText("المدير", 80f, footerY, paintLabel)
                    
                    // Teacher "المعلم" at bottom-right
                    canvas.drawText("المعلم", originalWidth - 140f, footerY, paintLabel)

                    // Let's draw a light line representing signature bounding helper
                    val linePaint = Paint().apply {
                        color = Color.LTGRAY
                        strokeWidth = 1f
                        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
                        style = Paint.Style.STROKE
                    }
                    canvas.drawLine(originalWidth - 160f, footerY + 15f, originalWidth - 50f, footerY + 15f, linePaint)

                    doc.finishPage(page)
                }

                val outStream = FileOutputStream(sampleFile)
                doc.writeTo(outStream)
                outStream.close()
                doc.close()

                // Register sample PDF to live state
                val uri = Uri.fromFile(sampleFile)
                _pdfUri.value = uri
                parcelFileDescriptor = ParcelFileDescriptor.open(sampleFile, ParcelFileDescriptor.MODE_READ_ONLY)
                
                parcelFileDescriptor?.let { pfd ->
                    val renderer = PdfRenderer(pfd)
                    pdfRenderer = renderer
                    _pageCount.value = renderer.pageCount

                    val dims = mutableListOf<Pair<Int, Int>>()
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        dims.add(Pair(page.width, page.height))
                        page.close()
                    }
                    _pageDimensions.value = dims
                    
                    renderPage(0)
                }

            } catch (e: Exception) {
                Log.e("PdfViewModel", "Error generating sample", e)
            }
        }
    }

    fun savePdf(context: Context, outputUri: Uri) {
        _isSaving.value = true
        _saveSuccessMessage.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val srcUri = _pdfUri.value ?: return@launch
            val stampsList = _stamps.value
            val dims = _pageDimensions.value

            try {
                // Open original PDF
                val srcPfd = context.contentResolver.openFileDescriptor(srcUri, "r") ?: return@launch
                val renderer = PdfRenderer(srcPfd)
                val totalPages = renderer.pageCount

                val outputDoc = PdfDocument()

                for (i in 0 until totalPages) {
                    val page = renderer.openPage(i)
                    
                    // Original dimensions in points
                    val originalWidth = page.width
                    val originalHeight = page.height

                    // To guarantee a super crisp, publication-grade printout, 
                    // render the original page onto a 3x resolution bitmap!
                    val scaleFactor = 3f
                    val highResWidth = (originalWidth * scaleFactor).toInt()
                    val highResHeight = (originalHeight * scaleFactor).toInt()

                    val highResBitmap = Bitmap.createBitmap(highResWidth, highResHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(highResBitmap)
                    canvas.drawColor(Color.WHITE)

                    // Render original page contents onto high-res canvas (scaled by 3x)
                    val matrix = Matrix().apply { postScale(scaleFactor, scaleFactor) }
                    page.render(highResBitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // Add stamp(s) placed on this page
                    val stampsOnPage = stampsList.filter { it.pageIndex == i }
                    stampsOnPage.forEach { stamp ->
                        val sig = stamp.bitmap ?: return@forEach

                        // Normalized stamp dimensions
                        val stampWidthPx = highResWidth * 0.25f * stamp.scale
                        val stampHeightPx = stampWidthPx / stamp.aspectRatio

                        val stampX = stamp.normX * highResWidth
                        val stampY = stamp.normY * highResHeight

                        val destRect = RectF(
                            stampX - stampWidthPx / 2f,
                            stampY - stampHeightPx / 2f,
                            stampX + stampWidthPx / 2f,
                            stampY + stampHeightPx / 2f
                        )

                        // Draw signature bitmap on top
                        val stampPaint = Paint().apply {
                            isAntiAlias = true
                            isFilterBitmap = true
                        }
                        canvas.drawBitmap(sig, null, destRect, stampPaint)
                    }

                    // Burn high-res stamped bitmap to page info (representing standard points bounds)
                    val pageInfo = PdfDocument.PageInfo.Builder(originalWidth, originalHeight, i).create()
                    val outputPage = outputDoc.startPage(pageInfo)
                    val pageCanvas = outputPage.canvas

                    // Draw high-res stamped bitmap scaled down back to standard point coordinates inside PDF bounds
                    pageCanvas.drawBitmap(
                        highResBitmap,
                        null,
                        Rect(0, 0, originalWidth, originalHeight),
                        Paint().apply { isAntiAlias = true; isFilterBitmap = true }
                    )

                    outputDoc.finishPage(outputPage)
                    highResBitmap.recycle()
                }

                renderer.close()
                srcPfd.close()

                // Save outputs to location specified by URI
                val contentResolver = context.contentResolver
                contentResolver.openOutputStream(outputUri)?.use { outStream ->
                    outputDoc.writeTo(outStream)
                    outStream.flush()
                }
                outputDoc.close()

                withContext(Dispatchers.Main) {
                    _stamps.value = emptyList() // clear stamps since we successfully applied them
                    _saveSuccessMessage.value = "تم حفظ ملف PDF المعدّل بنجاح على جهازك!"
                    _isSaving.value = false
                    // Reload updated PDF
                    loadPdf(context, outputUri)
                }

            } catch (e: Exception) {
                Log.e("PdfViewModel", "Error compiling PDF", e)
                withContext(Dispatchers.Main) {
                    _isSaving.value = false
                    _saveSuccessMessage.value = "حدث خطأ أثناء حفظ الملف: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun closeCurrentRenderer() {
        try {
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        } catch (e: Exception) {
            // Ignored
        } finally {
            pdfRenderer = null
            parcelFileDescriptor = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeCurrentRenderer()
    }

    // SharedPreferences signature state persistence
    private fun saveSignatureToPrefs(context: Context, bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
        context.getSharedPreferences("pdf_signer_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_sig_base64", base64)
            .apply()
    }

    private fun loadSignatureFromPrefs(context: Context): Bitmap? {
        val prefs = context.getSharedPreferences("pdf_signer_prefs", Context.MODE_PRIVATE)
        val base64 = prefs.getString("saved_sig_base64", null) ?: return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}
