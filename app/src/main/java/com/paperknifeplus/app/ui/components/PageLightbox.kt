package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.memory.MemoryCache
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageRequest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageLightbox(
    uri: Uri,
    initialPage: Int,
    totalCount: Int,
    password: String?,
    onDismiss: () -> Unit,
    selectedPages: Set<Int>? = null,
    onToggleSelection: ((Int) -> Unit)? = null,
    isGrayscale: Boolean = false,
    itemOverlay: @Composable (BoxScope.(Int) -> Unit)? = null,
    bottomBar: @Composable (BoxScope.(Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialPage) { totalCount }
    val scope = rememberCoroutineScope()
    
    var showJumpDialog by remember { mutableStateOf(false) }

    // The shared loader's fetcher and keyer with a larger cache of its own for the 2x pages shown here.
    val imageLoader = remember {
        PdfImageLoader.get(context).newBuilder()
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.40).build() }
            .build()
    }

    // Paging is disabled while the current page is zoomed, so horizontal drags pan it instead.
    val zoomLevels = remember { mutableStateMapOf<Int, Float>() }
    val isCurrentPageZoomed by remember {
        derivedStateOf { (zoomLevels[pagerState.currentPage] ?: 1f) > 1.01f }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp,
                beyondBoundsPageCount = 1,
                userScrollEnabled = !isCurrentPageZoomed && (bottomBar == null)
            ) { pageIndex ->
                val request = remember(uri, pageIndex, password) { 
                    PdfPageRequest(uri, pageIndex, password, 2.0f, priority = 1) 
                }
                
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                
                LaunchedEffect(scale) {
                    zoomLevels[pageIndex] = scale
                }

                val animatedScale by animateFloatAsState(targetValue = scale, label = "scale")
                val animatedOffset by animateOffsetAsState(targetValue = offset, label = "offset")

                LaunchedEffect(pagerState.currentPage) {
                    if (pagerState.currentPage != pageIndex) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }

                BoxWithConstraints(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
                        scale = (scale * zoomChange).coerceIn(1f, 4f)
                        offset = zoomPanClamp(offset + offsetChange, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), scale)
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { tapOffset ->
                                        if (scale > 1.01f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                            offset = doubleTapZoomOffset(tapOffset, size.toSize(), scale)
                                        }
                                    }
                                )
                            }
                            .then(if (scale > 1.01f) Modifier.transformable(state) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        val painter = rememberAsyncImagePainter(request, imageLoader)
                        
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = animatedScale,
                                    scaleY = animatedScale,
                                    translationX = animatedOffset.x,
                                    translationY = animatedOffset.y
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painter,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                                colorFilter = if (isGrayscale) GrayscaleColorFilter else null
                            )
                            
                            // Inside the zoomed layer, so tool overlays (page numbers etc.) zoom with the page.
                            itemOverlay?.invoke(this, pageIndex)
                        }

                        if (painter.state is AsyncImagePainter.State.Loading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.background(Color.White.copy(0.1f), CircleShape)
                        ) {
                            Icon(Icons.Filled.Close, null, tint = Color.White)
                        }
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share PDF"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot share this file", Toast.LENGTH_SHORT).show()
                            }
                        }, modifier = Modifier.background(Color.White.copy(0.1f), CircleShape)) {
                            Icon(Icons.Filled.Share, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            onClick = { showJumpDialog = true },
                            color = Color.White.copy(0.1f), 
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "${pagerState.currentPage + 1} / $totalCount",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
            
            if (showJumpDialog) {
                JumpToPageDialog(
                    initialInput = (pagerState.currentPage + 1).toString(),
                    pageCount = totalCount,
                    onJump = { index ->
                        scope.launch { pagerState.scrollToPage(index) }
                        showJumpDialog = false
                    },
                    onDismiss = { showJumpDialog = false }
                )
            }
            
            if (onToggleSelection != null && selectedPages != null && bottomBar == null) {
                Box(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 72.dp), contentAlignment = Alignment.TopCenter) {
                    val isSelected = selectedPages.contains(pagerState.currentPage)
                    Button(
                        onClick = { onToggleSelection(pagerState.currentPage) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Color(0xFF10B981) else Color.White.copy(0.1f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (isSelected) {
                            Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isSelected) "SELECTED" else "SELECT", fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                bottomBar?.invoke(this, pagerState.currentPage)
            }

            if (bottomBar == null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                        .background(Color.Black.copy(0.5f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        enabled = pagerState.currentPage > 0
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = if (pagerState.currentPage > 0) Color.White else Color.White.copy(0.3f))
                    }

                    IconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                        enabled = pagerState.currentPage < totalCount - 1
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = if (pagerState.currentPage < totalCount - 1) Color.White else Color.White.copy(0.3f))
                    }
                }
            }
        }
    }
}
