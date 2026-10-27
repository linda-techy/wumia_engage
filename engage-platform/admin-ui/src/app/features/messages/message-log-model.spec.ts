import { journeyLabel, messageText, outcomeText, outcomeTone } from './message-log-model';

describe('message log wording', () => {
  it('names journeys and campaigns', () => {
    expect(journeyLabel({ intentKey: 'cart_recovery', campaign: null })).toBe('Cart recovery');
    expect(journeyLabel({ intentKey: 'campaign:3f2a9b7c-1111', campaign: 'Diwali Sale' })).toBe('Campaign: Diwali Sale');
    expect(journeyLabel({ intentKey: null, campaign: null })).toBe('Direct');
    expect(journeyLabel({ intentKey: 'something_new', campaign: null })).toBe('something new');
  });

  it('shows what WhatsApp sent, otherwise the template wording', () => {
    expect(messageText({ text: 'Hi Ravi, your bag is waiting.', templateTitle: 'x', templateBody: 'y' })).toBe('Hi Ravi, your bag is waiting.');
    expect(messageText({ text: null, templateTitle: 'Your bag is waiting', templateBody: 'Complete your order.' }))
      .toBe('Your bag is waiting — Complete your order.');
    expect(messageText({ text: null, templateTitle: null, templateBody: 'Only a body' })).toBe('Only a body');
  });

  it('explains outcomes in plain words', () => {
    expect(outcomeText({ status: 'clicked', failedReason: null, blockReason: null })).toBe('Tapped');
    expect(outcomeText({ status: 'deferred', failedReason: null, blockReason: 'QUIET_HOURS' })).toBe('Held: quiet hours (sent later)');
    expect(outcomeText({ status: 'blocked', failedReason: null, blockReason: 'NO_WHATSAPP_OPT_IN' })).toBe('Not sent: no WhatsApp opt-in');
    expect(outcomeText({ status: 'failed', failedReason: 'wa_131030', blockReason: null })).toBe('Failed: not a test recipient (Meta test number)');
    expect(outcomeTone('read')).toBe('good');
    expect(outcomeTone('blocked')).toBe('bad');
  });
});
