package pt.pixellusitano.aguenta.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.pixellusitano.aguenta.domain.Level
import pt.pixellusitano.aguenta.domain.Snapshot
import pt.pixellusitano.aguenta.domain.eur
import kotlin.math.floor

val Obsidian = Color(0xFF0B0D13)
val Charcoal = Color(0xFF151922)
val Cyan = Color(0xFF00F5D4)
val Amber = Color(0xFFFFB703)
val Coral = Color(0xFFFF0054)
val Dim = Color.White.copy(alpha = 0.78f)

fun levelColor(l: Level?) = when (l) {
    Level.HALF, Level.HIGH -> Amber
    Level.EXCEEDED -> Coral
    else -> Cyan
}

@Composable
fun AguentaTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Cyan, onPrimary = Obsidian, secondary = Amber, error = Coral,
        background = Obsidian, surface = Charcoal, surfaceContainerHigh = Charcoal, onSurface = Color.White, onBackground = Color.White,
    ),
) {
    // Sem Surface por baixo, o Text cai no preto por omissão.
    CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
}

/** Vidro fosco: véu escuro para o texto se ler sobre cores vivas, brilho especular no topo e rebordo que se desvanece. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(28.dp)) = this
    .clip(shape)
    .background(Color(0xFF140A2E).copy(alpha = 0.28f))
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f))))
    .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.08f))), shape)

/** Gradiente abstrato vibrante: base índigo com manchas saturadas; a do canto acompanha o estado do dia. */
@Composable
fun Backdrop(accent: Color, content: @Composable BoxScope.() -> Unit) {
    val glow by animateColorAsState(accent, tween(800), label = "glow")
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B0A6B), Color(0xFF14082F), Color(0xFF3A0CA3))))) {
        // blur usa RenderEffect no Android 12+; abaixo disso não faz nada e o gradiente radial já dá um brilho suave.
        Blob(Color(0xFFF72585).copy(alpha = 0.85f), 360.dp, Modifier.align(Alignment.TopEnd).offset(110.dp, (-40).dp))
        Blob(glow.copy(alpha = 0.75f), 300.dp, Modifier.offset((-110).dp, 120.dp))
        Blob(Color(0xFFFF9E00).copy(alpha = 0.7f), 340.dp, Modifier.align(Alignment.BottomStart).offset((-90).dp, 60.dp))
        Blob(Color(0xFF4CC9F0).copy(alpha = 0.6f), 300.dp, Modifier.align(Alignment.CenterEnd).offset(120.dp, 140.dp))
        content()
    }
}

@Composable
private fun Blob(color: Color, size: Dp, modifier: Modifier) = Box(
    // Unbounded: o recorte retangular por omissão deixava arestas duras nas manchas.
    modifier.size(size).blur(70.dp, BlurredEdgeTreatment.Unbounded).background(Brush.radialGradient(listOf(color, Color.Transparent)), CircleShape),
)

@Composable
fun Pill(text: String, selected: Boolean, color: Color = Cyan, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Selecionado = cor sólida: sobre o gradiente vivo, texto colorido em fundo translúcido deixava de se ler.
    val fill by animateColorAsState(if (selected) color else Color.White.copy(alpha = 0.10f), tween(250), label = "pillFill")
    val edge by animateColorAsState(if (selected) color else Color.White.copy(alpha = 0.25f), tween(250), label = "pillEdge")
    val ink by animateColorAsState(if (selected && color.luminance() > 0.4f) Obsidian else Color.White, tween(250), label = "pillInk")
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .border(1.dp, edge, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = ink,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun Gauge(s: Snapshot) {
    val color = levelColor(s.level)
    val frac by animateFloatAsState(
        if (s.baseCents > 0) (s.remainingCents.toFloat() / s.baseCents).coerceIn(0f, 1f) else 0f, tween(900), label = "gauge",
    )
    Box(
        Modifier.size(270.dp).glass(CircleShape).semantics { contentDescription = "${eur(s.remainingCents)} para hoje de ${eur(s.baseCents)}" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(22.dp)) {
            val stroke = Stroke(16.dp.toPx(), cap = StrokeCap.Round)
            drawArc(Color.White.copy(alpha = 0.08f), 135f, 270f, false, style = stroke)
            drawArc(color.copy(alpha = 0.25f), 135f, 270f * frac, false, style = Stroke(30.dp.toPx(), cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * frac, false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(eur(s.remainingCents), fontSize = 44.sp, fontWeight = FontWeight.Black, color = if (s.remainingCents < 0) Coral else Color.White)
            Text("para hoje", color = Dim)
            Spacer(Modifier.height(6.dp))
            Text("teto ${eur(s.baseCents)}", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun RunwayBar(s: Snapshot, modifier: Modifier = Modifier) {
    val color = if (s.safeUntilPayday) Cyan else Coral
    // Com o relógio acertado para antes do início do ciclo, as frações saíam de [0, 1] e a barra transbordava.
    val elapsed = ((s.totalDays - s.daysLeft).toFloat() / s.totalDays).coerceIn(0f, 1f)
    val runway = (((s.runwayDays ?: s.daysLeft.toDouble()).coerceAtMost(s.daysLeft.toDouble())).toFloat() / s.totalDays).coerceIn(0f, 1f - elapsed)
    Column(modifier.fillMaxWidth().glass().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Faltam ${s.daysLeft} dias até dia ${s.payday.dayOfMonth}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                s.runwayDays?.let { if (s.safeUntilPayday) "chega ✔" else "aguentas ${floor(it).toInt()} dias" } ?: "sem gastos recentes",
                color = color, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = r)
            drawRoundRect(Color.White.copy(alpha = 0.25f), size = Size(size.width * elapsed, size.height), cornerRadius = r)
            drawRoundRect(color, Offset(size.width * elapsed, 0f), Size(size.width * runway, size.height), r)
        }
    }
}
