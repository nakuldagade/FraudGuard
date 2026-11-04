import type { ReactNode, SVGProps } from 'react';

// Small inline SVG icons (no icon library). They inherit the text colour through currentColor.
function Icon({ children, ...props }: SVGProps<SVGSVGElement> & { children: ReactNode }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>
      {children}
    </svg>
  );
}

export const ShieldIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><path d="M12 3l8 3v6c0 4.5-3.2 8.2-8 9-4.8-.8-8-4.5-8-9V6l8-3z" /><path d="M9 12l2 2 4-4" /></Icon>
);
export const SunIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></Icon>
);
export const MoonIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><path d="M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z" /></Icon>
);
export const CloseIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><path d="M18 6L6 18M6 6l12 12" /></Icon>
);
export const SearchIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3" /></Icon>
);
export const LogOutIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9" /></Icon>
);
export const ListIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" /></Icon>
);
export const CheckIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><circle cx="12" cy="12" r="9" /><path d="M8 12l3 3 5-6" /></Icon>
);
export const EyeIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" /><circle cx="12" cy="12" r="3" /></Icon>
);
export const BanIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><circle cx="12" cy="12" r="9" /><path d="M5.6 5.6l12.8 12.8" /></Icon>
);
export const ClockIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></Icon>
);
export const InboxIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><path d="M22 12h-6l-2 3h-4l-2-3H2" /><path d="M5.5 5h13L22 12v6a2 2 0 01-2 2H4a2 2 0 01-2-2v-6l3.5-7z" /></Icon>
);
export const ChevronIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><path d="M6 9l6 6 6-6" /></Icon>
);
export const KeyIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}><circle cx="8" cy="15" r="4" /><path d="M10.8 12.2L21 2M16 7l3 3" /></Icon>
);
