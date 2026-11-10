package com.swift.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** What was long-pressed: the video's direct file URL when the page exposes one. */
data class VideoTarget(val src: String?, val filename: String)

private val VSurface = Color(0xFF2E3138)
private val VLine = Color(0xFF4A4E57)
private val VText = Color(0xFFF1F2F4)
private val VMuted = Color(0xFF8E929B)

/** Brave-style menu for a long-pressed video. */
@Composable
fun VideoContextMenu(
    target: VideoTarget,
    onDismiss: () -> Unit,
    onDownloadVideo: () -> Unit,
    onCopyFrame: () -> Unit,
    onDownloadFrame: () -> Unit,
    onPictureInPicture: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(VSurface)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF3A3D45)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Videocam, null, tint = VText, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(18.dp))
                Text(
                    target.filename, color = VText, fontSize = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(color = VLine)
            if (target.src != null) {
                VideoRow("Download video") { onDismiss(); onDownloadVideo() }
            }
            VideoRow("Copy video frame") { onDismiss(); onCopyFrame() }
            VideoRow("Download video frame") { onDismiss(); onDownloadFrame() }
            VideoRow("Picture-in-Picture") { onDismiss(); onPictureInPicture() }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun VideoRow(label: String, onClick: () -> Unit) {
    Text(
        label, color = VText, fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp)
    )
}
