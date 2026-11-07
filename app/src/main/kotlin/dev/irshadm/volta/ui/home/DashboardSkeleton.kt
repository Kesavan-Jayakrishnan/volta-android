package dev.irshadm.volta.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.irshadm.volta.core.designsystem.LocalReducedMotion
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.data.ismart.CarModel
import dev.irshadm.volta.ui.components.CarPictures
import dev.irshadm.volta.ui.components.CarShot

private val Sheen = Color(0xFF30343B)

/** One light sweep shared by every placeholder, so they read as a single surface. Still under reduced motion. */
@Composable
internal fun skeletonBrush(): Brush {
    if (LocalReducedMotion.current) return Brush.linearGradient(listOf(VoltaColors.PanelTrack, VoltaColors.PanelTrack))
    val x by rememberInfiniteTransition(label = "skeleton").animateFloat(
        -400f,
        1400f,
        infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "sheen",
    )
    return Brush.linearGradient(listOf(VoltaColors.PanelTrack, Sheen, VoltaColors.PanelTrack), start = Offset(x, 0f), end = Offset(x + 400f, 120f))
}

@Composable
internal fun SkeletonBar(brush: Brush, width: Dp, height: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier.width(width).height(height).clip(shape).background(brush))
}

/** The car, held back until there's a reading to show it with. Same height as CarHero. */
@Composable
internal fun SkeletonCar(model: CarModel, modifier: Modifier = Modifier) {
    val picture = CarPictures.hero(model, CarShot.Locked)
    Box(modifier.fillMaxWidth().height(250.dp)) {
        Image(
            painterResource(picture),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // Darkened rather than faded: a faded photo lets the dusk sky show through the car.
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.55f, 0.55f, 0.55f, 1f) }),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        )
    }
}

/** HomePanel's shape while the first read is on its way: range and charge, the bar, AC and slider, the three actions. */
@Composable
internal fun PanelSkeleton(brush: Brush, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(VoltaColors.Panel)
            .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 14.dp)
            .semantics { contentDescription = "Loading your car's data" },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                SkeletonBar(brush, 112.dp, 46.dp, shape = RoundedCornerShape(12.dp))
                SkeletonBar(brush, 118.dp, 14.dp, Modifier.padding(top = 10.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                SkeletonBar(brush, 84.dp, 46.dp, shape = RoundedCornerShape(12.dp))
                SkeletonBar(brush, 138.dp, 14.dp, Modifier.padding(top = 10.dp))
            }
        }
        Box(Modifier.padding(top = 26.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(brush))
        Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(brush))
            Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(brush))
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp)) {
            repeat(3) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SkeletonBar(brush, 74.dp, 16.dp) }
            }
        }
    }
}
