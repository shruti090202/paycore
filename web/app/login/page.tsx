"use client";

import { Suspense, useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { api, ApiError, setToken } from "@/lib/api";
import { Alert, Button, Card, Input } from "@/components/ui";

type AuthResponse = { token: string; merchant: { name: string } };

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function finish(r: AuthResponse) {
    setToken(r.token);
    router.replace("/dashboard/payments");
  }

  async function demo() {
    setBusy(true);
    setError(null);
    try {
      await finish(await api<AuthResponse>("/dashboard/auth/demo", { method: "POST", body: {}, auth: false }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The API is not reachable yet (free instances take up to a minute to wake). Try again.");
    } finally {
      setBusy(false);
    }
  }

  useAsyncEffect(() => {
    if (params.get("demo") === "1") return demo();
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = mode === "login" ? { email, password } : { name, email, password };
      await finish(await api<AuthResponse>(`/dashboard/auth/${mode}`, { method: "POST", body, auth: false }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not reach the API");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md flex-col justify-center px-6 py-10">
      <Link href="/" className="mb-6 text-sm font-semibold uppercase tracking-widest text-indigo-600">PayCore</Link>
      <Card title={mode === "login" ? "Merchant login" : "Create a merchant account"}>
        <div className="mb-4">
          <Button onClick={demo} disabled={busy} variant="secondary">
            {busy ? "Signing in…" : "Try the demo merchant (no signup)"}
          </Button>
        </div>
        <form onSubmit={submit} className="space-y-3">
          {mode === "signup" && <Input label="Business name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} />}
          <Input label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="username" />
          <Input label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} autoComplete={mode === "login" ? "current-password" : "new-password"} />
          {error && <Alert kind="error">{error}</Alert>}
          <div className="flex items-center justify-between">
            <Button type="submit" disabled={busy}>{mode === "login" ? "Sign in" : "Sign up"}</Button>
            <button type="button" className="text-sm text-indigo-600 hover:underline" onClick={() => setMode(mode === "login" ? "signup" : "login")}>
              {mode === "login" ? "Need an account?" : "Have an account?"}
            </button>
          </div>
        </form>
      </Card>
      <p className="mt-4 text-xs text-zinc-500">Sessions last 30 minutes. This is a simulator: never enter real card numbers anywhere.</p>
    </main>
  );
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
