package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.SafetyFloor

/**
 * Core system surfaces we never treat as a blockable/countable foreground app: the shield
 * allowlist in [BlockDecisionEngine] and the "doesn't count as an open" heuristic in
 * [OpenLimitPolicy] share this so the two decisions can't drift apart.
 *
 * This is the static half of the enforcement [SafetyFloor] (system UI, Settings and permission
 * recovery, telephony and emergency surfaces). The detector layer additionally exempts the
 * device's resolved default/system dialer and home apps at runtime — deliberately not part of
 * this set, because launcher→app transitions are exactly the opens that should count.
 */
internal val SYSTEM_SURFACE_PACKAGES: Set<String> = SafetyFloor.STATIC_PACKAGES
