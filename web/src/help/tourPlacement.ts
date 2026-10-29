type VerticalTarget = Readonly<{ top: number; height: number }>;

/** Reserve the floating help button below the card and keep the guided target reachable. */
export function placeTourAboveTarget(target: VerticalTarget | null, viewportHeight: number): boolean {
  if (!target || viewportHeight <= 0) {
    return false;
  }

  // Match the compact layout's 12px top and 96px bottom insets.
  const availableCenter = (viewportHeight + 12 - 96) / 2;
  return target.top + target.height / 2 > availableCenter;
}

/** Move upward only enough to reveal a target hidden under the sticky editor header. */
export function scrollOffsetBelowHeader(targetTop: number, headerBottom: number): number {
  return Math.min(0, targetTop - headerBottom - 12);
}
