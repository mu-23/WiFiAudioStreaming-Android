/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * --------------------------------------------------------------------------
 * ExpressiveExpandable.kt
 *
 * Le cose che si aprono.
 *
 * Sotto la ricerca dei dispositivi ci sono i modi manuali di collegarsi —
 * Snapcast per indirizzo, RTP per indirizzo e formato. Sono moduli: campi,
 * bottoni, elenchi. Tenerli aperti tutti insieme vuol dire due muri identici
 * uno sotto l'altro, e chi arriva alla schermata non vede piu' la ricerca, che
 * e' la cosa che serve nove volte su dieci.
 *
 * Qui stanno i pezzi che li rendono richiudibili senza farli diventare due
 * righe morte: una card che si apre con un peso addosso, il contenuto che
 * entra sfalsato invece che tutto insieme, una piega interna per i campi che
 * quasi nessuno tocca. Il movimento non e' decorazione — dice dove sei: la
 * forma del segno cambia quando la card e' aperta, l'angolo si stringe sotto
 * il dito, la card aperta si stacca dallo sfondo.
 *
 * Le molle sono quelle del resto dell'app (MorphIconBadge, ExpressiveDeviceRow):
 * stessa rigidita', stesso rimbalzo. Due fisiche diverse nella stessa schermata
 * si notano, anche senza sapere perche'.
 */

package com.cuscus.wifiaudiostreaming

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay

/* Le molle: una sola volta, cosi' non divergono da sole. */

/** Apertura: un filo di rimbalzo, abbastanza morbida da vedersi. */
private fun <T> openSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow
)

/** L'altezza che si apre: stessa molla, con la soglia giusta per i pixel. */
private fun openSizeSpring() = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntSize.VisibilityThreshold
)

/** Chiusura: nessun rimbalzo. Una cosa che si chiude e poi torna su e' un errore. */
private fun closeSizeSpring() = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = IntSize.VisibilityThreshold
)

/**
 * Una card che si apre.
 *
 * Chiusa e' una riga: segno, titolo, sottotitolo, quante cose ci sono dentro.
 * Aperta e' il modulo. Fra le due c'e' una molla, e il segno cambia forma —
 * da cerchio a [openShape] — perche' aperto e chiuso si vedano di colpo
 * d'occhio anche senza leggere.
 *
 * [stateKey] tiene lo stato attraverso la rotazione dello schermo: due card
 * nella stessa schermata devono avere chiavi diverse o si aprono insieme.
 *
 * Il contenuto riceve il proprio indice di apparizione tramite [ExpressiveReveal]:
 * non entra tutto insieme, entra a scalare, che e' come si apre una cosa vera.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveExpandableCard(
    icon: ImageVector,
    openShape: RoundedPolygon,
    title: String,
    subtitle: String,
    accent: Color,
    stateKey: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptics = rememberAppHaptics()
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Sotto il dito la card si stringe; aperta si allarga. Sono i due angoli
    // che gia' fanno le righe dei dispositivi, con in mezzo un terzo stato.
    val corner by animateDpAsState(
        targetValue = when {
            pressed -> 16.dp
            expanded -> 34.dp
            else -> 28.dp
        },
        animationSpec = openSpring(),
        label = "CardCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "CardScale"
    )
    val container by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "CardContainer"
    )

    val morph = remember(openShape) { Morph(MaterialShapes.Circle, openShape) }
    val morphProgress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        morphProgress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }
    // Il segno si gira di poco aprendosi: quel tanto che basta perche' il
    // cambio di forma sembri un movimento e non uno scatto di fotogramma.
    val badgeSpin by animateFloatAsState(
        targetValue = if (expanded) 0f else -22f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "BadgeSpin"
    )
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "CardChevron"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(corner))
            .background(container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = interaction, indication = null) {
                    haptics.tap()
                    expanded = !expanded
                }
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer { rotationZ = badgeSpin }
                    .clip(MorphOutlineShape(morph, morphProgress.value))
                    .background(accent.copy(alpha = if (expanded) 0.26f else 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = -badgeSpin },
                    tint = accent
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (badge != null) {
                Spacer(Modifier.width(10.dp))
                ExpressiveCountPill(text = badge, accent = accent)
            }

            Spacer(Modifier.width(6.dp))

            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = stringResourceExpand(expanded),
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = chevron },
                tint = accent
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = openSizeSpring()) + fadeIn(tween(200)),
            exit = shrinkVertically(animationSpec = closeSizeSpring()) + fadeOut(tween(110))
        ) {
            Column(
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}

/** "Espandi"/"Riduci" per chi naviga a voce. */
@Composable
private fun stringResourceExpand(expanded: Boolean): String =
    androidx.compose.ui.res.stringResource(
        if (expanded) R.string.card_collapse else R.string.card_expand
    )

