import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactElement,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import {
  DRAG_CLICK_THRESHOLD,
  defaultPosition,
  dragPosition,
  readStoredPosition,
  resolveRenderedPosition,
  shouldReposition,
  withViewport,
  writeStoredPosition,
  type FloatingPosition,
  type FloatingState,
} from './floatingPosition';

export type FloatingAssistantButtonProps = {
  open: boolean;
  onOpen: () => void;
};

/**
 * Resolves the DESIRED position (the stored one, or the default anchor) and the
 * initial viewport during the FIRST render instead of in an effect. Resolving
 * it in an effect painted the CSS default for one frame and then jumped to the
 * remembered spot, so the button visibly moved on every load. Reading it during
 * render removes the jump; `window` is guarded so server rendering still
 * produces a state with nulls, hence a button with no inline position. The
 * desired position is stored UNCLAMPED: clamping is derived at render time via
 * `resolveRenderedPosition`, so a degenerate viewport can never poison it.
 */
function resolveInitialState(): FloatingState {
  if (typeof window === 'undefined') {
    return { desired: null, viewport: null };
  }
  const viewport = { width: window.innerWidth, height: window.innerHeight };
  const stored = readStoredPosition(window.localStorage);
  return { desired: stored ?? defaultPosition(viewport), viewport };
}

/**
 * Circular, draggable floating button that opens the AI assistant. Position is
 * remembered across visits via `floatingPosition.ts`; the inline `left`/`top`
 * style is only applied once a position is known, so server rendering stays
 * deterministic and the CSS default (bottom-right) holds until hydration.
 */
export default function FloatingAssistantButton({
  open,
  onOpen,
}: FloatingAssistantButtonProps): ReactElement {
  // The DESIRED position and the VIEWPORT are independent state; the rendered
  // position is DERIVED from both via resolveRenderedPosition. The resize
  // handler writes only `viewport` (via withViewport) and never the clamped
  // result back into `desired` — writing it back was what ratcheted the button
  // to the top-left after a transient degenerate viewport.
  const [state, setState] = useState<FloatingState>(resolveInitialState);
  // Mirrors `state` so imperative code (pointer up, resize) can read the
  // current value without side effects inside setState updaters, which must
  // stay pure (StrictMode invokes them more than once).
  const stateRef = useRef<FloatingState>(state);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const suppressClickRef = useRef(false);
  const dragState = useRef<{
    pointerId: number;
    startPointer: FloatingPosition;
    startPosition: FloatingPosition;
    dragged: boolean;
  } | null>(null);

  // Re-clamp on resize so the button can never end up off-screen. This writes
  // ONLY the viewport: `desired` survives every resize untouched, so a tiny or
  // transient viewport can never rewrite the remembered corner.
  useEffect(() => {
    const handleResize = () => {
      const next = withViewport(stateRef.current, {
        width: window.innerWidth,
        height: window.innerHeight,
      });
      stateRef.current = next;
      setState(next);
    };
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('resize', handleResize);
    };
  }, []);

  const handlePointerDown = useCallback((event: ReactPointerEvent<HTMLButtonElement>) => {
    const button = buttonRef.current;
    if (button === null || event.button !== 0) {
      return;
    }
    // No event.preventDefault() here on purpose: it suppresses the
    // compatibility mousedown, so a mouse press never focuses the button and
    // the Modal focus-restoration contract (previouslyFocused) would restore
    // focus to <body> instead of this button. `touch-action: none` and
    // `user-select: none` in the CSS already prevent page scroll and text
    // selection during the gesture.
    button.setPointerCapture(event.pointerId);
    const rect = button.getBoundingClientRect();
    dragState.current = {
      pointerId: event.pointerId,
      startPointer: { x: event.clientX, y: event.clientY },
      // The drag origin is the button's own rendered position read from the
      // DOM (it is position:fixed, so rect is in the same coordinate space as
      // the inline left/top), never the pointer coordinates: otherwise the
      // button teleports to the cursor on the first move.
      startPosition: { x: rect.left, y: rect.top },
      dragged: false,
    };
  }, []);

  const handlePointerMove = useCallback((event: ReactPointerEvent<HTMLButtonElement>) => {
    const drag = dragState.current;
    if (drag === null || drag.pointerId !== event.pointerId) {
      return;
    }
    // Below the click threshold nothing moves at all — no reposition and no
    // ref update — so a jittery click cannot nudge the button by a pixel or
    // two and leave it shifted.
    if (!shouldReposition(drag.dragged, drag.startPointer, { x: event.clientX, y: event.clientY }, DRAG_CLICK_THRESHOLD)) {
      return;
    }
    drag.dragged = true;
    const next = dragPosition(drag.startPosition, drag.startPointer, { x: event.clientX, y: event.clientY });
    // The drag writes the DESIRED position (unclamped); the rendered spot is
    // derived from desired + viewport, so the clamp stays out of the state.
    const nextState: FloatingState = {
      desired: next,
      viewport: { width: window.innerWidth, height: window.innerHeight },
    };
    stateRef.current = nextState;
    setState(nextState);
  }, []);

  const handlePointerUp = useCallback((event: ReactPointerEvent<HTMLButtonElement>) => {
    const drag = dragState.current;
    if (drag === null || drag.pointerId !== event.pointerId) {
      return;
    }
    dragState.current = null;
    const button = buttonRef.current;
    if (button !== null && button.hasPointerCapture(event.pointerId)) {
      button.releasePointerCapture(event.pointerId);
    }
    if (drag.dragged) {
      // The gesture was a drag: persist the final position and suppress the
      // click that the browser fires right after pointerup.
      suppressClickRef.current = true;
      window.setTimeout(() => {
        suppressClickRef.current = false;
      }, 0);
      // Write from the mirrored ref, not from inside a setState updater:
      // updater functions must be pure and can be invoked more than once
      // (StrictMode does), which would double-write to storage. The DESIRED
      // position is what gets persisted, not a clamped derivative.
      const desired = stateRef.current.desired;
      if (desired !== null) {
        writeStoredPosition(window.localStorage, desired);
      }
    }
  }, []);

  const handleClick = useCallback(() => {
    if (suppressClickRef.current) {
      return;
    }
    onOpen();
  }, [onOpen]);

  const renderedPosition = resolveRenderedPosition(state);

  return (
    <button
      ref={buttonRef}
      type="button"
      className="floating-assistant"
      aria-label="Asistente de modelo (IA)"
      aria-haspopup="dialog"
      aria-expanded={open}
      style={renderedPosition === null ? undefined : { left: `${renderedPosition.x}px`, top: `${renderedPosition.y}px` }}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onClick={handleClick}
    >
      IA
    </button>
  );
}
