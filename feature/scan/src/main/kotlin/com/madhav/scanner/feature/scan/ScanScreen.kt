package com.madhav.scanner.feature.scan

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ScanScreen(
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PreviewView(context).apply {
                    viewModel.bindCamera(lifecycleOwner, surfaceProvider)
                }
            },
        )

        OverlayCanvas(
            quad = uiState.quad,
            scanState = uiState.scanState,
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            text = uiState.guidance,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        FloatingActionButton(
            onClick = viewModel::manualShutter,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
        ) {
            Text("Capture")
        }

        uiState.result?.let { scan ->
            ResultSheet(scan = scan, onDismiss = viewModel::dismissResult)
        }
    }
}
