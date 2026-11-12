package com.forever.dorolog

import android.content.Context
import ai.pleos.playground.vehicle.api.Safety

@Suppress("UNUSED_PARAMETER")
internal fun subscribePocSeatWarning(
    context: Context,
    safety: Safety,
    deliver: (() -> Unit) -> Unit,
    onWarning: (Boolean?) -> Unit,
): () -> Unit = {}
