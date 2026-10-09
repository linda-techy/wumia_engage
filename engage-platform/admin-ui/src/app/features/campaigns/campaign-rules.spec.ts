import { ARM_WITHIN_MS, Campaign, canApprove, canArm, canEdit, canPause, canResume, Me, progress } from './campaign-rules';

const NOW = Date.parse('2026-10-09T12:00:00Z');

function campaign(over: Partial<Campaign>): Campaign {
  return {
    id: 'c1', name: 'Onam', channel: 'whatsapp', templateKey: 't', segmentId: 's', segmentName: 'Kerala', vars: {},
    status: 'DRAFT', pausedReason: null, scheduledAt: null, sendRatePerMinute: 600, holdoutPct: 0, budgetCapPaise: 50000,
    ttlSeconds: null, followUpChannel: null, followUpTemplateKey: null, followUpAfterMinutes: null, estimate: null,
    estimatedAt: null, estimatedCostPaise: null, createdBy: 'author@example.com', approvedBy: null, approvedAt: null,
    startedAt: null, finishedAt: null, recipients: {}, approvalRequired: true, createdAt: '2026-10-09T10:00:00Z',
    ...over,
  };
}

const author: Me = { email: 'author@example.com', canEdit: true, canSend: true };
const second: Me = { email: 'second@example.com', canEdit: false, canSend: true };
const editor: Me = { email: 'editor@example.com', canEdit: true, canSend: false };

describe('composer button rules', () => {
  it('disables Approve for the author, and for anyone without sending rights', () => {
    const c = campaign({ status: 'PENDING_APPROVAL' });
    expect(canApprove(c, author)).toEqual({ allowed: false, why: 'Another operator must approve your campaign' });
    expect(canApprove(c, editor).allowed).toBe(false);
    expect(canApprove(c, second)).toEqual({ allowed: true });
    expect(canApprove(campaign({ status: 'READY' }), second).allowed).toBe(false);
  });

  it('disables Arm until a dry run from the last 30 minutes exists, and approval where needed', () => {
    const fresh = new Date(NOW - 5 * 60 * 1000).toISOString();
    const stale = new Date(NOW - ARM_WITHIN_MS - 1000).toISOString();
    expect(canArm(campaign({ status: 'DRAFT' }), author, NOW)).toEqual({ allowed: false, why: 'Run the dry run first' });
    expect(canArm(campaign({ status: 'READY', estimatedAt: stale, approvedBy: 'second@example.com' }), author, NOW))
      .toEqual({ allowed: false, why: 'The dry run is over 30 minutes old: run it again' });
    expect(canArm(campaign({ status: 'READY', estimatedAt: fresh, approvedBy: null }), author, NOW))
      .toEqual({ allowed: false, why: 'Waiting for approval' });
    expect(canArm(campaign({ status: 'READY', estimatedAt: fresh, approvedBy: 'second@example.com' }), author, NOW)).toEqual({ allowed: true });
    expect(canArm(campaign({ status: 'READY', estimatedAt: fresh, approvalRequired: false }), editor, NOW).allowed).toBe(false);
  });

  it('allows edits only before arming, and resume never past the budget cap', () => {
    expect(canEdit(campaign({ status: 'READY' }), editor).allowed).toBe(true);
    expect(canEdit(campaign({ status: 'RUNNING' }), editor).allowed).toBe(false);
    expect(canPause(campaign({ status: 'RUNNING' }), second).allowed).toBe(true);
    expect(canResume(campaign({ status: 'PAUSED', pausedReason: 'budget_cap' }), second))
      .toEqual({ allowed: false, why: 'Stopped at its budget cap' });
    expect(canResume(campaign({ status: 'PAUSED', pausedReason: 'operator' }), second).allowed).toBe(true);
  });

  it('measures progress over treatment recipients, holdout excluded', () => {
    expect(progress({ recipients: { pending: 30, sent: 60, blocked: 10, skipped: 20 } })).toEqual({ done: 70, total: 100, pct: 70 });
    expect(progress({ recipients: {} })).toEqual({ done: 0, total: 0, pct: 0 });
  });
});
