package com.bennod.pkmninstaller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bennod.pkmninstaller.ui.theme.PokeRed

/**
 * Draws a classic Pokéball: a red top half, a white bottom half, a black band
 * across the middle and a white centre button.
 *
 * @param size the diameter of the ball.
 */
@Composable
fun PokeBall(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val diameter = this.size.minDimension
        val radius = diameter / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val outline = diameter * 0.06f

        // Top half (red) and bottom half (white).
        drawArc(
            color = PokeRed,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(diameter, diameter),
        )
        drawArc(
            color = Color.White,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(diameter, diameter),
        )

        // Black band across the middle.
        val bandHeight = diameter * 0.12f
        drawRect(
            color = Color.Black,
            topLeft = Offset(center.x - radius, center.y - bandHeight / 2f),
            size = Size(diameter, bandHeight),
        )

        // Outer ring.
        drawCircle(
            color = Color.Black,
            radius = radius - outline / 2f,
            center = center,
            style = Stroke(width = outline),
        )

        // Centre button: black ring with a white core.
        val buttonRadius = diameter * 0.18f
        drawCircle(color = Color.Black, radius = buttonRadius, center = center)
        drawCircle(color = Color.White, radius = buttonRadius * 0.6f, center = center)
    }
}
