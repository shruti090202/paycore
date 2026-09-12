import { useEffect } from "react";

/** Run async work when deps change. */
export function useAsyncEffect(fn: (alive: () => boolean) => Promise<unknown> | void, deps: React.DependencyList) {
  useEffect(() => {
    let alive = true;
    void Promise.resolve().then(() => {
      if (!alive) return;
      return fn(() => alive);
    }).catch(() => { /* callers handle their own errors */ });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}
