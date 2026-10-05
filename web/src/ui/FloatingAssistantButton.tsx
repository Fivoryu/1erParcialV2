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
  clampToViewport,
  defaultPosition,
  dragPosition,
  isDragGesture,
  readStoredPosition,
  writeStoredPosition,
  type FloatingPosition,
} from './floatingPosition';

export type FloatingAssistantButtonProps = {
  open: boolean;
  onOpen: () => void;
};

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
  const [position, setPosition] = useState<FloatingPosition | null>(null);
  // Mirrors `position` so imperative code (pointer up, resize) can read the
  // current value without side effects inside setState updaters, which must
  // stay pure (StrictMode invokes them more than once).
  const positionRef = useRef<FloatingPosition | null>(null);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const suppressClickRef = useRef(false);
  const dragState = useRef<{
    pointerId: number;
    startPointer: FloatingPosition;
    startPosition: FloatingPosition;
    dragged: boolean;
  } | null>(null);

  // Adopt the stored position (or the default one) on mount, clamped to the
  // viewport that is actually in front of the user.
  useEffect(() => {
    const stored = readStoredPosition(window.localStorage);
    const base = stored ?? defaultPosition({
      width: window.innerWidth,
      height: window.innerHeight,
    });
    const clamped = clampToViewport(base, {
      width: window.innerWidth,
      height: window.innerHeight,
    });
    positionRef.current = clamped;
    setPosition(clamped);
  }, []);

  // Re-clamp on resize so the button can never end up off-screen.
  useEffect(() => {
    const handleResize = () => {
      const current = positionRef.current;
      if (current === null) {
        return;
      }
      const clamped = clampToViewport(current, {
        width: window.innerWidth,
        height: window.innerHeight,
      });
      positionRef.current = clamped;
      setPosition(clamped);
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
    drag.dragged = isDragGesture(drag.startPointer, { x: event.clientX, y: event.clientY }, DRAG_CLICK_THRESHOLD);
    const next = clampToViewport(
      dragPosition(drag.startPosition, drag.startPointer, { x: event.clientX, y: event.clientY }),
      { width: window.innerWidth, height: window.innerHeight },
    );
    positionRef.current = next;
    setPosition(next);
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
      // Write from the mirrored ref, not from inside a setPosition updater:
      // updater functions must be pure and can be invoked more than once
      // (StrictMode does), which would double-write to storage.
      if (positionRef.current !== null) {
        writeStoredPosition(window.localStorage, positionRef.current);
      }
    }
  }, []);

  const handleClick = useCallback(() => {
    if (suppressClickRef.current) {
      return;
    }
    onOpen();
  }, [onOpen]);

  return (
    <button
      ref={buttonRef}
      type="button"
      className="floating-assistant"
      aria-label="Asistente de modelo (IA)"
      aria-haspopup="dialog"
      aria-expanded={open}
      style={position === null ? undefined : { left: `${position.x}px`, top: `${position.y}px` }}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onClick={handleClick}
    >
      IA
    </button>
  );
}
