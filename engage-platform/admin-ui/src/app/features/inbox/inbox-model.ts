/** One conversation in the list (GET /api/inbox). */
export interface InboxThreadRow {
  identityId: string;
  name: string | null;
  phone: string | null;
  lastMessageAt: string;
  lastInboundAt: string | null;
  lastPreview: string | null;
  unread: number;
  status: 'open' | 'done';
  windowUntil: string | null;
}

export interface InboxMessage {
  id: number;
  direction: 'in' | 'out';
  kind: string;
  body: string | null;
  hasMedia: boolean;
  mediaMime: string | null;
  mediaName: string | null;
  templateKey: string | null;
  /** One of Engage's journey or campaign messages, not typed by staff. */
  automated: boolean;
  sentBy: string | null;
  status: 'sent' | 'delivered' | 'read' | 'failed' | null;
  failedReason: string | null;
  at: string;
}

/** One conversation (GET /api/inbox/{identityId}). */
export interface InboxThread extends InboxThreadRow {
  email: string | null;
  orders: { orderNumber: string; totalPaise: number; createdAt: string; cancelled: boolean }[];
  messages: InboxMessage[];
  windowOpen: boolean;
  followUps: { key: string }[];
}

/** "5h 12m", "48m", "under a minute". */
export function timeLeft(ms: number): string {
  if (ms < 60_000) return 'under a minute';
  const minutes = Math.floor(ms / 60_000);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

/** The free 24 h reply window: open until the customer's last message + 24 h. */
export function windowState(until: string | null, now: number): { open: boolean; left: string } {
  if (!until) return { open: false, left: '' };
  const ms = new Date(until).getTime() - now;
  return ms > 0 ? { open: true, left: timeLeft(ms) } : { open: false, left: '' };
}
