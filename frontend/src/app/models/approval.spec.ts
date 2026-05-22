import { describe, it, expect } from 'vitest';
import { stateBadgeClass, stateLabel, ApprovalState } from './approval';

describe('approval helpers', () => {
  it('returns a label for every state', () => {
    const states: ApprovalState[] = [
      'AWAITING_CONFIRMATION', 'SUBMITTED', 'AWAITING_GROUP1_APPROVAL',
      'AWAITING_GROUP2_APPROVAL', 'APPROVED', 'REJECTED'
    ];
    for (const s of states) {
      expect(stateLabel(s)).toBeTruthy();
      expect(stateBadgeClass(s)).toMatch(/^badge /);
    }
  });

  it('maps approved/rejected to their distinct badge variants', () => {
    expect(stateBadgeClass('APPROVED')).toBe('badge badge-approved');
    expect(stateBadgeClass('REJECTED')).toBe('badge badge-rejected');
    expect(stateBadgeClass('AWAITING_CONFIRMATION')).toBe('badge badge-confirm');
  });
});
