export type HaltScope = 'marketing' | 'channel' | 'journey';

export interface Halt {
  scope: HaltScope;
  selector: string;
  since: string;
  byEmail: string | null;
  reason: string;
}

export const HALT_CHANNELS = ['whatsapp', 'push', 'email', 'sms'] as const;

/**
 * The word an operator types to confirm a halt: the channel or journey name
 * when one is picked, else the scope itself ("marketing", "channel").
 * Typing it is the point: a halt stops revenue, so it is never one click.
 */
export function haltConfirmWord(scope: HaltScope, selector: string): string {
  return scope === 'marketing' || !selector || selector === '*' ? scope : selector;
}

/** What a halt stops, in words for the banner. */
export function haltLabel(h: Pick<Halt, 'scope' | 'selector'>): string {
  if (h.scope === 'marketing') return 'All marketing';
  const what = h.selector === '*' ? (h.scope === 'channel' ? 'every channel' : 'every journey') : h.selector;
  return h.scope === 'channel' ? `Channel: ${what}` : `Journey: ${what}`;
}
