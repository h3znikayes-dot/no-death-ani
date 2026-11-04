package com.example.deadwalking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeadWalking {

    public static final String MOD_ID = "deadwalking";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** How long a body stays, in ticks (20 ticks = 1 second). */
    public static final int CORPSE_TICKS = 40;

    private DeadWalking() {}
}
