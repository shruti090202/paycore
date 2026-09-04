"use client";

import { ReactNode } from "react";

export function Card({ title, children, actions, className = "" }: { title?: ReactNode; children: ReactNode; actions?: ReactNode; className?: string }) {
  return (
    <section className={`rounded-xl border border-zinc-200 bg-white shadow-sm dark:border-zinc-800 dark:bg-zinc-900 ${className}`}>
      {(title || actions) && (
        <header className="flex items-center justify-between gap-4 border-b border-zinc-100 px-5 py-3 dark:border-zinc-800">
          <h2 className="text-sm font-semibold tracking-wide text-zinc-700 dark:text-zinc-200">{title}</h2>
          {actions}
        </header>
      )}
      <div className="p-5">{children}</div>
    </section>
  );
}

export function Button({ children, variant = "primary", type = "button", ...rest }: { children: ReactNode; variant?: "primary" | "secondary" | "danger" | "ghost" } & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const base = "inline-flex items-center justify-center gap-2 rounded-lg px-3.5 py-2 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50";
  const styles = {
    primary: "bg-indigo-600 text-white hover:bg-indigo-500",
    secondary: "border border-zinc-300 bg-white text-zinc-800 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:hover:bg-zinc-800",
    danger: "bg-rose-600 text-white hover:bg-rose-500",
    ghost: "text-indigo-600 hover:bg-indigo-50 dark:text-indigo-300 dark:hover:bg-zinc-800",
  }[variant];
  return (
    <button type={type} className={`${base} ${styles}`} {...rest}>
      {children}
    </button>
  );
}

export function Input(props: React.InputHTMLAttributes<HTMLInputElement> & { label?: string }) {
  const { label, className = "", ...rest } = props;
  return (
    <label className="block text-sm">
      {label && <span className="mb-1 block text-zinc-600 dark:text-zinc-400">{label}</span>}
      <input
        className={`w-full rounded-lg border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-900 outline-none ring-indigo-500 focus:ring-2 dark:border-zinc-700 dark:bg-zinc-950 dark:text-zinc-100 ${className}`}
        {...rest}
      />
    </label>
  );
}

export function Select(props: React.SelectHTMLAttributes<HTMLSelectElement> & { label?: string }) {
  const { label, className = "", children, ...rest } = props;
  return (
    <label className="block text-sm">
      {label && <span className="mb-1 block text-zinc-600 dark:text-zinc-400">{label}</span>}
      <select className={`w-full rounded-lg border border-zinc-300 bg-white px-3 py-2 text-sm dark:border-zinc-700 dark:bg-zinc-950 ${className}`} {...rest}>
        {children}
      </select>
    </label>
  );
}

const STATUS_COLORS: Record<string, string> = {
  captured: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200",
  succeeded: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200",
  delivered: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200",
  allow: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200",
  completed: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200",
  paid: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200",
  authorized: "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-200",
  created: "bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200",
  pending: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200",
  pending_bank: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200",
  review: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200",
  open: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200",
  partially_refunded: "bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-200",
  refunded: "bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-200",
  failed: "bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-200",
  dead: "bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-200",
  block: "bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-200",
  canceled: "bg-zinc-200 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
  resolved: "bg-zinc-200 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
};

export function Badge({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="text-zinc-400">—</span>;
  const cls = STATUS_COLORS[value] ?? "bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200";
  return <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${cls}`}>{value.replace(/_/g, " ")}</span>;
}

export function Alert({ kind = "info", children }: { kind?: "info" | "error" | "success" | "warn"; children: ReactNode }) {
  const cls = {
    info: "border-sky-200 bg-sky-50 text-sky-900 dark:border-sky-900 dark:bg-sky-950/40 dark:text-sky-100",
    error: "border-rose-200 bg-rose-50 text-rose-900 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-100",
    success: "border-emerald-200 bg-emerald-50 text-emerald-900 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-100",
    warn: "border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-100",
  }[kind];
  return <div className={`rounded-lg border px-4 py-3 text-sm ${cls}`}>{children}</div>;
}

export function Table({ head, children }: { head: ReactNode[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase tracking-wide text-zinc-500">
          <tr>
            {head.map((h, i) => (
              <th key={i} className="border-b border-zinc-200 px-3 py-2 font-medium dark:border-zinc-800">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-100 dark:divide-zinc-800">{children}</tbody>
      </table>
    </div>
  );
}

export function Td({ children, mono = false, className = "" }: { children: ReactNode; mono?: boolean; className?: string }) {
  return <td className={`px-3 py-2 align-top ${mono ? "font-mono text-xs" : ""} ${className}`}>{children}</td>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-sm text-zinc-500">{children}</p>;
}

export function Code({ children }: { children: ReactNode }) {
  return <code className="rounded bg-zinc-100 px-1.5 py-0.5 font-mono text-xs dark:bg-zinc-800">{children}</code>;
}

export function KeyValue({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
      {rows.map(([k, v]) => (
        <div key={k} className="flex flex-col">
          <dt className="text-xs uppercase tracking-wide text-zinc-500">{k}</dt>
          <dd className="text-zinc-900 dark:text-zinc-100">{v}</dd>
        </div>
      ))}
    </dl>
  );
}
