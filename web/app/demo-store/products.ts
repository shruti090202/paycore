export type Product = { sku: string; name: string; description: string; price_minor: number; emoji: string };

export const PRODUCTS: Product[] = [
  { sku: "tee", name: "Ledger T-shirt", description: "Debits on the left, credits on the right.", price_minor: 79900, emoji: "👕" },
  { sku: "mug", name: "Idempotent Mug", description: "Order it twice, get one mug.", price_minor: 49900, emoji: "☕" },
  { sku: "cap", name: "SKIP LOCKED Cap", description: "For concurrent thinkers.", price_minor: 59900, emoji: "🧢" },
  { sku: "book", name: "Reconciliation Notebook", description: "Every page balances.", price_minor: 129900, emoji: "📒" },
];
