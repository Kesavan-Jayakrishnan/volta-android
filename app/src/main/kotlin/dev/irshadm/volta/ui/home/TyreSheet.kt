package dev.irshadm.volta.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.irshadm.volta.R
import dev.irshadm.volta.ui.components.TOP_H
import dev.irshadm.volta.ui.components.TOP_W
import dev.irshadm.volta.ui.components.WHEEL_FRONT
import dev.irshadm.volta.ui.components.WHEEL_L
import dev.irshadm.volta.ui.components.WHEEL_R
import dev.irshadm.volta.ui.components.WHEEL_REAR
import dev.irshadm.volta.ui.components.Wheel
import dev.irshadm.volta.ui.components.VoltaButton
import dev.irshadm.volta.ui.components.VoltaButtonStyle
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.tabular
import dev.irshadm.volta.core.model.VehicleStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The tyre detail: the four pressures on the top-down car, the low one with its gap to the placard target,
 * how it has moved over the week, and two actions: "Checked it" (quiet until it drops further) and the target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TyreSheet(v: VehicleStatus, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version by TyreCare.version.collectAsState()
    val reading = v.tyrePsi
    val t = reading?.value
    val target = remember(version) { TyreCare.target(context) }
    val checks = remember(t, v.warningCodes, version) { TyreCare.checks(context, t, target, carFlag = TyreCare.carFlag(v.warningCodes?.value)) }
    val trends = remember(version) { TyreCare.trends(context) }
    var editing by remember { mutableStateOf(false) }
    val worst = checks.firstOrNull()

    // Open fully straight away: the car and the actions are the point, not a half-sheet peek.
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = VoltaColors.Surface, contentColor = VoltaColors.Text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text("Tyres", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        t == null -> "No tyre reading yet"
                        checks.size > 1 -> "${checks.size} tyres under: " + checks.joinToString(", ") { "${it.wheel.label.lowercase()} −${ceil(it.gap).toInt()}" }
                        worst != null && worst.gap >= 1 -> "${worst.wheel.label} is ${ceil(worst.gap).toInt()} psi under"
                        worst != null -> "The car flags ${worst.wheel.label.lowercase()} · ${worst.psi.roundToInt()} psi"
                        else -> "All four within ${TyreCare.GAP.toInt()} psi of target"
                    },
                    style = MaterialTheme.typography.bodyLarge, color = if (worst != null) VoltaColors.AmberText else VoltaColors.Green, modifier = Modifier.padding(top = 2.dp),
                )
                reading?.let { r ->
                    val cold = v.parked() && (v.ignitionChangedAtMillis?.let { System.currentTimeMillis() - it > 60 * 60_000L } ?: false)
                    Text("Read at ${clock(r.atMillis)}${if (cold) ", cold" else ""} · target ${target.roundToInt()} psi from the door placard",
                        style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted, modifier = Modifier.padding(top = 2.dp))
                }
            }

            if (t != null) Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(TOP_W / TOP_H)) {
                    Image(painterResource(R.drawable.cartop_base), null, Modifier.fillMaxWidth().fillMaxHeight(), contentScale = ContentScale.Fit)
                    val w = maxWidth
                    val h = maxHeight
                    val chipW = 92.dp
                    @Composable
                    fun chip(wheel: Wheel, fx: Float, fy: Float, left: Boolean) {
                        val psi = wheel.psi(t)
                        val gap = target - psi
                        val low = checks.any { it.wheel == wheel }
                        val x = if (left) w * fx - 18.dp - chipW else w * fx + 18.dp
                        Column(
                            Modifier.offset(x, h * fy - 24.dp).width(chipW).clip(RoundedCornerShape(14.dp))
                                .background(if (low) VoltaColors.tint(VoltaColors.Amber, 0.16f) else VoltaColors.SurfaceHigh)
                                .border(1.dp, if (low) VoltaColors.Amber else VoltaColors.Border, RoundedCornerShape(14.dp)).padding(vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("${psi.roundToInt()}", style = MaterialTheme.typography.titleMedium.tabular(), color = if (low) VoltaColors.Amber else VoltaColors.Text)
                            Text(wheel.label.lowercase() + if (gap >= 1) " · −${gap.roundToInt()}" else "", style = MaterialTheme.typography.labelSmall,
                                color = if (low) VoltaColors.AmberText else VoltaColors.Muted, maxLines = 1)
                        }
                    }
                    chip(Wheel.FrontLeft, WHEEL_L, WHEEL_FRONT, left = true)
                    chip(Wheel.FrontRight, WHEEL_R, WHEEL_FRONT, left = false)
                    chip(Wheel.RearLeft, WHEEL_L, WHEEL_REAR, left = true)
                    chip(Wheel.RearRight, WHEEL_R, WHEEL_REAR, left = false)
                }
            }

            val trend = trends.firstOrNull { it.wheel == worst?.wheel } ?: trends.maxByOrNull { it.dropPsi }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(VoltaColors.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (trend != null) Sparkline(trend.series, Modifier.width(88.dp).height(30.dp), falling = trend.dropPsi >= 1)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            trend == null -> "No week of readings yet"
                            trend.dropPsi >= 1 -> "${trend.wheel.label} down ${trend.dropPsi.roundToInt()} psi since ${trend.since}"
                            trend.dropPsi <= -1 -> "${trend.wheel.label} up ${abs(trend.dropPsi).roundToInt()} psi since ${trend.since}"
                            else -> "Steady since ${trend.since}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when {
                            trend == null -> "The daily check logs one cold reading a day."
                            trend.dropPsi >= 3 -> "Fast for a week. Worth checking for a puncture."
                            trend.dropPsi >= 1 -> "Slow loss, not a puncture. Top up and watch it."
                            else -> "Normal drift with the weather."
                        },
                        style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VoltaButton("Checked it", { t?.let { TyreCare.acknowledge(context, it) }; onDismiss() }, Modifier.weight(1f))
                VoltaButton("Set target", { editing = true }, style = VoltaButtonStyle.Secondary)
            }
            Text("Checked it keeps this quiet until the pressure changes", style = MaterialTheme.typography.labelSmall, color = VoltaColors.Dim,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }

    if (editing) TargetDialog(target, onDismiss = { editing = false }) { TyreCare.setTarget(context, it); editing = false }
}

