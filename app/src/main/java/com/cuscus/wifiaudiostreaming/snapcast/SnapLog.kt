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

package com.cuscus.wifiaudiostreaming.snapcast

import android.util.Log

/**
 * Il registro del client Snapcast.
 *
 * Esiste per una ragione sola: i file di rete sono gli stessi dell'app
 * desktop, dove il registro si chiama AppDebug. Tenere qui l'unico punto di
 * traduzione vuol dire che il resto del file resta identico riga per riga, e
 * che le due versioni si possono ancora confrontare a colpo d'occhio.
 */
internal object SnapLog {
    private const val TAG = "Snapcast"
    fun d(msg: String) = Log.d(TAG, msg)
}
