export type Payment = {
  id: string;
  amount_minor: number;
  currency: string;
  captured_minor: number;
  refunded_minor: number;
  status: string;
  capture_method: string;
  description?: string;
  customer?: { email?: string; ref?: string };
  card?: { brand: string; last4: string };
  bank_ref?: string;
  failure?: { code: string; message: string };
  risk?: { score: number; decision: string };
  checkout_url?: string;
  success_url?: string;
  cancel_url?: string;
  metadata?: Record<string, unknown>;
  created_at: string;
  updated_at: string;
};

export type Refund = {
  id: string;
  payment_id: string;
  amount_minor: number;
  currency: string;
  status: string;
  reason?: string;
  failure_code?: string;
  created_at: string;
};

export type PaymentDetail = {
  payment: Payment;
  events: { id: string; type: string; from_status?: string; to_status?: string; data: Record<string, unknown>; created_at: string }[];
  ledger: { id: string; kind: string; description: string; created_at: string; postings: { account_code: string; direction: string; amount_minor: number; currency: string }[] }[];
  refunds: Refund[];
  bank_attempts: { id: string; kind: string; bank_ref: string; amount_minor: number; outcome: string; decline_code?: string; latency_ms?: number; resolution?: string; created_at: string }[];
  risk?: { score: number; decision: string; reasons: { rule: string; score: number; message: string }[] };
  card_fingerprint?: string;
};
