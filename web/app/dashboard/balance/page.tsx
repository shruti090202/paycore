"use client";

import { useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { api } from "@/lib/api";
import { money, short, when } from "@/lib/format";
import { Badge, Card, Empty, Table, Td } from "@/components/ui";

type Balance = { account_code: string; type: string; currency: string; balance_minor: number; posting_count: number };
type Payout = { id: string; amount_minor: number; currency: string; status: string; journal_entry_id: string; created_at: string };

export default function BalancePage() {
  const [balances, setBalances] = useState<Balance[]>([]);
  const [payouts, setPayouts] = useState<Payout[]>([]);

  useAsyncEffect(async () => {
    setBalances(await api<Balance[]>("/dashboard/balance"));
    setPayouts(await api<Payout[]>("/dashboard/reconciliation/payouts"));
  }, []);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Balance & payouts</h1>
        <p className="text-sm text-zinc-500">Your balance is the ledger balance of your <code>merchant_payable</code> account: captured amounts minus fees and refunds, minus payouts. It is derived from postings, never stored.</p>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        {balances.length === 0 && <Card><Empty>No balance yet — capture a payment first.</Empty></Card>}
        {balances.map((b) => (
          <Card key={b.account_code} title={b.currency}>
            <p className={`text-3xl font-semibold ${b.balance_minor < 0 ? "text-rose-600" : ""}`}>{money(b.balance_minor, b.currency)}</p>
            <p className="mt-1 text-xs text-zinc-500">{b.posting_count} postings · <code>{b.account_code}</code></p>
            {b.balance_minor < 0 && <p className="mt-2 text-xs text-rose-600">Negative: refunds exceeded what was payable (fees are not returned on refund). A real gateway nets this against future captures.</p>}
          </Card>
        ))}
      </div>
      <Card title="Payouts">
        <p className="mb-3 text-xs text-zinc-500">Payouts are made by the daily <code>payout-run</code> job, only out of money the bank has actually settled.</p>
        {payouts.length === 0 ? (
          <Empty>No payouts yet.</Empty>
        ) : (
          <Table head={["When", "Payout", "Amount", "Status", "Journal entry"]}>
            {payouts.map((p) => (
              <tr key={p.id}>
                <Td className="text-zinc-500">{when(p.created_at)}</Td>
                <Td mono>{short(p.id)}</Td>
                <Td>{money(p.amount_minor, p.currency)}</Td>
                <Td><Badge value={p.status} /></Td>
                <Td mono>{short(p.journal_entry_id)}</Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
