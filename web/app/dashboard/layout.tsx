"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, getToken, setToken } from "@/lib/api";

const NAV: [string, string][] = [
  ["/dashboard/payments", "Payments"],
  ["/dashboard/balance", "Balance & payouts"],
  ["/dashboard/api-keys", "API keys"],
  ["/dashboard/webhooks", "Webhooks"],
  ["/dashboard/risk", "Risk"],
  ["/dashboard/reconciliation", "Reconciliation"],
  ["/dashboard/simulator", "Bank simulator"],
];

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [merchant, setMerchant] = useState<{ name: string; email: string; is_demo: boolean } | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    api<{ name: string; email: string; is_demo: boolean }>("/dashboard/me")
      .then((m) => {
        setMerchant(m);
        setReady(true);
      })
      .catch(() => router.replace("/login"));
  }, [router]);

  if (!ready) return <div className="p-10 text-sm text-zinc-500">Loading…</div>;

  return (
    <div className="flex min-h-screen">
      <aside className="hidden w-60 shrink-0 flex-col border-r border-zinc-200 bg-white px-4 py-6 dark:border-zinc-800 dark:bg-zinc-900 md:flex">
        <Link href="/" className="px-2 text-sm font-semibold uppercase tracking-widest text-indigo-600">PayCore</Link>
        <p className="mt-1 truncate px-2 text-xs text-zinc-500">{merchant?.name}{merchant?.is_demo ? " (demo)" : ""}</p>
        <nav className="mt-6 flex flex-col gap-1">
          {NAV.map(([href, label]) => (
            <Link
              key={href}
              href={href}
              className={`rounded-lg px-3 py-2 text-sm ${pathname.startsWith(href) ? "bg-indigo-50 font-medium text-indigo-700 dark:bg-zinc-800 dark:text-indigo-300" : "text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800"}`}
            >
              {label}
            </Link>
          ))}
        </nav>
        <div className="mt-auto px-2">
          <Link href="/demo-store" className="block py-1 text-xs text-zinc-500 hover:underline">Demo store ↗</Link>
          <button
            className="mt-2 text-xs text-zinc-500 hover:underline"
            onClick={() => {
              setToken(null);
              router.replace("/login");
            }}
          >
            Sign out
          </button>
        </div>
      </aside>
      <div className="flex-1">
        <div className="border-b border-zinc-200 bg-white px-4 py-2 text-xs text-zinc-500 md:hidden dark:border-zinc-800 dark:bg-zinc-900">
          <div className="flex flex-wrap gap-3">
            {NAV.map(([href, label]) => (
              <Link key={href} href={href} className={pathname.startsWith(href) ? "font-semibold text-indigo-600" : ""}>{label}</Link>
            ))}
          </div>
        </div>
        <main className="mx-auto w-full max-w-6xl px-6 py-8">{children}</main>
      </div>
    </div>
  );
}
