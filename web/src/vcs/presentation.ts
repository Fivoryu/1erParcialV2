export function relativeTimeLabel(timestamp: string, now = Date.now()): string {
  const createdAt = Date.parse(timestamp);
  if (Number.isNaN(createdAt)) {
    return 'Time unavailable';
  }

  const elapsedSeconds = (now - createdAt) / 1000;
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 60 * 60 * 24 * 365],
    ['month', 60 * 60 * 24 * 30],
    ['week', 60 * 60 * 24 * 7],
    ['day', 60 * 60 * 24],
    ['hour', 60 * 60],
    ['minute', 60],
    ['second', 1],
  ];
  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
  const [unit, secondsPerUnit] = units.find(([, seconds]) => Math.abs(elapsedSeconds) >= seconds) ?? units.at(-1)!;
  return formatter.format(-Math.round(elapsedSeconds / secondsPerUnit), unit);
}
