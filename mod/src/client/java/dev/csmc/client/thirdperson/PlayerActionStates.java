package dev.csmc.client.thirdperson;

import dev.csmc.network.PlayerActionStatePayload;

/** Maps {@link PlayerActionStatePayload} kinds onto the {@link ActionTimeline}. */
final class PlayerActionStates {
    private PlayerActionStates() {}

    static void receive(PlayerActionStatePayload payload) {
        double now = ActionTimeline.now();
        switch (payload.kind()) {
            case PlayerActionStatePayload.SCOPE -> ActionTimeline.scope(payload.entityId(), payload.value());
            case PlayerActionStatePayload.GRENADE_PRIME ->
                    ActionTimeline.start(payload.entityId(), ActionTimeline.Action.GRENADE_PRIME, now, 0.0, payload.value());
            case PlayerActionStatePayload.GRENADE_THROW ->
                    ActionTimeline.start(payload.entityId(), ActionTimeline.Action.GRENADE_THROW, now, 0.5, payload.value());
            case PlayerActionStatePayload.GRENADE_CANCEL -> {
                // Only cancel a grenade action: a later draw of another item must survive a late cancel.
                ActionTimeline.Entry current = ActionTimeline.current(payload.entityId());
                if (current != null && (current.action() == ActionTimeline.Action.GRENADE_PRIME
                        || current.action() == ActionTimeline.Action.GRENADE_THROW)) {
                    ActionTimeline.start(payload.entityId(), ActionTimeline.Action.NONE, now, 0.0, 0);
                }
            }
            default -> { }
        }
    }
}
