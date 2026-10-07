// Formatting helpers used by the table and the detail panel

export const formatMoney = (amount: number, currency: string) =>
    new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(amount);

export const formatDateTime = (iso: string) =>
    new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });

// Map a 0-100 risk score to a colour bucket used in CSS (low / medium / high)
export const riskLevel = (score: number): 'low' | 'medium' | 'high' =>
    score >= 70 ? 'high' : score >= 40 ? 'medium' : 'low';

// "CONFIRMED_FRAUD" -> "Confirmed fraud"
export const humanize = (value: string) => {
    const s = value.toLowerCase().replace(/_/g, ' ');
    return s.charAt(0).toUpperCase() + s.slice(1);
};