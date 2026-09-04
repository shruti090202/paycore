"use client";

import { useCallback, useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { api, ApiError, Page } from "@/lib/api";
import { short, when } from "@/lib/format";
import { Alert, Badge, Button, Card, Code, Empty, Input, Select, Table, Td } from "@/components/ui";

type Endpoint = { id: string; url: string; enabled_events: string[]; description?: string; active: boolean; secret?: string; created_at: string };
type Delivery = { id: string; event_id: string; event_type?: string; endpoint_id: string; status: string; attempts: number; next_attempt_at?: string; last_status_code?: number; last_error?: string; delivered_at?: string; created_at: string; attempt_log?: { attempt_no: number; status_code?: number; error?: string; duration_ms?: number; response_snippet?: string; created_at: string }[] };

export default function WebhooksPage() {
  const [endpoints, setEndpoints] = useState<Endpoint[]>([]);
  const [types, setTypes] = useState<string[]>([]);
  const [url, setUrl] = useState("");
  const [desc, setDesc] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [created, setCreated] = useState<Endpoint | null>(null);
  const [revealed, setRevealed] = useState<Record<string, string>>({});
  const [status, setStatus] = useState("");
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [open, setOpen] = useState<Delivery | null>(null);
  const [summary, setSummary] = useState<Record<string, number>>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    const [eps, tps, dl, sm] = await Promise.all([
      api<Endpoint[]>("/dashboard/webhook_endpoints"),
      api<string[]>("/dashboard/webhook_events/types"),
      api<Page<Delivery>>(`/dashboard/webhook_deliveries?limit=50${status ? `&status=${status}` : ""}`),
      api<Record<string, number>>("/dashboard/webhook_deliveries/summary"),
    ]);
    setEndpoints(eps);
    setTypes(tps);
    setDeliveries(dl.data);
    setSummary(sm);
  }, [status]);

  useAsyncEffect(() => load().catch((e) => setError(e instanceof ApiError ? e.message : String(e))), [load]);

  async function run<T>(fn: () => Promise<T>, ok?: string) {
    setError(null);
    setNotice(null);
    try {
      const r = await fn();
      if (ok) setNotice(ok);
      await load();
      return r;
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Webhooks</h1>
        <p className="text-sm text-zinc-500">
          Events are written in the same database transaction as the change they describe (transactional outbox) and delivered with
          <Code>PayCore-Signature: t=&lt;unix&gt;,v1=&lt;HMAC-SHA256&gt;</Code>. Failed deliveries retry with exponential backoff and jitter, then dead-letter.
        </p>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {notice && <Alert kind="success">{notice}</Alert>}
      {created?.secret && (
        <Alert kind="success">
          <p className="font-medium">Endpoint created. Signing secret (shown once here; you can reveal it again from the list):</p>
          <pre className="mt-2 select-all overflow-x-auto rounded bg-white/60 p-2 font-mono text-xs dark:bg-black/30">{created.secret}</pre>
        </Alert>
      )}

      <Card title="Add an endpoint">
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            void run(async () => {
              const ep = await api<Endpoint>("/dashboard/webhook_endpoints", { body: { url, description: desc || undefined, enabled_events: selected } });
              setCreated(ep);
              setUrl("");
              setDesc("");
              setSelected([]);
            });
          }}
        >
          <Input label="URL (https; localhost allowed in local dev)" value={url} onChange={(e) => setUrl(e.target.value)} required placeholder="https://example.com/webhooks/paycore" />
          <Input label="Description" value={desc} onChange={(e) => setDesc(e.target.value)} maxLength={200} />
          <div>
            <p className="mb-1 text-sm text-zinc-600 dark:text-zinc-400">Events (none selected = all)</p>
            <div className="flex flex-wrap gap-2">
              {types.map((t) => (
                <label key={t} className={`cursor-pointer rounded-full border px-2.5 py-1 text-xs ${selected.includes(t) ? "border-indigo-500 bg-indigo-50 text-indigo-700 dark:bg-indigo-900/30" : "border-zinc-300 dark:border-zinc-700"}`}>
                  <input type="checkbox" className="hidden" checked={selected.includes(t)} onChange={(e) => setSelected(e.target.checked ? [...selected, t] : selected.filter((x) => x !== t))} />
                  {t}
                </label>
              ))}
            </div>
          </div>
          <Button type="submit">Add endpoint</Button>
        </form>
      </Card>

      <Card title="Endpoints">
        {endpoints.length === 0 ? (
          <Empty>No endpoints. The demo store registers one automatically when it first receives a payment.</Empty>
        ) : (
          <Table head={["URL", "Events", "Status", "Secret", ""]}>
            {endpoints.map((ep) => (
              <tr key={ep.id} className={ep.active ? "" : "opacity-50"}>
                <Td mono className="max-w-xs truncate">{ep.url}<div className="text-[10px] text-zinc-500">{ep.description}</div></Td>
                <Td className="text-xs">{ep.enabled_events.length ? ep.enabled_events.join(", ") : "all"}</Td>
                <Td>{ep.active ? "active" : "deleted"}</Td>
                <Td mono>
                  {revealed[ep.id] ? (
                    <span className="select-all">{revealed[ep.id]}</span>
                  ) : (
                    ep.active && <Button variant="ghost" onClick={() => run(async () => { const r = await api<{ secret: string }>(`/dashboard/webhook_endpoints/${ep.id}/secret`); setRevealed({ ...revealed, [ep.id]: r.secret }); })}>Reveal</Button>
                  )}
                </Td>
                <Td>
                  {ep.active && (
                    <div className="flex gap-1">
                      <Button variant="ghost" onClick={() => run(() => api(`/dashboard/webhook_endpoints/${ep.id}/test`, { method: "POST", body: {} }), "Ping queued — it is delivered by the dispatcher within a few seconds")}>Send test</Button>
                      <Button variant="ghost" onClick={() => { if (confirm("Delete this endpoint?")) void run(() => api(`/dashboard/webhook_endpoints/${ep.id}`, { method: "DELETE" }), "Endpoint deleted"); }}>Delete</Button>
                    </div>
                  )}
                </Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <Card
        title={`Deliveries — ${summary.pending ?? 0} pending · ${summary.delivered ?? 0} delivered · ${summary.dead ?? 0} dead`}
        actions={
          <Select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">all</option>
            <option value="pending">pending</option>
            <option value="delivered">delivered</option>
            <option value="dead">dead</option>
          </Select>
        }
      >
        {deliveries.length === 0 ? (
          <Empty>No deliveries yet.</Empty>
        ) : (
          <Table head={["Created", "Event", "Status", "Attempts", "Last", ""]}>
            {deliveries.map((d) => (
              <tr key={d.id}>
                <Td className="whitespace-nowrap text-zinc-500">{when(d.created_at)}</Td>
                <Td><span className="font-mono text-xs">{d.event_type ?? short(d.event_id)}</span></Td>
                <Td><Badge value={d.status} /></Td>
                <Td>{d.attempts}{d.next_attempt_at && <span className="block text-[10px] text-zinc-500">next {when(d.next_attempt_at)}</span>}</Td>
                <Td className="text-xs">{d.last_status_code ?? ""} {d.last_error ?? ""}</Td>
                <Td>
                  <div className="flex gap-1">
                    <Button variant="ghost" onClick={() => run(async () => setOpen(await api<Delivery>(`/dashboard/webhook_deliveries/${d.id}`)))}>Attempts</Button>
                    {d.status !== "pending" && <Button variant="ghost" onClick={() => run(() => api(`/dashboard/webhook_deliveries/${d.id}/replay`, { method: "POST", body: {} }), "Replay queued")}>Replay</Button>}
                  </div>
                </Td>
              </tr>
            ))}
          </Table>
        )}
        {open && (
          <div className="mt-4 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
            <div className="flex items-center justify-between">
              <p className="text-sm font-medium">Delivery {open.id} — attempt log</p>
              <Button variant="ghost" onClick={() => setOpen(null)}>Close</Button>
            </div>
            <Table head={["#", "When", "HTTP", "Duration", "Error / response"]}>
              {(open.attempt_log ?? []).map((a) => (
                <tr key={a.attempt_no}>
                  <Td>{a.attempt_no}</Td>
                  <Td className="text-zinc-500">{when(a.created_at)}</Td>
                  <Td>{a.status_code ?? "—"}</Td>
                  <Td>{a.duration_ms != null ? `${a.duration_ms} ms` : "—"}</Td>
                  <Td className="max-w-md truncate text-xs">{a.error ?? a.response_snippet ?? ""}</Td>
                </tr>
              ))}
            </Table>
          </div>
        )}
      </Card>
    </div>
  );
}
