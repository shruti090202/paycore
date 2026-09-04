"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { api, ApiError, API_BASE, getToken } from "@/lib/api";
import { money, short, when } from "@/lib/format";
import { Alert, Badge, Button, Card, Code, Empty, Input, Select, Table, Td } from "@/components/ui";

type Run = { id: string; settlement_file_id: string; status: string; rows_total: number; rows_matched: number; items_open: number; settled_minor: number; journal_entry_id?: string; started_at: string; finished_at?: string; error?: string };
type File = { id: string; settlement_date: string; row_count: number; total_minor: number; currency: string; generated_at: string };
type Item = { id: string; run_id: string; kind: string; bank_ref?: string; payment_id?: string; refund_id?: string; expected_minor?: number; actual_minor?: number; detail: string; status: string; resolution?: string; created_at: string };

export default function ReconciliationPage() {
  const [runs, setRuns] = useState<Run[]>([]);
  const [files, setFiles] = useState<File[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [status, setStatus] = useState("open");
  const [resolutions, setResolutions] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    const [r, f, i] = await Promise.all([api<Run[]>("/dashboard/reconciliation/runs"), api<File[]>("/dashboard/reconciliation/files"), api<Item[]>(`/dashboard/reconciliation/items?status=${status}`)]);
    setRuns(r);
    setFiles(f);
    setItems(i);
  }, [status]);

  useAsyncEffect(() => load().catch((e) => setError(e instanceof ApiError ? e.message : String(e))), [load]);

  async function job(name: string) {
    setError(null);
    setNotice(null);
    try {
      const r = await api<{ status: string; result: unknown }>(`/dashboard/simulator/jobs/${name}`, { method: "POST", body: {} });
      setNotice(`${name}: ${r.status} — ${JSON.stringify(r.result)}`);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
    }
  }

  async function downloadCsv(id: string) {
    const res = await fetch(`${API_BASE}/dashboard/reconciliation/files/${id}/csv`, { headers: { Authorization: `Bearer ${getToken()}` } });
    const blob = await res.blob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${id}.csv`;
    a.click();
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Reconciliation</h1>
          <p className="text-sm text-zinc-500">The bank&apos;s settlement file is matched against the gateway&apos;s own record of every bank call. Only rows that match exactly are booked; everything else becomes an item below.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={() => job("settlement-generate")}>1. Bank: generate settlement file</Button>
          <Button variant="secondary" onClick={() => job("reconcile")}>2. Reconcile</Button>
          <Button variant="secondary" onClick={() => job("payout-run")}>3. Run payouts</Button>
        </div>
      </div>
      <p className="text-xs text-zinc-500">In production these three run daily from a scheduled workflow; the buttons trigger the same jobs for the demo. Set a settlement anomaly rate in the bank simulator to see discrepancies.</p>
      {error && <Alert kind="error">{error}</Alert>}
      {notice && <Alert kind="info"><span className="font-mono text-xs">{notice}</span></Alert>}

      <Card
        title="Exceptions"
        actions={
          <Select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="open">open</option>
            <option value="resolved">resolved</option>
            <option value="all">all</option>
          </Select>
        }
      >
        {items.length === 0 ? (
          <Empty>Nothing to review — your books agree with the bank.</Empty>
        ) : (
          <Table head={["When", "Kind", "Payment", "Expected", "Bank says", "Detail", "Status", ""]}>
            {items.map((it) => (
              <tr key={it.id}>
                <Td className="whitespace-nowrap text-zinc-500">{when(it.created_at)}</Td>
                <Td><Badge value={it.kind} /></Td>
                <Td mono>{it.payment_id ? <Link className="text-indigo-600 hover:underline" href={`/dashboard/payments/${it.payment_id}`}>{short(it.payment_id)}</Link> : short(it.bank_ref)}</Td>
                <Td>{it.expected_minor != null ? money(it.expected_minor) : "—"}</Td>
                <Td>{it.actual_minor != null ? money(it.actual_minor) : "—"}</Td>
                <Td className="max-w-sm text-xs">{it.detail}{it.resolution && <div className="text-zinc-500">↳ {it.resolution}</div>}</Td>
                <Td><Badge value={it.status} /></Td>
                <Td>
                  {it.status === "open" && (
                    <form className="flex gap-1" onSubmit={(e) => { e.preventDefault(); void api(`/dashboard/reconciliation/items/${it.id}/resolve`, { body: { resolution: resolutions[it.id] || "reviewed" } }).then(load); }}>
                      <Input value={resolutions[it.id] ?? ""} onChange={(e) => setResolutions({ ...resolutions, [it.id]: e.target.value })} placeholder="note" />
                      <Button type="submit" variant="ghost">Resolve</Button>
                    </form>
                  )}
                </Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Runs">
          {runs.length === 0 ? <Empty>No runs yet.</Empty> : (
            <Table head={["Started", "Rows", "Matched", "Open", "Booked", "Status"]}>
              {runs.map((r) => (
                <tr key={r.id}>
                  <Td className="whitespace-nowrap text-zinc-500">{when(r.started_at)}</Td>
                  <Td>{r.rows_total}</Td>
                  <Td>{r.rows_matched}</Td>
                  <Td>{r.items_open}</Td>
                  <Td>{money(r.settled_minor)}</Td>
                  <Td><Badge value={r.status} />{r.error && <div className="text-xs text-rose-600">{r.error}</div>}</Td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
        <Card title="Settlement files (from the bank)">
          {files.length === 0 ? <Empty>No files yet.</Empty> : (
            <Table head={["Date", "Rows", "Net", "File", ""]}>
              {files.map((f) => (
                <tr key={f.id}>
                  <Td>{f.settlement_date}</Td>
                  <Td>{f.row_count}</Td>
                  <Td>{money(f.total_minor, f.currency)}</Td>
                  <Td mono><Code>{short(f.id)}</Code></Td>
                  <Td><Button variant="ghost" onClick={() => downloadCsv(f.id)}>CSV</Button></Td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      </div>
    </div>
  );
}
