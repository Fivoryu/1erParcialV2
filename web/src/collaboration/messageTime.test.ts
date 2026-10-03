import { describe, expect, it } from 'vitest';
import { formatMessageTime } from './messageTime';

describe('formatMessageTime', () => {
  it('formats an ISO timestamp in the explicitly requested locale and time zone', () => {
    expect(formatMessageTime('2026-01-15T18:05:00.000Z', 'en-US', 'America/Los_Angeles'))
      .toBe('10:05 AM');
  });

  it('renders an unparseable timestamp as a sane fallback instead of throwing', () => {
    expect(() => formatMessageTime('not-a-time', 'en-US', 'America/Los_Angeles')).not.toThrow();
    expect(formatMessageTime('not-a-time', 'en-US', 'America/Los_Angeles')).toBe('Time unavailable');
  });

  it('renders a null timestamp as unavailable instead of the Unix epoch', () => {
    expect(formatMessageTime(null as unknown as string, 'en-US', 'America/Los_Angeles'))
      .toBe('Time unavailable');
  });
});
