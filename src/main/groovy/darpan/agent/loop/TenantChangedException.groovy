package darpan.agent.loop

/** The active tenant is no longer the one the turn was pinned to (another tab switched it). No tool runs after this. */
class TenantChangedException extends RuntimeException {
    TenantChangedException() { super("TENANT_CHANGED: the active tenant changed during the turn") }
}
