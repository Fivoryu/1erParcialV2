import {
  useLayoutEffect,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type ReactElement,
} from 'react';
import { isDismissKey } from '../ui/modalBehavior';
import {
  CONTEXT_MENU_FALLBACK_SIZE,
  clampMenuPosition,
  isSeparator,
  type ContextMenuEntry,
} from './contextMenuBehavior';

export type ContextMenuProps = {
  x: number;
  y: number;
  items: ContextMenuEntry[];
  onClose: () => void;
};

/**
 * Canvas context menu (right-click), rendered inline as a fixed overlay so it
 * works under server rendering, like Modal. Anchors at the cursor with
 * clamp-to-viewport (same math pattern as ui/floatingPosition), and closes on
 * Escape, on any left click outside, and on scroll or resize — the anchor is a
 * transient cursor position, so any viewport movement invalidates it.
 *
 * Every DOM effect is browser-only, guarded by being mounted, and cleaned up
 * on close/unmount; the parent simply stops rendering the menu when closed.
 */
export default function ContextMenu({ x, y, items, onClose }: ContextMenuProps): ReactElement | null {
  const menuRef = useRef<HTMLDivElement | null>(null);
  const [openSubmenuId, setOpenSubmenuId] = useState<string | null>(null);
  // First paint clamps with the fallback size; the layout effect then swaps in
  // the measured box and the clamp is recomputed for this render.
  const [menuSize, setMenuSize] = useState(CONTEXT_MENU_FALLBACK_SIZE);

  useLayoutEffect(() => {
    const node = menuRef.current;
    if (node) {
      setMenuSize({ width: node.offsetWidth, height: node.offsetHeight });
    }
    setOpenSubmenuId(null);
  }, [items]);

  // Dismissal. Re-registering the listeners per render is cheap (same trade-off
  // as Modal) and keeps them out of the focus lifecycle.
  useLayoutEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (isDismissKey(event.key, true)) {
        event.preventDefault();
        onClose();
      }
    };
    // Left click anywhere outside the menu dismisses it; button 0 only, so
    // right-clicking elsewhere can move the menu instead of closing it.
    const handleMouseDown = (event: MouseEvent) => {
      if (event.button === 0 && menuRef.current && !menuRef.current.contains(event.target as Node)) {
        onClose();
      }
    };
    // Nested scrollers (the diagram pane among them) must also dismiss, hence capture.
    const handleScroll = () => onClose();
    const handleResize = () => onClose();

    document.addEventListener('keydown', handleKeyDown);
    document.addEventListener('mousedown', handleMouseDown);
    document.addEventListener('scroll', handleScroll, true);
    window.addEventListener('resize', handleResize);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('mousedown', handleMouseDown);
      document.removeEventListener('scroll', handleScroll, true);
      window.removeEventListener('resize', handleResize);
    };
  }, [onClose]);

  if (items.length === 0) {
    return null;
  }

  // Only rendered client-side after a right click, but keep SSR markup sane
  // anyway: with no window there is nothing to clamp against.
  const viewport = typeof window === 'undefined'
    ? { width: 1920, height: 1080 }
    : { width: window.innerWidth, height: window.innerHeight };
  const clamped = clampMenuPosition({ x, y }, menuSize, viewport);

  function handleItemSelect(item: ContextMenuEntry, event: ReactMouseEvent<HTMLButtonElement>): void {
    event.stopPropagation();
    if (isSeparator(item) || item.disabled) {
      return;
    }
    if (item.submenu) {
      // Parent entry of a submenu: hover opens it, click just toggles for keyboard/touch users.
      setOpenSubmenuId((current) => (current === item.id ? null : item.id));
      return;
    }
    item.onSelect?.();
    onClose();
  }

  function renderEntry(entry: ContextMenuEntry, nested: boolean): ReactElement {
    if (isSeparator(entry)) {
      return <li key={`separator-${nested ? 'sub' : 'main'}`} role="separator" className="context-menu-separator" />;
    }
    const hasSubmenu = entry.submenu !== undefined;
    const submenuOpen = hasSubmenu && openSubmenuId === entry.id;
    return (
      <li key={entry.id} className="context-menu-entry">
        <button
          type="button"
          role="menuitem"
          className="context-menu-item"
          disabled={entry.disabled}
          aria-disabled={entry.disabled ? true : undefined}
          aria-haspopup={hasSubmenu ? true : undefined}
          title={entry.disabled ? entry.disabledReason : undefined}
          onClick={(event) => handleItemSelect(entry, event)}
          onMouseEnter={hasSubmenu && !entry.disabled ? () => setOpenSubmenuId(entry.id) : undefined}
        >
          <span className="context-menu-item-label">{entry.label}</span>
          {hasSubmenu ? <span className="context-menu-submenu-arrow" aria-hidden="true">▸</span> : null}
        </button>
        {hasSubmenu ? (
          <div
            className="context-menu-submenu"
            role="menu"
            hidden={!submenuOpen}
          >
            <ul className="context-menu-list">
              {entry.submenu!.map((child) => renderEntry(child, true))}
            </ul>
          </div>
        ) : null}
      </li>
    );
  }

  return (
    <div
      ref={menuRef}
      className="context-menu"
      role="menu"
      style={{ left: clamped.x, top: clamped.y }}
    >
      <ul className="context-menu-list">
        {items.map((entry) => renderEntry(entry, false))}
      </ul>
    </div>
  );
}
