package com.volleyballlab.player

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Sideload previews keep the same registered development attestation as debug. */
internal fun installAppCheckProvider() {
    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance(),
    )
}