@Composable
private fun Sparkline(series: List<Double>, modifier: Modifier, falling: Boolean) {
    val color = if (falling) VoltaColors.Amber else VoltaColors.Green
    Canvas(modifier) {
        if (series.size < 2) return@Canvas
        val lo = series.min() - 0.5
        val hi = series.max() + 0.5
        fun pt(i: Int) = Offset(size.width * i / (series.size - 1), size.height - ((series[i] - lo) / (hi - lo) * size.height).toFloat())
        val path = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until series.size) lineTo(pt(i).x, pt(i).y) }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 3.dp.toPx(), pt(series.size - 1))
    }
}

@Composable
private fun TargetDialog(current: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var psi by remember { mutableStateOf(current.roundToInt()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoltaColors.Surface,
        title = { Text("Tyre target", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The cold pressure on the sticker inside the driver's door. Volta flags a tyre ${TyreCare.GAP.toInt()} psi or more under it.",
                    style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ if (psi > 24) psi-- }) { Text("−", style = MaterialTheme.typography.titleLarge, color = VoltaColors.Text) }
                    Text("$psi psi", style = MaterialTheme.typography.headlineMedium.tabular(), modifier = Modifier.padding(horizontal = 18.dp))
                    TextButton({ if (psi < 45) psi++ }) { Text("+", style = MaterialTheme.typography.titleLarge, color = VoltaColors.Text) }
                }
            }
        },
        confirmButton = { TextButton({ onSave(psi.toDouble()) }) { Text("Save", color = VoltaColors.Gold) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = VoltaColors.Muted) } },
    )
}

private val clockFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun clock(ms: Long) = clockFmt.format(Date(ms)).lowercase()
