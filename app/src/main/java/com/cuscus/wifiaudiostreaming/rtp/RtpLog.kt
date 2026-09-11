/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 */

package com.cuscus.wifiaudiostreaming.rtp

import android.util.Log

/**
 * Il registro del ricevitore RTP.
 *
 * Come per Snapcast: i file di rete sono gli stessi dell'app desktop, dove il
 * registro si chiama AppDebug. Tenere qui l'unico punto di traduzione lascia il
 * resto del file identico riga per riga.
 */
internal object RtpLog {
    private const val TAG = "RTP-RX"
    fun d(msg: String) = Log.d(TAG, msg)
}
