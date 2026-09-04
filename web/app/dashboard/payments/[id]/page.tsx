"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { api, ApiError } from "@/lib/api";
import { money, short, when } from "@/lib/format";
import { PaymentDetail } from "@/lib/types";
import { Alert, Badge, Button, Card, Code, Empty, Input, KeyValue, Table, Td } from "@/components/ui";

export default function PaymentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [d, setD] = useState<PaymentDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [refundAmount, setRefundAmount] = useState("");
  const [refundReason, setRefundReason] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setD(await api<PaymentDetail>(`/dashboard/payments/${id}`));
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }, [id]);

  useAsyncEffect(() => load(), [load]);

  async function act(path: string, body?: unknown, ok?: string) {
    setBusy(true);
    setNotice(null);
    setError(null);
    try {
      await api(path, { method: "POST", body: body ?? {} });
      setNotice(ok ?? "Done");
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (error && !d) return <Alert kind="error">{error}</Alert>;
  if (!d) return <p className="text-sm text-zinc-500">Loading…</p>;
  const p = d.payment;
  const refundable = p.captured_minor - p.refunded_minor - d.refunds.filter((r) => r.status === "pending").reduce((s, r) => s + r.amount_minor, 0);
  const canRefund = ["captured", "partially_refunded"].includes(p.status) && refundable > 0;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link href="/dashboard/payments" className="text-xs text-zinc-500 hover:underline">← Payments</Link>
          <h1 className="mt-1 font-mono text-xl font-bold">{p.id}</h1>
          <div className="mt-2 flex items-center gap-3">
            <span className="text-2xl font-semibold">{money(p.amount_minor, p.currency)}</span>
            <Badge value={p.status} />
            {p.risk && <span className="text-xs text-zinc-500">risk {p.risk.score} · <Badge value={p.risk.decision} /></span>}
          </div>
        </div>
        <div className="flex gap-2">
          {p.status === "authorized" && <Button disabled={busy} onClick={() => act(`/dashboard/payments/${p.id}/capture`, {}, "Captured")}>Capture</Button>}
          {["created", "authorized"].includes(p.status) && <Button variant="danger" disabled={busy} onClick={() => act(`/dashboard/payments/${p.id}/cancel`, {}, "Canceled")}>Cancel</Button>}
        </div>
      </div>
      {notice && <Alert kind="success">{notice}</Alert>}
      {error && <Alert kind="error">{error}</Alert>}

      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="Details" className="lg:col-span-2">
          <KeyValue
            rows={[
              ["Captured", money(p.captured_minor, p.currency)],
              ["Refunded", money(p.refunded_minor, p.currency)],
              ["Capture method", p.capture_method],
              ["Customer", p.customer?.email ?? "—"],
              ["Card", p.card ? `${p.card.brand} •••• ${p.card.last4}` : "—"],
              ["Card fingerprint", d.card_fingerprint ? <Code>{d.card_fingerprint}</Code> : "—"],
              ["Bank reference", p.bank_ref ? <Code>{p.bank_ref}</Code> : "—"],
              ["Description", p.description ?? "—"],
              ["Created", when(p.created_at)],
              ["Updated", when(p.updated_at)],
              ["Failure", p.failure ? `${p.failure.code}: ${p.failure.message}` : "—"],
              ["Checkout URL", p.checkout_url ? <a className="text-indigo-600 hover:underline" href={p.checkout_url}>open</a> : "—"],
            ]}
          />
          {p.metadata && Object.keys(p.metadata).length > 0 && (
            <pre className="mt-4 rounded-lg bg-zinc-100 p-3 text-xs dark:bg-zinc-800">{JSON.stringify(p.metadata, null, 2)}</pre>
          )}
        </Card>

        <Card title="Refund">
          {canRefund ? (
            <form
              className="space-y-3"
              onSubmit={(e) => {
                e.preventDefault();
                void act(`/dashboard/payments/${p.id}/refunds`, { amount_minor: refundAmount ? Number(refundAmount) : undefined, reason: refundReason || undefined }, "Refund requested");
              }}
            >
              <p className="text-sm text-zinc-500">Refundable: {money(refundable, p.currency)}</p>
              <Input label={`Amount in minor units (blank = ${refundable})`} value={refundAmount} onChange={(e) => setRefundAmount(e.target.value)} inputMode="numeric" pattern="[0-9]*" />
              <Input label="Reason" value={refundReason} onChange={(e) => setRefundReason(e.target.value)} maxLength={500} />
              <Button type="submit" disabled={busy}>Refund</Button>
            </form>
          ) : (
            <Empty>{p.status === "refunded" ? "Fully refunded." : "Nothing to refund."}</Empty>
          )}
          {d.refunds.length > 0 && (
            <ul className="mt-4 space-y-1 text-sm">
              {d.refunds.map((r) => (
                <li key={r.id} className="flex items-center justify-between gap-2">
                  <span className="font-mono text-xs">{short(r.id)}</span>
                  <span>{money(r.amount_minor, r.currency)}</span>
                  <Badge value={r.status} />
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      {d.risk && (
        <Card title={`Risk decision — score ${d.risk.score}`}>
          {d.risk.reasons.length === 0 ? (
            <Empty>No rules fired.</Empty>
          ) : (
            <ul className="space-y-1 text-sm">
              {d.risk.reasons.map((r, i) => (
                <li key={i}><Code>{r.rule}</Code> +{r.score} — {r.message}</li>
              ))}
            </ul>
          )}
        </Card>
      )}

      <Card title="Timeline">
        <Table head={["When", "Event", "Transition", "Data"]}>
          {d.events.map((e) => (
            <tr key={e.id}>
              <Td className="whitespace-nowrap text-zinc-500">{when(e.created_at)}</Td>
              <Td mono>{e.type}</Td>
              <Td>{e.from_status || e.to_status ? <span className="text-xs">{e.from_status ?? "∅"} → {e.to_status ?? "∅"}</span> : ""}</Td>
              <Td mono className="max-w-md truncate">{JSON.stringify(e.data)}</Td>
            </tr>
          ))}
        </Table>
      </Card>

      <Card title="Ledger entries">
        {d.ledger.length === 0 ? (
          <Empty>No money has moved for this payment (authorizations do not create ledger entries).</Empty>
        ) : (
          d.ledger.map((je) => (
            <div key={je.id} className="mb-4">
              <p className="text-sm"><Code>{je.kind}</Code> {je.description} <span className="text-xs text-zinc-500">{when(je.created_at)}</span></p>
              <Table head={["Account", "Debit", "Credit"]}>
                {je.postings.map((po, i) => (
                  <tr key={i}>
                    <Td mono>{po.account_code}</Td>
                    <Td>{po.direction === "debit" ? money(po.amount_minor, po.currency) : ""}</Td>
                    <Td>{po.direction === "credit" ? money(po.amount_minor, po.currency) : ""}</Td>
                  </tr>
                ))}
              </Table>
            </div>
          ))
        )}
      </Card>

      <Card title="Bank attempts">
        {d.bank_attempts.length === 0 ? (
          <Empty>The bank has not been contacted for this payment.</Empty>
        ) : (
          <Table head={["When", "Kind", "Reference", "Amount", "Outcome", "Latency", "Resolution"]}>
            {d.bank_attempts.map((a) => (
              <tr key={a.id}>
                <Td className="whitespace-nowrap text-zinc-500">{when(a.created_at)}</Td>
                <Td>{a.kind}</Td>
                <Td mono>{a.bank_ref}</Td>
                <Td>{money(a.amount_minor, p.currency)}</Td>
                <Td><Badge value={a.outcome} />{a.decline_code && <span className="ml-1 text-xs">{a.decline_code}</span>}</Td>
                <Td>{a.latency_ms != null ? `${a.latency_ms} ms` : "—"}</Td>
                <Td>{a.resolution ?? "—"}</Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
