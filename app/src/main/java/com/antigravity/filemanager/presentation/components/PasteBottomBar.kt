package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.filemanager.presentation.theme.DarkCard
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextPrimary

@Composable
fun PasteBottomBar(
    itemCount: Int,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = DarkCard,
        tonalElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 6.dp, top = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$itemCount item(s) in clipboard",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Row {
                TextButton(onClick = onCancel) {
                    Text(
                        text = "CANCEL",
                        color = Color(0xFFEF5350),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onPaste,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TealPrimary,
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "PASTE HERE",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
