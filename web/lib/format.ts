const EXPONENT: Record<string, number> = { INR: 2, USD: 2, EUR: 2, GBP: 2, SGD: 2, AED: 2, JPY: 0 };
const LOCALE: Record<string, string> = { INR: "en-IN" };

/** Minor units -> display string. Integer math on the API; formatting only here. */
export function money(minor: number | null | undefined, currency = "INR"): string {
  if (minor === null || minor === undefined) return "—";
  const exp = EXPONENT[currency] ?? 2;
  const major = minor / Math.pow(10, exp);
  try {
    return new Intl.NumberFormat(LOCALE[currency] ?? "en-US", { style: "currency", currency, minimumFractionDigits: exp, maximumFractionDigits: exp }).format(major);
  } catch {
    return `${major.toFixed(exp)} ${currency}`;
  }
}

export function when(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "medium" });
}

export function short(id: string | null | undefined, keep = 10): string {
  if (!id) return "—";
  return id.length <= keep + 4 ? id : `${id.slice(0, keep)}…${id.slice(-4)}`;
}
