"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { api, ApiError, Page } from "@/lib/api";
import { money, short, when } from "@/lib/format";
import { Payment } from "@/lib/types";
import { Alert, Badge, Button, Card, Empty, Input, Select, Table, Td } from "@/components/ui";

const STATUSES = ["", "created", "pending_bank", "authorized", "captured", "partially_refunded", "refunded", "failed", "canceled"];

export default function PaymentsPage() {
  const [status, setStatus] = useState("");
  const [email, setEmail] = useState("");
  const [pages, setPages] = useState<Page<Payment>[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (cursor?: string) => {
    setBusy(true);
    setError(null);
    try {
      const q = new URLSearchParams({ limit: "25" });
      if (status) q.set("status", status);
      if (email) q.set("customer_email", email);
      if (cursor) q.set("cursor", cursor);
      const page = await api<Page<Payment>>(`/dashboard/payments?${q}`);
      setPages((prev) => (cursor ? [...prev, page] : [page]));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [status, email]);

  useAsyncEffect(() => load(), [load]);

  const rows = pages.flatMap((p) => p.data);
  const last = pages[pages.length - 1];

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Payments</h1>
          <p className="text-sm text-zinc-500">Newest first. Create payments through the API or the demo store.</p>
        </div>
      </div>
      <Card>
        <div className="grid gap-3 sm:grid-cols-3">
          <Select label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
            {STATUSES.map((s) => (
              <option key={s} value={s}>{s || "any"}</option>
            ))}
          </Select>
          <Input label="Customer email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="buyer@example.com" />
          <div className="flex items-end">
            <Button variant="secondary" onClick={() => load()} disabled={busy}>Refresh</Button>
          </div>
        </div>
      </Card>
      {error && <Alert kind="error">{error}</Alert>}
      <Card>
        {rows.length === 0 ? (
          <Empty>{busy ? "Loading…" : "No payments yet."}</Empty>
        ) : (
          <Table head={["Payment", "Amount", "Status", "Customer", "Card", "Risk", "Created"]}>
            {rows.map((p) => (
              <tr key={p.id} className="hover:bg-zinc-50 dark:hover:bg-zinc-800/50">
                <Td mono><Link className="text-indigo-600 hover:underline" href={`/dashboard/payments/${p.id}`}>{short(p.id)}</Link></Td>
                <Td>
                  {money(p.amount_minor, p.currency)}
                  {p.refunded_minor > 0 && <span className="ml-1 text-xs text-zinc-500">(−{money(p.refunded_minor, p.currency)})</span>}
                </Td>
                <Td><Badge value={p.status} /></Td>
                <Td>{p.customer?.email ?? "—"}</Td>
                <Td>{p.card ? `${p.card.brand} •••• ${p.card.last4}` : "—"}</Td>
                <Td>{p.risk ? <span className="inline-flex items-center gap-1"><Badge value={p.risk.decision} /><span className="text-xs text-zinc-500">{p.risk.score}</span></span> : "—"}</Td>
                <Td className="whitespace-nowrap text-zinc-500">{when(p.created_at)}</Td>
              </tr>
            ))}
          </Table>
        )}
        {last?.has_more && (
          <div className="mt-4 text-center">
            <Button variant="secondary" onClick={() => load(last.next_cursor)} disabled={busy}>Load more</Button>
          </div>
        )}
      </Card>
    </div>
  );
}
