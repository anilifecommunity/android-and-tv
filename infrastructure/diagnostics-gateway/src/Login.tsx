import { FormEvent, useEffect, useRef, useState } from "react";
import { jsonRequest } from "./api";
import { SignalIcon } from "./SignalIcon";

interface TurnstileApi {
  render(container: HTMLElement, options: {
    sitekey: string;
    action: string;
    callback: (token: string) => void;
    "expired-callback": () => void;
    "error-callback": () => void;
  }): string;
  remove(widgetId: string): void;
  reset(widgetId: string): void;
}

declare global {
  interface Window { turnstile?: TurnstileApi }
}

function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile);
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>("script[data-anilife-turnstile]");
    const script = existing ?? document.createElement("script");
    const ready = () => window.turnstile ? resolve(window.turnstile) : reject(new Error("Bot check unavailable"));
    script.addEventListener("load", ready, { once: true });
    script.addEventListener("error", () => reject(new Error("Bot check unavailable")), { once: true });
    if (!existing) {
      script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
      script.async = true;
      script.defer = true;
      script.dataset.anilifeTurnstile = "1";
      document.head.append(script);
    }
  });
}

export function Login({ onAuthenticated }: { onAuthenticated: () => void }) {
  const [accessKey, setAccessKey] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [turnstileToken, setTurnstileToken] = useState("");
  const turnstileContainer = useRef<HTMLDivElement>(null);
  const turnstileWidget = useRef<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      jsonRequest<{ siteKey: string }>("/api/turnstile/config"),
      loadTurnstile(),
    ]).then(([config, turnstile]) => {
      if (cancelled || !turnstileContainer.current) return;
      turnstileWidget.current = turnstile.render(turnstileContainer.current, {
        sitekey: config.siteKey,
        action: "admin_login",
        callback: setTurnstileToken,
        "expired-callback": () => setTurnstileToken(""),
        "error-callback": () => {
          setTurnstileToken("");
          setError("Bot check failed. Please retry.");
        },
      });
    }).catch((loadError) => {
      if (!cancelled) setError(loadError instanceof Error ? loadError.message : "Bot check unavailable");
    });
    return () => {
      cancelled = true;
      if (turnstileWidget.current && window.turnstile) {
        window.turnstile.remove(turnstileWidget.current);
      }
    };
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await jsonRequest("/api/admin/login", {
        method: "POST",
        body: JSON.stringify({ accessKey, turnstileToken }),
      });
      setAccessKey("");
      onAuthenticated();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Sign-in failed");
      setTurnstileToken("");
      if (turnstileWidget.current && window.turnstile) {
        window.turnstile.reset(turnstileWidget.current);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-shell">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="console-mark">
          <span className="console-mark__icon"><SignalIcon /></span>
          <span>ANILIFE / OPS</span>
        </div>
        <p className="eyebrow">Private diagnostic receiver</p>
        <h1 id="login-title">Flight recorder<br />console.</h1>
        <p className="login-copy">
          Crash, playback, resolver and performance reports, sent with explicit user consent.
        </p>
        <form onSubmit={submit} className="login-form">
          <label htmlFor="access-key">Administrator access key</label>
          <div className="key-row">
            <input
              id="access-key"
              type="password"
              autoComplete="current-password"
              value={accessKey}
              onChange={(event) => setAccessKey(event.target.value)}
              required
              minLength={24}
              autoFocus
            />
            <button type="submit" disabled={busy || !turnstileToken}>{busy ? "Checking…" : "Enter"}</button>
          </div>
          <div className="turnstile-row" ref={turnstileContainer} aria-label="Bot verification" />
          {error && <p className="form-error" role="alert">{error}</p>}
        </form>
        <div className="privacy-note">
          <span className="privacy-note__led" />
          Reports are redacted before upload and removed automatically after 30 days.
        </div>
      </section>
      <aside className="login-rail" aria-hidden="true">
        <span>01</span><span>INGEST</span><span>VALIDATE</span><span>ARCHIVE</span><span>30D</span>
      </aside>
    </main>
  );
}
