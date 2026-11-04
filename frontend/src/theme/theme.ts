import { useSyncExternalStore } from 'react';

// Light / dark theme. The choice is stored in localStorage (wrapped: storage can be blocked) and applied as
// data-theme on <html>, where index.css switches the colour variables. index.html applies it before first paint.
export type Theme = 'light' | 'dark';
const KEY = 'fraudguard.theme';
const listeners = new Set<() => void>();

export function getTheme(): Theme {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

export function setTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(KEY, theme);
  } catch {
    /* the theme just will not be remembered */
  }
  listeners.forEach((fn) => fn());
}

const subscribe = (fn: () => void) => {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
};

export function useTheme() {
  const theme = useSyncExternalStore(subscribe, getTheme, () => 'light' as Theme);
  return { theme, toggle: () => setTheme(theme === 'dark' ? 'light' : 'dark') };
}
