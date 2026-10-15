// Shapes returned by admin-api (Console v0). Hand-written for v0; Phase 6
// generates them from Micronaut's OpenAPI output.

export type Role = 'VIEWER' | 'ANALYST' | 'CAMPAIGN_EDIT' | 'CAMPAIGN_SEND' | 'CONFIG_ADMIN' | 'OWNER';

export interface Operator {
  id: string;
  email: string;
  fullName: string;
  roles: Role[];
  mfaEnrolled?: boolean;
}

export interface SignedIn {
  accessToken: string;
  operator: Operator;
}

export interface MfaChallenge {
  mfaRequired: true;
  mfaToken: string;
}

/** RFC 9457 problem+json, plus the extension members admin-api adds. */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
  enrolToken?: string;
}

export interface Enrolment {
  provisioningUri: string;
  secret: string;
}

export interface TopicHealth {
  source: string;
  topic: string;
  total: number;
  pending: number;
  failed: number;
  dead: number;
  oldestPendingAgeSeconds: number;
  lastReceivedAt: string | null;
}

export interface StuckItem {
  source: string;
  topic: string;
  deliveryId: string;
  attempts: number;
  lastError: string;
  receivedAt: string;
  nextAttemptAt: string;
}

export interface IngestHealth {
  topics: TopicHealth[];
  stuck: StuckItem[];
}

export interface IdentityKey {
  kind: string;
  value: string;
  verified: boolean;
  firstSeen?: string;
  lastSeen?: string;
}

export type Row = Record<string, string | number | boolean | null>;

export interface CustomerView {
  identityId: string;
  profile: { createdAt?: string; attrs?: string | null; computed?: string | null };
  keys: IdentityKey[];
  consent: Row[];
  orders: Row[];
  checkouts: Row[];
  payments: Row[];
  shipments: Row[];
  devices: Row[];
  sends: Row[];
}

export interface PaymentReportRow {
  istDay: string;
  matchMethod: string;
  failures: number;
  withoutMobile: number;
  avgWebhookLagSeconds: number | null;
}

export interface PaymentAttempt {
  gatewayPaymentId: string;
  status: string;
  amountPaise: number;
  method: string | null;
  errorSource: string | null;
  errorReason: string | null;
  matchMethod: string | null;
  joinedToCheckout: boolean;
  receivedAt: string;
}

export interface PaymentFailures {
  report: PaymentReportRow[];
  recent: PaymentAttempt[];
}

export interface CopyVersion {
  version: string;
  channel: string;
  surface: string;
  text: string;
  purposes: string[];
  grants: number;
  createdAt: string;
}

/** GET /api/support-requests (V33). No contact details; the reply text is fetched on its own. */
export interface SupportRequest {
  id: number;
  kind: 'delivery' | 'after_delivery' | string;
  status: 'open' | 'done';
  identityId: string;
  orderId: string | null;
  orderNumber: string | null;
  awb: string | null;
  courier: string | null;
  messages: number;
  hasText: boolean;
  note: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** GET /api/reports/lift, /api/journeys/{key}/incrementality (P7-T03). Money in paise. */
export interface JourneyLift {
  journey: string;
  firstAssigned: string | null;
  enoughData: boolean;
  why: string | null;
  treated: number;
  control: number;
  revenuePerTreatedPaise: number;
  revenuePerControlPaise: number;
  liftPct: number | null;
  liftLowPct: number | null;
  liftHighPct: number | null;
  significant: boolean;
  incrementalPaise: number;
  incrementalLowPaise: number;
  incrementalHighPaise: number;
  messagingCostPaise: number;
  roi: number | null;
  weekly: { weekIst: string; treatedRevenuePaise: number; treatedPeople: number; controlRevenuePaise: number; controlPeople: number }[];
}

/** GET /api/reports/spend-reconciliation?month=YYYY-MM: our WhatsApp ledger against Meta's bill. */
export interface SpendReconciliation {
  month: string;
  billUploaded: boolean;
  rows: { category: string; ledgerPaise: number; metaPaise: number | null; diffPct: number | null; flagged: boolean }[];
}
