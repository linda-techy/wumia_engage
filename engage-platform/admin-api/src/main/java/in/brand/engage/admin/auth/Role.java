package in.brand.engage.admin.auth;

/**
 * Operator roles, fixed in code (V2 operator_roles CHECK). Additive: an
 * operator holds a set. Every role includes reading (VIEWER); OWNER holds all.
 * Nothing else is implied: a campaign role is not CONFIG_ADMIN or ANALYST.
 */
public enum Role {
    /** Read dashboards, campaigns, sends. */
    VIEWER,
    /** + reveal contact details, export, incrementality. */
    ANALYST,
    /** + create and edit campaigns, templates, segments. Cannot send. */
    CAMPAIGN_EDIT,
    /** + approve and execute sends, halt. */
    CAMPAIGN_SEND,
    /** + change policy config, register consent copy, halt. */
    CONFIG_ADMIN,
    /** + manage operators, API keys, destructive operations. */
    OWNER
}
