"use client";

import { useCallback, useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { api, ApiError, API_BASE } from "@/lib/api";
import { when } from "@/lib/format";
import { Alert, Button, Card, Code, Empty, Input, Table, Td } from "@/components/ui";

type Key = { id: string; name: string; prefix: string; created_at: string; last_used_at?: string; revoked_at?: string };

export default function ApiKeysPage() {
  const [keys, setKeys] = useState<Key[]>([]);
  const [name, setName] = useState("");
  const [created, setCreated] = useState<{ key: string; name: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => api<Key[]>("/dashboard/api_keys").then(setKeys), []);
  useAsyncEffect(() => load(), [load]);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const k = await api<{ key: string; name: string }>("/dashboard/api_keys", { body: { name } });
      setCreated(k);
      setName("");
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err));
    }
  }

  async function revoke(id: string) {
    if (!confirm("Revoke this key? Requests using it will fail immediately.")) return;
    await api(`/dashboard/api_keys/${id}`, { method: "DELETE" });
    await load();
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">API keys</h1>
        <p className="text-sm text-zinc-500">Secret keys start with <Code>sk_test_</Code>. Only a SHA-256 hash is stored; the full key is shown once, at creation.</p>
      </div>
      {created && (
        <Alert kind="success">
          <p className="font-medium">Key “{created.name}” created. Copy it now — it will not be shown again.</p>
          <pre className="mt-2 select-all overflow-x-auto rounded bg-white/60 p-2 font-mono text-xs dark:bg-black/30">{created.key}</pre>
          <p className="mt-2 text-xs">
            Try it: <code>curl -H &quot;Authorization: Bearer {created.key.slice(0, 12)}…&quot; {API_BASE}/v1/account</code>
          </p>
        </Alert>
      )}
      {error && <Alert kind="error">{error}</Alert>}
      <Card title="Create a key">
        <form onSubmit={create} className="flex items-end gap-3">
          <div className="flex-1"><Input label="Name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} placeholder="backend, staging, …" /></div>
          <Button type="submit">Create</Button>
        </form>
      </Card>
      <Card title="Keys">
        {keys.length === 0 ? (
          <Empty>No keys yet.</Empty>
        ) : (
          <Table head={["Name", "Prefix", "Created", "Last used", "Status", ""]}>
            {keys.map((k) => (
              <tr key={k.id} className={k.revoked_at ? "opacity-50" : ""}>
                <Td>{k.name}</Td>
                <Td mono>{k.prefix}…</Td>
                <Td className="text-zinc-500">{when(k.created_at)}</Td>
                <Td className="text-zinc-500">{k.last_used_at ? when(k.last_used_at) : "never"}</Td>
                <Td>{k.revoked_at ? `revoked ${when(k.revoked_at)}` : "active"}</Td>
                <Td>{!k.revoked_at && <Button variant="ghost" onClick={() => revoke(k.id)}>Revoke</Button>}</Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
