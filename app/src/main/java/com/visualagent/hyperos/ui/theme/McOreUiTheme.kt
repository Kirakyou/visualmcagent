package com.visualagent.hyperos.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visualagent.hyperos.ui.sound.McSoundManager

/**
 * 第二部分 3：Minecraft OreUI 风格视觉规范 (参考 https://www.npmjs.com/package/mcui-oreui)
 * - 灰色立体面板、像素字体、绿/灰/红三态按钮
 * - 使用 Compose 自定义 Canvas drawBehind 绘制零圆角像素斜角高光与阴影边框
 */
object McColors {
    val BgDarkStone = Color(0xFF1E1E1F)
    val PanelGray = Color(0xFFC6C6C6)
    val PanelDarkGray = Color(0xFF313233)
    val BorderOuterBlack = Color(0xFF111111)

    // 绿色按钮 (Emerald / Primary Action)
    val GreenBase = Color(0xFF3C8527)
    val GreenLight = Color(0xFF52A535)
    val GreenDark = Color(0xFF2A641C)

    // 灰色按钮 (Stone / Secondary Action)
    val GrayBase = Color(0xFF7D7D7D)
    val GrayLight = Color(0xFFAAAAAA)
    val GrayDark = Color(0xFF4A4A4A)

    // 红色按钮 (Redstone / Stop & Danger Action)
    val RedBase = Color(0xFFB3312C)
    val RedLight = Color(0xFFD84640)
    val RedDark = Color(0xFF7A1E1B)
}

enum class McButtonVariant { GREEN, GRAY, RED }

/**
 * 自定义 Canvas 像素立体边框修饰符 (Minecraft OreUI Bevel Panel)
 */
fun Modifier.mcBevelPanel(
    baseColor: Color = McColors.PanelDarkGray,
    highlightColor: Color = Color(0xFF5A5B5C),
    shadowColor: Color = Color(0xFF181819),
    borderPx: Dp = 3.dp
): Modifier = this.drawBehind {
    val b = borderPx.toPx()
    val w = size.width
    val h = size.height

    // 1. 最外层纯黑像素轮廓
    drawRect(color = McColors.BorderOuterBlack, size = size)

    // 2. 内部主底色
    drawRect(
        color = baseColor,
        topLeft = Offset(b, b),
        size = Size(w - b * 2, h - b * 2)
    )

    // 3. 顶部与左侧高光边
    drawRect(
        color = highlightColor,
        topLeft = Offset(b, b),
        size = Size(w - b * 2, b)
    )
    drawRect(
        color = highlightColor,
        topLeft = Offset(b, b),
        size = Size(b, h - b * 2)
    )

    // 4. 底部与右侧暗部立体阴影边
    drawRect(
        color = shadowColor,
        topLeft = Offset(b, h - b * 2),
        size = Size(w - b * 2, b)
    )
    drawRect(
        color = shadowColor,
        topLeft = Offset(w - b * 2, b),
        size = Size(b, h - b * 2)
    )
}

/**
 * Minecraft 风格绿/灰/红三态像素按钮（内置按压下沉位移与 SoundPool 音效触发）
 */
@Composable
fun McPixelButton(
    text: String,
    variant: McButtonVariant = McButtonVariant.GRAY,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val (base, light, dark) = when {
        !enabled -> Triple(Color(0xFF444444), Color(0xFF555555), Color(0xFF2B2B2B))
        variant == McButtonVariant.GREEN -> Triple(McColors.GreenBase, McColors.GreenLight, McColors.GreenDark)
        variant == McButtonVariant.RED -> Triple(McColors.RedBase, McColors.RedLight, McColors.RedDark)
        else -> Triple(McColors.GrayBase, McColors.GrayLight, McColors.GrayDark)
    }

    // 按下时高光与暗部反转，呈现像素按键下沉感
    val activeHighlight = if (isPressed) dark else light
    val activeShadow = if (isPressed) light else dark

    Box(
        modifier = modifier
            .mcBevelPanel(
                baseColor = base,
                highlightColor = activeHighlight,
                shadowColor = activeShadow,
                borderPx = 3.dp
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) {
                McSoundManager.playClick()
                onClick()
            }
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (enabled) Color.White else Color(0xFF888888),
                fontFamily = FontFamily.Monospace, // 若放入 res/font/press_start_2p.ttf 可替换为 Font(R.font.press_start_2p)
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.8.sp
            )
        )
    }
}

@Composable
fun McPanelContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .mcBevelPanel(
                baseColor = McColors.PanelDarkGray,
                highlightColor = Color(0xFF585A5C),
                shadowColor = Color(0xFF1A1A1B),
                borderPx = 3.dp
            )
            .padding(12.dp),
        content = content
    )
}
