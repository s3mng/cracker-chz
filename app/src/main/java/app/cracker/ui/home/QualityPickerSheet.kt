package app.cracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cracker.model.JobKind
import app.cracker.model.VideoMeta
import app.cracker.ui.theme.AdultClay
import app.cracker.ui.theme.Cheddar
import app.cracker.ui.theme.Ink
import app.cracker.ui.theme.LiveCoral

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityPickerSheet(
    meta: VideoMeta,
    selectedQualityId: String?,
    isLoggedIn: Boolean,
    onSelectQuality: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onLogin: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(
                when (meta.kind) {
                    JobKind.Live -> "라이브 녹화"
                    JobKind.Clip -> "클립 저장"
                    JobKind.Vod -> "다시보기 저장"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (meta.kind == JobKind.Live) LiveCoral else Cheddar,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(meta.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                listOfNotNull(meta.channel, meta.durationLabel).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (meta.isAdult) {
                Spacer(Modifier.height(16.dp))
                AdultBanner(isLoggedIn)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "화질",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                meta.qualities.forEach { option ->
                    val selected = option.id == selectedQualityId
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectQuality(option.id) },
                        label = {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(option.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    option.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Ink else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cheddar,
                            selectedLabelColor = Ink,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = Cheddar,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            val needsLogin = meta.isAdult && !isLoggedIn
            Button(
                onClick = if (needsLogin) onLogin else onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cheddar,
                    contentColor = Ink,
                ),
            ) {
                Text(
                    when {
                        needsLogin -> "로그인하고 받기"
                        meta.kind == JobKind.Live -> "녹화 시작"
                        else -> "다운로드"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("취소", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AdultBanner(isLoggedIn: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AdultClay.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text("성인 콘텐츠", color = AdultClay, style = MaterialTheme.typography.labelLarge)
        Text(
            if (isLoggedIn) {
                "로그인된 네이버 계정으로만 기기에 요청합니다. 쿠키는 밖으로 나가지 않아요."
            } else {
                "이 영상은 본인 인증된 네이버 로그인이 필요합니다. 쿠키는 이 기기에만 암호화해서 둡니다."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
