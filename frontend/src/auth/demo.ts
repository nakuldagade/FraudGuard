// One-click demo logins on the sign-in page, so the project can be shown quickly.
// DEMO ONLY: these values are compiled into the page, so anyone who can load the page can read them. They come from
// VITE_DEMO_* variables (Docker build args from .env, or frontend/.env.development). Set VITE_SHOW_DEMO_LOGINS=false,
// or leave the variables out, to hide the section.
export interface DemoAccount {
  role: 'ANALYST' | 'ADMIN';
  label: string;
  blurb: string;
  username: string;
  password: string;
}

export function getDemoAccounts(): DemoAccount[] {
  const env = import.meta.env;
  if (env.VITE_SHOW_DEMO_LOGINS === 'false') return [];
  const accounts: DemoAccount[] = [
    {
      role: 'ANALYST', label: 'Analyst', blurb: 'Review transactions, charts, model feedback',
      username: env.VITE_DEMO_ANALYST_USERNAME ?? '', password: env.VITE_DEMO_ANALYST_PASSWORD ?? '',
    },
    {
      role: 'ADMIN', label: 'Admin', blurb: 'Everything an analyst can do, plus the Operations tab',
      username: env.VITE_DEMO_ADMIN_USERNAME ?? '', password: env.VITE_DEMO_ADMIN_PASSWORD ?? '',
    },
  ];
  return accounts.filter((a) => a.username && a.password);
}
