import { useEffect } from "react";

/**
 * Run async work when deps change. The callback starts on a microtask (after the render commits), so state
 * updates it makes never run synchronously inside the effect body, and it is skipped if the component
 * unmounted or deps changed in the meantime.
 */
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
