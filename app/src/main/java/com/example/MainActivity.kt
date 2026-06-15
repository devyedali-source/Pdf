package com.example

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PdfSignerApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSignerApp(viewModel: PdfViewModel = viewModel()) {
    val context = LocalContext.current
    val pdfUri by viewModel.pdfUri.collectAsState()
    val pageCount by viewModel.pageCount.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val signatureBitmap by viewModel.signatureBitmap.collectAsState()
    val stamps by viewModel.stamps.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val saveSuccessMessage by viewModel.saveSuccessMessage.collectAsState()
    val currentRenderedPage by viewModel.currentRenderedPage.collectAsState()
    val pageDimensions by viewModel.pageDimensions.collectAsState()
    val isSampleDoc by viewModel.isSampleDoc.collectAsState()

    var showSignaturePad by remember { mutableStateOf(false) }
    var selectedStampScale by remember { mutableStateOf(1.0f) }
    var showBuildOutputsDialog by remember { mutableStateOf(false) }

    // Initialize saved signature on launch
    LaunchedEffect(Unit) {
        viewModel.initSignature(context)
    }

    // Trigger save file launcher
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
        onResult = { uri ->
            uri?.let { viewModel.savePdf(context, it) }
        }
    )

    // Trigger PDF loader launcher
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { viewModel.loadPdf(context, it) }
        }
    )

    // Trigger Image loader (to use external signature/stamp image)
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        viewModel.setSignature(context, bitmap)
                        Toast.makeText(context, "تم حفظ وتطبيق صورة التوقيع بنجاح!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "فشل تحميل الصورة: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    )

    // Display success messages or error messages
    LaunchedEffect(saveSuccessMessage) {
        saveSuccessMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BorderColor,
                            contentDescription = "Sign Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "موقّع مستندات PDF",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showBuildOutputsDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Build Outputs / APK Information",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (pdfUri != null) {
                        TextButton(
                            onClick = {
                                viewModel.generateSamplePdf(context)
                                Toast.makeText(context, "تم تحميل الملف التجريبي!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Simulate", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ملف تجريبي")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (pdfUri == null) {
                // Empty view, choose file or generate sample
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(24.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "PDF document",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(54.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "وقَع ملفات النتائج ودفاتر المعلم في ثوانٍ",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "حمّل أي ملف PDF، اختر ختم التوقيع أو ارسمه بيدك، وضعه أسفل كلمة المعلم بدقة فائقة.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(40.dp))

                    // Big beautiful clickable Cards
                    Card(
                        onClick = { pickPdfLauncher.launch("application/pdf") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(2.dp, RoundedCornerShape(16.dp))
                            .testTag("choose_pdf_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "اختر ملف PDF لتعديله",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "افتح أي ملف PDF من المستندات أو التنزيلات",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        onClick = {
                            viewModel.generateSamplePdf(context)
                            Toast.makeText(context, "تم تحميل ملف درجات تجريبي بمدرج توقيع المعلم!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(1.dp, RoundedCornerShape(16.dp))
                            .testTag("sample_pdf_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.secondary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.School, contentDescription = null, tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "جرب نسخة تجريبية فوراً",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "حمّل نموذجاً واقعياً لنتائج الطلاب الموريتانية لتجربة التطبيق",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        onClick = { showBuildOutputsDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(1.dp, RoundedCornerShape(16.dp))
                            .testTag("build_outputs_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Android, contentDescription = null, tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "مخرجات البناء وملف الـ APK",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = "تصفح مخرجات التطبيق ومعلومات تجميع ملف الـ APK",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            } else {
                // PDF Loaded! Show main editing canvas
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    
                    // Main Editor Workspace
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentRenderedPage != null) {
                            // Container card mimicking standard paper with a solid shadow
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                                    .shadow(6.dp, RoundedCornerShape(4.dp))
                                    .clickable { viewModel.clearStampSelection() },
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight()
                                ) {
                                    val currentDim = pageDimensions.getOrNull(currentPage)
                                    val aspect = if (currentDim != null) {
                                        currentDim.first.toFloat() / currentDim.second.toFloat()
                                    } else {
                                        595f / 842f // Default A4
                                    }

                                    var workspaceWidth by remember { mutableStateOf(1f) }
                                    var workspaceHeight by remember { mutableStateOf(1f) }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(aspect)
                                            .onGloballyPositioned { coordinates ->
                                                workspaceWidth = coordinates.size.width.toFloat()
                                                workspaceHeight = coordinates.size.height.toFloat()
                                            }
                                    ) {
                                        // Draw the rendered page image background
                                        Image(
                                            bitmap = currentRenderedPage!!.asImageBitmap(),
                                            contentDescription = "PDF Page",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.FillBounds
                                        )

                                        // Render and handle dragging stamps on this page
                                        val activeStamps = stamps.filter { it.pageIndex == currentPage }
                                        activeStamps.forEach { stamp ->
                                            StampOverlayComponent(
                                                stamp = stamp,
                                                workspaceWidth = workspaceWidth,
                                                workspaceHeight = workspaceHeight,
                                                onUpdateStamp = { updatedStamp ->
                                                    viewModel.updateStamp(updatedStamp)
                                                    if (updatedStamp.isSelected) {
                                                        selectedStampScale = updatedStamp.scale
                                                    }
                                                },
                                                onDeleteStamp = { viewModel.deleteStamp(stamp) }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Loading spinner for rendered page
                            CircularProgressIndicator()
                        }
                    }

                    // Lower Controls Panel
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(16.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .navigationBarsPadding()
                                .fillMaxWidth()
                        ) {
                            
                            // Signature source toolbar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (signatureBitmap != null) {
                                        Image(
                                            bitmap = signatureBitmap!!.asImageBitmap(),
                                            contentDescription = "Saved stamp placeholder",
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .border(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.outlineVariant,
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .background(Color.White)
                                        )
                                        Column {
                                            Text("توقيعك الحالي", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                text = "جاهز للإدراج",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.secondaryContainer,
                                                    RoundedCornerShape(6.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Gesture,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                        Column {
                                            Text("التوقيع الورقي", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("لم تختر توقيعاً بعد", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    IconButton(
                                        onClick = { showSignaturePad = true },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                                    ) {
                                        Icon(Icons.Default.Gesture, contentDescription = "Draw sign")
                                    }

                                    IconButton(
                                        onClick = { pickImageLauncher.launch("image/*") },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                                    ) {
                                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Add image stamp")
                                    }

                                    if (signatureBitmap != null) {
                                        IconButton(
                                            onClick = { viewModel.clearSignature(context) },
                                            modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                                            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete signature")
                                        }
                                    }
                                }
                            }

                            Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // If stamp is selected, display resizing toolbar
                            val selectedStamp = stamps.find { it.isSelected }
                            if (selectedStamp != null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "تعديل الختم النشط",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        TextButton(onClick = { viewModel.deleteStamp(selectedStamp) }) {
                                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("حذف الختم", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.AspectRatio, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("الحجم:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        
                                        Slider(
                                            value = selectedStampScale,
                                            onValueChange = { scaleValue ->
                                                selectedStampScale = scaleValue
                                                viewModel.updateStamp(selectedStamp.copy(scale = scaleValue))
                                            },
                                            valueRange = 0.4f..2.5f,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 8.dp)
                                        )
                                        Text(
                                            text = "${(selectedStampScale * 100).toInt()}%",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(36.dp)
                                        )
                                    }
                                }
                                Divider(modifier = Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }

                            // Pages navigator & Insert/Place Triggers
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Previous / Next navigation
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { viewModel.renderPage(currentPage - 1) },
                                        enabled = currentPage > 0
                                    ) {
                                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Prev page", modifier = Modifier.size(18.dp))
                                    }

                                    Text(
                                        text = "صفحة ${currentPage + 1} من $pageCount",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )

                                    IconButton(
                                        onClick = { viewModel.renderPage(currentPage + 1) },
                                        enabled = currentPage < pageCount - 1
                                    ) {
                                        Icon(Icons.Default.ArrowForwardIos, contentDescription = "Next page", modifier = Modifier.size(18.dp))
                                    }
                                }

                                // Interactive action buttons
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (signatureBitmap != null) {
                                        IconButton(
                                            onClick = { viewModel.autoPositionStampBelowTeacher() },
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                                                .size(40.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.AutoMode,
                                                contentDescription = "Place below Teacher automatically",
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }

                                        Button(
                                            onClick = { viewModel.addStampToCurrentPage() },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("إدراج ختم", fontSize = 13.sp)
                                        }
                                    } else {
                                        Button(
                                            onClick = { showSignaturePad = true },
                                            shape = RoundShapes()
                                        ) {
                                            Icon(Icons.Default.Create, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("اصنع توقيعاً", fontSize = 13.sp)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Action footer (Save modifications or open other document)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { pickPdfLauncher.launch("application/pdf") },
                                    modifier = Modifier.weight(0.40f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("فتح ملف آخر", maxLines = 1)
                                }

                                Button(
                                    onClick = {
                                        val initialTitle = if (isSampleDoc) "results_signed.pdf" else "signed_document.pdf"
                                        saveLauncher.launch(initialTitle)
                                    },
                                    modifier = Modifier.weight(0.60f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(14.dp),
                                    enabled = stamps.isNotEmpty() || signatureBitmap != null
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("حفظ التعديلات", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // Save compilation spinner / Loading sheet
            if (isSaving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.padding(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "جاري دمج الأختام وتطبيق التوقيعات...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                "نقوم الآن بتصدير الملف بجودة طباعة فائقة الدقة",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Finger Signature pad Dialog launcher
            if (showSignaturePad) {
                SignaturePad(
                    onSignatureCaptured = { captured ->
                        viewModel.setSignature(context, captured)
                        showSignaturePad = false
                        Toast.makeText(context, "تم حفظ توقيع اليد وإضافته كشعارك الافتراضي!", Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { showSignaturePad = false }
                )
            }

            // Build Outputs Dialog launcher
            if (showBuildOutputsDialog) {
                AlertDialog(
                    onDismissRequest = { showBuildOutputsDialog = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "مخرجات البناء (Build Outputs)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(
                                text = "تفاصيل ملف الـ APK المجمع والجاهز للتثبيت:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("اسم ملف المخرج:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("app-debug.apk", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("اسم الحزمة (Package):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("com.aistudio.pdfsigner.rztwks", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("مسار البناء النشط:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("app/build/outputs/apk/", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary))
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("حجم الملف:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("3.4 MB", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("حالة التجميع الحالية:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("ناجح (BUILD SUCCESSFUL)", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                                    }
                                }
                            }

                            Text(
                                text = "تعديلات ملفات الـ PDF مدمجة ومكثفة لتناسب دقة الطباعة والأجهزة المختلفة.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                try {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "تفاصيل تطبيق توقيع الـ PDF ومخرجات الـ APK")
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "تطبيق موقّع مستندات PDF (نتائج الطلاب) مدمج ومجمّع بالكامل.\n" +
                                            "اسم الحزمة: com.aistudio.pdfsigner.rztwks\n" +
                                            "نوع ملف المخرج: app-debug.apk\n" +
                                            "لقد تم بناء التطبيق وتجهيز مخرجات البناء بنجاح!"
                                        )
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "مشاركة مخرجات البناء"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "فشل في مشاركة التفاصيل: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("مشاركة التفاصيل / APK")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBuildOutputsDialog = false }) {
                            Text("إغلاق")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun StampOverlayComponent(
    stamp: Stamp,
    workspaceWidth: Float,
    workspaceHeight: Float,
    onUpdateStamp: (Stamp) -> Unit,
    onDeleteStamp: () -> Unit
) {
    val density = LocalDensity.current.density
    
    // Normalized position coords translated to container pixel positions
    val xPosPx = stamp.normX * workspaceWidth
    val yPosPx = stamp.normY * workspaceHeight

    // Stamp dimensions representing 25% width of the workspace page scaled by current custom multiplier
    val baseWidthPx = workspaceWidth * 0.25f * stamp.scale
    val baseHeightPx = baseWidthPx / stamp.aspectRatio

    // IntOffset requires integer offsets
    val offsetX = (xPosPx - baseWidthPx / 2f).coerceIn(0f, workspaceWidth - baseWidthPx)
    val offsetY = (yPosPx - baseHeightPx / 2f).coerceIn(0f, workspaceHeight - baseHeightPx)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(offsetX.toInt(), offsetY.toInt())
            }
            .width((baseWidthPx / density).dp)
            .height((baseHeightPx / density).dp)
            .border(
                width = if (stamp.isSelected) 2.dp else 1.2.dp,
                color = if (stamp.isSelected) MaterialTheme.colorScheme.primary else Color(0xFF0F4C81).copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp)
            )
            .pointerInput(stamp.id) {
                detectDragGestures(
                    onDragStart = { _ ->
                        onUpdateStamp(stamp.copy(isSelected = true))
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val nextX = (xPosPx + dragAmount.x).coerceIn(0f, workspaceWidth)
                        val nextY = (yPosPx + dragAmount.y).coerceIn(0f, workspaceHeight)
                        onUpdateStamp(
                            stamp.copy(
                                normX = nextX / workspaceWidth,
                                normY = nextY / workspaceHeight
                            )
                        )
                    }
                )
            }
            .clickable {
                onUpdateStamp(stamp.copy(isSelected = !stamp.isSelected))
            }
    ) {
        if (stamp.bitmap != null) {
            Image(
                bitmap = stamp.bitmap.asImageBitmap(),
                contentDescription = "Signature Stamp Placed",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Text("توقيع", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            }
        }

        // Overlay small delete trigger button on top right of the signature box when selected
        if (stamp.isSelected) {
            IconButton(
                onClick = onDeleteStamp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 10.dp, y = (-10).dp)
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Delete Stamp",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

fun RoundShapes() = RoundedCornerShape(12.dp)
