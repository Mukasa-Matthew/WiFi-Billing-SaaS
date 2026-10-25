"use client";

import React, { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { DotsThreeVertical } from "@/components/icons";

interface ActionMenuProps {
  children: React.ReactNode;
  ariaLabel?: string;
}

export function ActionMenu({ children, ariaLabel = "Actions" }: ActionMenuProps) {
  const [open, setOpen] = useState(false);
  const [coords, setCoords] = useState<{ top: number; left: number } | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  const updatePosition = () => {
    if (!triggerRef.current) return;
    const rect = triggerRef.current.getBoundingClientRect();
    const menuWidth = 208; // w-52 = 13rem = 208px
    const estimatedHeight = 240;

    const spaceBelow = window.innerHeight - rect.bottom;
    const top = spaceBelow >= estimatedHeight 
      ? rect.bottom + 6 
      : Math.max(8, rect.top - estimatedHeight - 6);

    const left = Math.max(8, Math.min(rect.right - menuWidth, window.innerWidth - menuWidth - 8));

    setCoords({ top, left });
  };

  const handleToggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!open) {
      updatePosition();
      setOpen(true);
    } else {
      setOpen(false);
    }
  };

  useEffect(() => {
    if (!open) return;

    const handleScrollOrResize = () => {
      setOpen(false);
    };

    const handlePointerDown = (e: MouseEvent | TouchEvent) => {
      const target = e.target as Node;
      if (
        menuRef.current &&
        !menuRef.current.contains(target) &&
        triggerRef.current &&
        !triggerRef.current.contains(target)
      ) {
        setOpen(false);
      }
    };

    window.addEventListener("scroll", handleScrollOrResize, true);
    window.addEventListener("resize", handleScrollOrResize);
    document.addEventListener("mousedown", handlePointerDown);
    document.addEventListener("touchstart", handlePointerDown);

    return () => {
      window.removeEventListener("scroll", handleScrollOrResize, true);
      window.removeEventListener("resize", handleScrollOrResize);
      document.removeEventListener("mousedown", handlePointerDown);
      document.removeEventListener("touchstart", handlePointerDown);
    };
  }, [open]);

  return (
    <div className="relative inline-block text-left">
      <button
        ref={triggerRef}
        type="button"
        aria-label={ariaLabel}
        aria-expanded={open}
        onClick={handleToggle}
        className="rounded-lg p-1.5 text-[var(--fg-muted)] hover:bg-[var(--fill)] hover:text-[var(--fg)] transition cursor-pointer"
      >
        <DotsThreeVertical size={16} />
      </button>

      {open && mounted && coords && createPortal(
        <div
          ref={menuRef}
          style={{
            position: "fixed",
            top: `${coords.top}px`,
            left: `${coords.left}px`,
            zIndex: 9999,
          }}
          className="w-52 rounded-xl border border-[var(--border)] bg-[var(--surface)] py-1.5 shadow-[var(--shadow-overlay)] animate-in fade-in-0 zoom-in-95 text-left"
          onClick={() => setOpen(false)}
        >
          {children}
        </div>,
        document.body
      )}
    </div>
  );
}

export function ActionMenuItem({
  icon,
  label,
  danger,
  onClick,
}: {
  icon: React.ReactNode;
  label: string;
  danger?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      className={`flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-xs font-medium transition cursor-pointer ${
        danger
          ? "text-red-600 hover:bg-red-500/10"
          : "text-[var(--fg-body)] hover:bg-[var(--fill)]"
      }`}
    >
      <span className="shrink-0">{icon}</span>
      <span>{label}</span>
    </button>
  );
}
