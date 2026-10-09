// GET /api/campaigns/{id} and the composer's button rules (P6-T07). The
// server enforces all of these; the console disables what would be refused,
// and says why.

export type CampaignStatus =
  | 'DRAFT'
  | 'ESTIMATING'
  | 'READY'
  | 'PENDING_APPROVAL'
  | 'SCHEDULED'
  | 'RUNNING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED';

export interface Estimate {
  audience: number;
  willReceive: number;
  costPaise: number;
  holdout: number;
  excluded: Record<string, number>;
  blocked: Record<string, number>;
  deferred: Record<string, number>;
  estimatedAt: string;
}

export interface Campaign {
  id: string;
  name: string;
  channel: 'push' | 'whatsapp';
  templateKey: string;
  segmentId: string | null;
  segmentName: string | null;
  vars: Record<string, string>;
  status: CampaignStatus;
  pausedReason: string | null;
  scheduledAt: string | null;
  sendRatePerMinute: number;
  holdoutPct: number;
  budgetCapPaise: number | null;
  ttlSeconds: number | null;
  followUpChannel: string | null;
  followUpTemplateKey: string | null;
  followUpAfterMinutes: number | null;
  estimate: Estimate | null;
  estimatedAt: string | null;
  estimatedCostPaise: number | null;
  createdBy: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  recipients: Record<string, number>;
  approvalRequired: boolean;
  createdAt: string;
}

export interface Me {
  email: string;
  canEdit: boolean; // CAMPAIGN_EDIT
  canSend: boolean; // CAMPAIGN_SEND
}

/** The console asks for a fresher dry run than the server's 24 h: what is armed should match what was reviewed. */
export const ARM_WITHIN_MS = 30 * 60 * 1000;

const EDITABLE: CampaignStatus[] = ['DRAFT', 'READY', 'PENDING_APPROVAL'];

export type Verdict = { allowed: true } | { allowed: false; why: string };

const yes: Verdict = { allowed: true };
const no = (why: string): Verdict => ({ allowed: false, why });

export function canEdit(c: Campaign, me: Me): Verdict {
  if (!me.canEdit) return no('Needs campaign editing rights');
  return EDITABLE.includes(c.status) ? yes : no(`A ${c.status.toLowerCase()} campaign cannot be changed`);
}

export function canEstimate(c: Campaign, me: Me): Verdict {
  return canEdit(c, me);
}

export function canApprove(c: Campaign, me: Me): Verdict {
  if (c.status !== 'PENDING_APPROVAL') return no('Nothing to approve');
  if (!me.canSend) return no('Needs campaign sending rights');
  if (c.createdBy && c.createdBy === me.email) return no('Another operator must approve your campaign');
  return yes;
}

export function canArm(c: Campaign, me: Me, now: number): Verdict {
  if (!me.canSend) return no('Needs campaign sending rights');
  if (c.status !== 'READY') return no(c.status === 'PENDING_APPROVAL' ? 'Waiting for approval' : 'Run the dry run first');
  if (!c.estimatedAt) return no('Run the dry run first');
  if (now - new Date(c.estimatedAt).getTime() > ARM_WITHIN_MS) return no('The dry run is over 30 minutes old: run it again');
  if (c.approvalRequired && !c.approvedBy) return no('Waiting for approval');
  return yes;
}

export function canPause(c: Campaign, me: Me): Verdict {
  if (!me.canSend) return no('Needs campaign sending rights');
  return c.status === 'RUNNING' || c.status === 'SCHEDULED' ? yes : no('Not running');
}

export function canResume(c: Campaign, me: Me): Verdict {
  if (!me.canSend) return no('Needs campaign sending rights');
  if (c.status !== 'PAUSED') return no('Not paused');
  return c.pausedReason === 'budget_cap' ? no('Stopped at its budget cap') : yes;
}

export function canCancel(c: Campaign, me: Me): Verdict {
  if (!me.canSend) return no('Needs campaign sending rights');
  return ['COMPLETED', 'CANCELLED', 'FAILED'].includes(c.status) ? no('Already finished') : yes;
}

/** Recipients handled so far, for the live progress bar. */
export function progress(c: Pick<Campaign, 'recipients'>): { done: number; total: number; pct: number } {
  const r = c.recipients ?? {};
  const total = Object.values(r).reduce((a, b) => a + Number(b), 0) - Number(r['skipped'] ?? 0);
  const done = total - Number(r['pending'] ?? 0) - Number(r['claimed'] ?? 0);
  return { done: Math.max(0, done), total: Math.max(0, total), pct: total > 0 ? Math.round((Math.max(0, done) / total) * 100) : 0 };
}
