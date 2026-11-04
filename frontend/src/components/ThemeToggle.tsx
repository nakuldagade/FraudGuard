import { useTheme } from '../theme/theme';
import { MoonIcon, SunIcon } from './Icons';

// Round button that switches between the light and dark theme
export function ThemeToggle() {
  const { theme, toggle } = useTheme();
  const next = theme === 'dark' ? 'light' : 'dark';
  return (
    <button type="button" className="icon-btn theme-toggle" onClick={toggle}
            aria-label={`Switch to ${next} theme`} title={`Switch to ${next} theme`}>
      <span className="theme-icon" key={theme}>{theme === 'dark' ? <SunIcon /> : <MoonIcon />}</span>
    </button>
  );
}
