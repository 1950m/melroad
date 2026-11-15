package com.studio1950.melroad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studio1950.melroad.ui.theme.HyundaiBlue
import com.studio1950.melroad.ui.theme.HyundaiBlueLight
import com.studio1950.melroad.ui.theme.Ink
import com.studio1950.melroad.ui.theme.SkyBlue
import com.studio1950.melroad.ui.theme.Subtle

enum class IconKind { PLAY, PAUSE, STOP, BACK, NONE }

/**
 * 입체감 있는 큰 버튼(최소 88dp). 위쪽이 밝은 그라데이션 + 그림자 + 윗면 하이라이트로 눌러지는 버튼처럼 보이게 했다.
 * 아이콘은 장식이고 의미는 항상 글자로 전달한다.
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: IconKind = IconKind.NONE,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(44.dp)
    val fill = if (primary) {
        Brush.verticalGradient(listOf(HyundaiBlueLight, HyundaiBlue))
    } else {
        Brush.verticalGradient(listOf(Color.White, Color(0xFFF2F5F9)))
    }
    val contentColor = if (primary) Color.White else HyundaiBlue
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .shadow(if (enabled) 8.dp else 0.dp, shape, ambientColor = HyundaiBlue.copy(alpha = 0.5f), spotColor = HyundaiBlue.copy(alpha = 0.5f))
            .clip(shape)
            .background(fill)
            .border(
                width = if (primary) 1.dp else 1.5.dp,
                brush = if (primary) {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent))
                } else {
                    Brush.verticalGradient(listOf(Color(0xFFB9C4D2), Color(0xFFD5DDE6)))
                },
                shape = shape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(semanticsModifier)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != IconKind.NONE) {
                SimpleIcon(icon, contentColor)
                Spacer(Modifier.width(16.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SimpleIcon(kind: IconKind, color: Color, size: Int = 28) {
    Canvas(Modifier.size(size.dp).clearAndSetSemantics { }) {
        val w = this.size.width
        val h = this.size.height
        when (kind) {
            IconKind.PLAY -> drawPath(
                Path().apply {
                    moveTo(w * 0.2f, h * 0.1f)
                    lineTo(w * 0.9f, h * 0.5f)
                    lineTo(w * 0.2f, h * 0.9f)
                    close()
                },
                color,
            )
            IconKind.PAUSE -> {
                drawRect(color, Offset(w * 0.18f, h * 0.12f), Size(w * 0.22f, h * 0.76f))
                drawRect(color, Offset(w * 0.6f, h * 0.12f), Size(w * 0.22f, h * 0.76f))
            }
            IconKind.STOP -> drawRect(color, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f))
            IconKind.BACK -> drawPath(
                Path().apply {
                    moveTo(w * 0.7f, h * 0.1f)
                    lineTo(w * 0.25f, h * 0.5f)
                    lineTo(w * 0.7f, h * 0.9f)
                    lineTo(w * 0.7f, h * 0.72f)
                    lineTo(w * 0.45f, h * 0.5f)
                    lineTo(w * 0.7f, h * 0.28f)
                    close()
                },
                color,
            )
            IconKind.NONE -> Unit
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.semantics { heading() },
    )
}

/** 상태 문장. 화면 낭독기가 변경을 알아서 읽도록 liveRegion을 건다. */
@Composable
fun StatusText(text: String, modifier: Modifier = Modifier, large: Boolean = true, medium: Boolean = false) {
    Text(
        text,
        style = when {
            medium -> MaterialTheme.typography.headlineMedium
            large -> MaterialTheme.typography.displaySmall
            else -> MaterialTheme.typography.bodyLarge
        },
        color = if (large) HyundaiBlue else Ink,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** 색만으로 구분하지 않도록 글자 배지를 쓴다. */
@Composable
fun Badge(text: String, emphasized: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .shadow(4.dp, shape)
            .background(if (emphasized) SkyBlue else Color.White, shape)
            .border(2.dp, HyundaiBlue, shape)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = HyundaiBlue)
    }
}

/** 흰 카드: 둥근 모서리 + 부드러운 그림자로 떠 있는 느낌 */
@Composable
fun SectionCard(modifier: Modifier = Modifier, glass: Boolean = false, elevation: Dp = 12.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(elevation, shape, ambientColor = HyundaiBlue.copy(alpha = 0.4f), spotColor = HyundaiBlue.copy(alpha = 0.4f))
            .clip(shape)
            .background(
                if (glass) {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.94f), Color.White.copy(alpha = 0.86f)))
                } else {
                    Brush.verticalGradient(listOf(Color.White, Color(0xFFF7F9FC)))
                },
            )
            .border(1.dp, Color.White, shape),
    ) {
        Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

@Composable
fun BackRow(onBack: () -> Unit, backLabel: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        BigButton(
            text = backLabel,
            onClick = onBack,
            modifier = Modifier.width(200.dp),
            primary = false,
            icon = IconKind.BACK,
        )
        ScreenTitle(title)
    }
}

@Composable
fun SubtleText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Subtle, modifier = modifier)
}

/** 여정 진행 막대(장식 + 글자 병행) */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(12.dp).clearAndSetSemantics { }) {
        val r = size.height / 2
        drawRoundRect(Color(0xFFC3CCD8), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(HyundaiBlue, com.studio1950.melroad.ui.theme.ActiveBlue)),
            size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
    }
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}


/** 정보 타일: 작은 제목 + 큰 값. 벤토 그리드 형태로 오른쪽 정보 영역을 구성한다. */
@Composable
fun InfoTile(label: String, value: String, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .shadow(6.dp, shape, ambientColor = HyundaiBlue.copy(alpha = 0.3f), spotColor = HyundaiBlue.copy(alpha = 0.3f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF8FAFC))))
            .border(1.dp, Color(0xFFE3E8EF), shape)
            .semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Subtle)
            Text(value, style = MaterialTheme.typography.titleLarge, color = Ink)
            extra()
        }
    }
}

/** 강조 타일: 현대 블루 그라데이션 위 흰 글자(대비 13:1). 현재 상태 문장을 담는다. */
@Composable
fun HeroTile(
    modifier: Modifier = Modifier,
    decoration: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(32.dp)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = HyundaiBlue, spotColor = HyundaiBlue)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0D4F96), HyundaiBlue, Color(0xFF001E42))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)), shape),
    ) {
        decoration?.invoke(this)
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
fun HeroText(text: String, large: Boolean = false, live: Boolean = false, small: Boolean = false) {
    Text(
        text,
        style = when {
            large -> MaterialTheme.typography.headlineMedium
            small -> MaterialTheme.typography.bodyMedium
            else -> MaterialTheme.typography.bodyLarge
        },
        color = if (large) Color.White else Color.White.copy(alpha = 0.86f),
        modifier = if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
    )
}
