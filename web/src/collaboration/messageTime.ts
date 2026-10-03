export function formatMessageTime(isoString: string, locale: string, timeZone: string): string {
  if (typeof isoString !== 'string' || isoString.trim() === '') {
    return 'Time unavailable';
  }

  const date = new Date(isoString);
  if (!Number.isFinite(date.getTime())) {
    return 'Time unavailable';
  }

  return new Intl.DateTimeFormat(locale, {
    hour: 'numeric',
    minute: '2-digit',
    timeZone,
  }).format(date);
}
