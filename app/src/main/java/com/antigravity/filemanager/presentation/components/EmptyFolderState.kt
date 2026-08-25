package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.filemanager.R
import com.antigravity.filemanager.presentation.theme.TextSecondary

/** Centered "nothing here" placeholder shown in place of a file list/grid when a folder has
 * no items — a sleepy owl napping in an open folder (drawable/empty_folder_owl.png, cropped
 * from the app's own design asset and converted to an alpha stencil so it tints with the
 * current theme instead of carrying its own baked-in colors), so an empty folder reads as
 * "confirmed empty" rather than "still loading" or "something's wrong". */
@Composable
fun EmptyFolderState(
    message: String = "This folder is empty",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.empty_folder_owl),
                contentDescription = null,
                colorFilter = ColorFilter.tint(TextSecondary),
                modifier = Modifier.size(140.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 15.sp
            )
        }
    }
}
