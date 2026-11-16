package com.godbless.lockin.intro

/**
 * One master clock (seconds since the app opened) drives the whole opening: the contours draw on,
 * the mountain rises, the flag goes up, the climbers walk, the wordmark locks in, and then the same
 * scene glides into its place on the welcome screen. Nothing fades; everything moves.
 */
internal object Timeline {
    const val CONTOURS_START = 0.05f

    const val RISE_START = 0.25f
    const val RISE_DUR = 0.95f

    // The flag climbs out of the summit while the mountain is still rising.
    const val FLAG_START = 0.30f
    const val FLAG_RISE_DUR = 1.0f
    const val FLAG_WIND_START = 0.45f
    const val FLAG_WIND_DUR = 1.1f

    const val ROPE_START = 1.15f
    const val ROPE_DUR = 0.9f

    const val WALK_START = 1.0f
    const val WALK_DUR = 2.5f
    const val WALK_LAG_RIGHT = 0.14f

    const val WORD_START = 2.45f
    const val WORD_STAGGER = 0.055f
    const val WORD_RISE_DUR = 0.55f
    const val CLICK = 3.28f
    const val CLICK_DUR = 0.5f

    /** The centred wordmark drops out of its mask, then a small one rises at the top left. */
    const val EXIT_STAGGER = 0.045f
    const val EXIT_DUR = 0.38f
    const val SMALL_WORD_START = 4.0f

    const val TRANS_START = 3.55f
    const val TRANS_DUR = 0.95f

    const val CURTAIN_START = 3.75f
    const val HEAD_1 = 4.0f
    const val HEAD_2 = 4.1f
    const val PARA = 4.25f
    const val BUTTONS = 4.4f

    /** After this the opening is finished and only idle motion (flag, breathing) continues. */
    const val END = 5.4f
}