/** Il numerino accanto al titolo: quante cose ci sono, senza aprire. */
@Composable
fun ExpressiveCountPill(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        // Salvi una sorgente e il numero cambia: se cambia e basta non se ne
        // accorge nessuno, e non si capisce che il tocco ha fatto qualcosa.
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(initialScale = 0.7f))
                    .togetherWith(fadeOut(tween(90)) + scaleOut(targetScale = 0.7f))
            },
            label = "CountPill"
        ) { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = accent
            )
        }
    }
}

/**
 * Un pezzo di contenuto che entra dopo quello prima.
 *
 * Dentro una card che si apre, far comparire otto campi nello stesso istante
 * li fa leggere come un muro. A scalare di poche decine di millisecondi si
 * leggono come un elenco, e l'occhio segue l'ordine invece di doverlo cercare.
 *
 * L'animazione parte quando il contenuto entra in composizione, cioe' a ogni
 * apertura: la card chiusa non tiene in vita i figli.
 */
@Composable
fun ExpressiveReveal(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(30L + index * 50L)
        progress.animateTo(targetValue = 1f, animationSpec = openSpring())
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = progress.value
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * 18.dp.toPx()
                val s = 0.95f + 0.05f * p
                scaleX = s
                scaleY = s
            }
    ) { content() }
}

/**
 * La piega dentro la card: i campi che quasi nessuno tocca.
 *
 * Porte con un valore di serie, formati che ormai si misurano da soli. Stanno
 * qui invece che in mezzo agli altri campi, perche' un modulo lungo fa sembrare
 * difficile una cosa che chiede un indirizzo.
 */
@Composable
fun ExpressiveFold(
    title: String,
    accent: Color,
    stateKey: String,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptics = rememberAppHaptics()
    var open by rememberSaveable(stateKey) { mutableStateOf(initiallyOpen) }

    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "FoldChevron"
    )
    val tint by animateColorAsState(
        targetValue = accent.copy(alpha = if (open) 0.16f else 0.07f),
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "FoldTint"
    )
    val corner by animateDpAsState(
        targetValue = if (open) 22.dp else 20.dp,
        animationSpec = openSpring(),
        label = "FoldCorner"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(corner))
                .background(tint)
                .clickable { haptics.tap(); open = !open }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accent
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                color = accent,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = stringResourceExpand(open),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = chevron },
                tint = accent
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = openSizeSpring()) + fadeIn(tween(180)),
            exit = shrinkVertically(animationSpec = closeSizeSpring()) + fadeOut(tween(100))
        ) {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

/**
 * Una voce salvata: si tocca per usarla, la x la dimentica.
 *
 * Ha la stessa fisica delle righe dei dispositivi trovati in rete — angolo che
 * si stringe, riga che rientra sotto il dito — perche' fa la stessa cosa:
 * la tocchi e ti colleghi. Solo piu' piccola, che qui e' un elenco secondario.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveSavedRow(
    icon: ImageVector,
    shape: RoundedPolygon,
    title: String,
    subtitle: String,
    accent: Color,
    forgetDescription: String,
    onClick: () -> Unit,
    onForget: () -> Unit
) {
    val haptics = rememberAppHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val corner by animateDpAsState(
        targetValue = if (pressed) 12.dp else 22.dp,
        animationSpec = openSpring(),
        label = "SavedCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "SavedScale"
    )
    val badgeMorph = remember(shape) { Morph(shape, shape) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interactionSource = interaction, indication = null) {
                haptics.confirm()
                onClick()
            }
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MorphOutlineShape(badgeMorph, 1f))
                .background(accent.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = accent
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = { haptics.reject(); onForget() }) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = forgetDescription,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
