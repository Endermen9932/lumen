package app.lumen.photos.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
}

/** An icon sitting inside one of the Material 3 Expressive shapes, slowly rotating if [spin]. */
@Composable
fun ShapeIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialShapes.Cookie9Sided.toShape(),
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 48.dp,
    spin: Boolean = false,
) {
    val rotation = if (spin) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart), label = "r").value
    } else 0f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { rotationZ = rotation }
                .clip(shape)
                .background(container)
        )
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ShapeIcon(icon, size = 112.dp, shape = MaterialShapes.Cookie12Sided.toShape(), spin = true)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** A tappable card row used on tool and settings screens. */
@Composable
fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialShapes.Cookie6Sided.toShape(),
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    iconContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconContent: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = container,
        border = app.lumen.photos.ui.theme.cardOutline(container),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(icon, shape = shape, container = iconContainer, content = iconContent, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing != null) trailing()
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Number that counts up smoothly whenever it changes. */
@Composable
fun AnimatedNumber(value: Long, format: (Long) -> String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) { anim.animateTo(value.toFloat(), tween(900)) }
    Text(format(anim.value.toLong()), style = style, modifier = modifier, color = color)
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        modifier = modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick) else Modifier)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
fun Dots(value: Int, max: Int = 5, color: Color = MaterialTheme.colorScheme.primary) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(max) { i ->
            Box(
                Modifier
                    .size(width = 14.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (i < value) color else color.copy(alpha = 0.18f))
            )
        }
    }
}
