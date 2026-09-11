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
 * LiveProtocol.kt
 *
 * Da quale protocollo sta arrivando l'audio, mentre lo si sta ricevendo.
 *
 * La schermata di ascolto e' la stessa per tutti — stessa forma viva, stesso
 * sfondo che respira — perche' per chi ascolta e' la stessa cosa. L'unica
 * differenza che vale la pena mostrare e' da dove arriva, e si mostra dove si
 * guarda per primo: al centro della forma.
 */

package com.cuscus.wifiaudiostreaming

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.ui.graphics.vector.ImageVector

enum class LiveProtocol {
    WFAS,
    SNAPCAST,
    RTP,
    HTTP,
    DLNA,
    USB;

    /**
     * Il segno da mettere al centro della forma.
     *
     * WFAS ha un logo suo, che e' un'immagine. Tutti gli altri portano qui lo
     * stesso segno con cui compaiono nelle impostazioni: chi li ha accesi li'
     * deve ritrovare la stessa cosa qui, perche' due segni diversi per lo
     * stesso protocollo, per chi guarda, sono due protocolli.
     */
    @DrawableRes
    fun drawable(): Int? = when (this) {
        WFAS -> R.drawable.wfas_protocol
        else -> null
    }

    /** Il segno vettoriale, per i protocolli che non hanno un'immagine. */
    fun icon(): ImageVector? = when (this) {
        WFAS -> null
        SNAPCAST -> Icons.Outlined.Speaker
        RTP -> Icons.Outlined.Radio
        HTTP -> Icons.Outlined.Language
        DLNA -> Icons.Outlined.Cast
        USB -> Icons.Outlined.Usb
    }
}
