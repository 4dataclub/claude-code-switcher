#!/usr/bin/env python3
"""
llm-loadtest.py — Concurrency-Load-Test für eine OpenAI-kompatible LLM-Engine.

Zweck: empirisch messen, ab wie vielen parallelen Nutzern eine Engine (Ollama auf
Mac Studio ODER vLLM auf GPU-Server) einbricht. Misst pro Concurrency-Stufe:
  - Aggregat-Durchsatz (tok/s über alle Streams)
  - Durchsatz pro Stream (Median)
  - TTFT (time to first token) p50/p95
  - End-to-End-Latenz p50/p95
  - Fehlerrate

Beide Backends sprechen /v1/chat/completions (SSE-Streaming), daher derselbe Test
für Mac-Pilot (Ollama) und GPU-Produktion (vLLM) → direkter Vergleich.

Nur Python-Stdlib, keine Dependencies. Läuft auf Mac + Linux.

── Beispiele ────────────────────────────────────────────────────────────────
  # Gegen lokales Ollama, llama3.1:8b, Stufen 1/2/4/8/16
  python3 llm-loadtest.py --url http://localhost:11434/v1 --model llama3.1:8b

  # Gegen Mac-Studio-Engine im VPN
  python3 llm-loadtest.py --url http://10.0.0.42:11434/v1 --model qwen2.5:72b \
      --levels 1,2,4,8,16,24,32 --reqs-per-worker 3

  # Gegen vLLM (OpenAI-Endpoint, ggf. API-Key)
  python3 llm-loadtest.py --url http://gpu-server:8000/v1 --model Qwen/Qwen2.5-32B-Instruct \
      --api-key sk-... --levels 1,4,8,16,32,64

  # Über den Switcher-Cascade-Proxy (llm-cascade Container-Port)
  python3 llm-loadtest.py --url http://localhost:8091/v1 --model qwen2.5-coder:7b

Hinweis: tok/s wird aus gezählten Stream-Chunks approximiert (1 Chunk ≈ 1 Token bei
den gängigen Servern). Wenn der Server `usage.completion_tokens` mitschickt, wird das
bevorzugt. Beide Werte werden ausgewiesen.
"""
import argparse
import json
import statistics
import sys
import threading
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

DEFAULT_PROMPT = (
    "Erkläre in etwa 150 Wörtern, wie ein HTTP-Reverse-Proxy funktioniert "
    "und wozu man ihn einsetzt. Antworte auf Deutsch, in ganzen Sätzen."
)


def one_request(url, model, api_key, prompt, max_tokens, timeout):
    """Ein einzelner Streaming-Request. Gibt Metriken-Dict zurück (oder error)."""
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "stream": True,
        # vLLM/manche liefern usage am Ende wenn angefragt:
        "stream_options": {"include_usage": True},
    }).encode("utf-8")

    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    req = urllib.request.Request(url.rstrip("/") + "/chat/completions",
                                 data=body, headers=headers, method="POST")
    t_start = time.perf_counter()
    t_first = None
    chunks = 0
    chars = 0
    usage_tokens = None
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            for raw in resp:
                line = raw.decode("utf-8", "replace").strip()
                if not line or not line.startswith("data:"):
                    continue
                data = line[len("data:"):].strip()
                if data == "[DONE]":
                    break
                try:
                    obj = json.loads(data)
                except json.JSONDecodeError:
                    continue
                # usage kommt (falls) im letzten Chunk
                if obj.get("usage"):
                    usage_tokens = obj["usage"].get("completion_tokens")
                choices = obj.get("choices") or []
                if choices:
                    delta = choices[0].get("delta") or {}
                    content = delta.get("content")
                    if content:
                        if t_first is None:
                            t_first = time.perf_counter()
                        chunks += 1
                        chars += len(content)
        t_end = time.perf_counter()
        if t_first is None:
            t_first = t_end
        out_tokens = usage_tokens if usage_tokens is not None else chunks
        return {
            "ok": True,
            "ttft": t_first - t_start,
            "total": t_end - t_start,
            "gen_time": max(t_end - t_first, 1e-6),
            "tokens": out_tokens,
            "chars": chars,
        }
    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
        return {"ok": False, "error": str(e), "total": time.perf_counter() - t_start}
    except Exception as e:  # noqa: BLE001 — Load-Test soll nie hart crashen
        return {"ok": False, "error": repr(e), "total": time.perf_counter() - t_start}


def pct(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def run_level(url, model, api_key, prompt, max_tokens, timeout, concurrency, reqs):
    """Feuert `reqs` Requests mit maximal `concurrency` gleichzeitig."""
    results = []
    wall_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        futs = [ex.submit(one_request, url, model, api_key, prompt, max_tokens, timeout)
                for _ in range(reqs)]
        for f in as_completed(futs):
            results.append(f.result())
    wall = time.perf_counter() - wall_start

    ok = [r for r in results if r.get("ok")]
    errs = [r for r in results if not r.get("ok")]
    total_tokens = sum(r["tokens"] for r in ok)
    agg_tps = total_tokens / wall if wall > 0 else 0.0
    per_stream = [r["tokens"] / r["gen_time"] for r in ok if r["gen_time"] > 0]
    ttfts = [r["ttft"] for r in ok]
    lats = [r["total"] for r in ok]

    return {
        "concurrency": concurrency,
        "reqs": reqs,
        "ok": len(ok),
        "err": len(errs),
        "wall": wall,
        "agg_tps": agg_tps,
        "stream_tps_median": statistics.median(per_stream) if per_stream else 0.0,
        "ttft_p50": pct(ttfts, 50),
        "ttft_p95": pct(ttfts, 95),
        "lat_p50": pct(lats, 50),
        "lat_p95": pct(lats, 95),
        "err_samples": [e.get("error", "?") for e in errs[:3]],
    }


def main():
    ap = argparse.ArgumentParser(description="Concurrency-Load-Test für OpenAI-kompatible LLM-Engines.")
    ap.add_argument("--url", required=True, help="Base-URL inkl. /v1 (z.B. http://host:11434/v1)")
    ap.add_argument("--model", required=True, help="Modell-ID wie vom Server erwartet")
    ap.add_argument("--api-key", default=None, help="Bearer-Token (vLLM optional)")
    ap.add_argument("--levels", default="1,2,4,8,16",
                    help="Concurrency-Stufen, kommagetrennt (Default: 1,2,4,8,16)")
    ap.add_argument("--reqs-per-worker", type=int, default=2,
                    help="Requests pro Concurrency-Slot je Stufe (Default: 2 → reqs=level*2)")
    ap.add_argument("--max-tokens", type=int, default=256, help="max_tokens pro Antwort (Default: 256)")
    ap.add_argument("--timeout", type=int, default=300, help="Request-Timeout in s (Default: 300)")
    ap.add_argument("--prompt", default=DEFAULT_PROMPT, help="Test-Prompt")
    ap.add_argument("--no-warmup", action="store_true", help="Warmup-Request (Modell laden) überspringen")
    args = ap.parse_args()

    levels = [int(x) for x in args.levels.split(",") if x.strip()]

    print(f"# LLM Load-Test")
    print(f"#   URL:    {args.url}")
    print(f"#   Modell: {args.model}")
    print(f"#   Stufen: {levels}  | reqs/slot: {args.reqs_per_worker} | max_tokens: {args.max_tokens}")
    print()

    if not args.no_warmup:
        print("# Warmup (Modell laden) …", flush=True)
        w = one_request(args.url, args.model, args.api_key, "Sag kurz hallo.", 16, args.timeout)
        if not w.get("ok"):
            print(f"!! Warmup fehlgeschlagen: {w.get('error')}", file=sys.stderr)
            print("   Prüfe URL/Modell/Erreichbarkeit. Abbruch.", file=sys.stderr)
            sys.exit(1)
        print(f"  ok (TTFT {w['ttft']:.2f}s, {w['tokens']} tok)\n", flush=True)

    hdr = (f"{'conc':>4} {'reqs':>5} {'ok':>4} {'err':>4} {'wall_s':>7} "
           f"{'AGG tok/s':>10} {'stream t/s':>11} {'TTFT p50':>9} {'TTFT p95':>9} "
           f"{'lat p50':>8} {'lat p95':>8}")
    print(hdr)
    print("-" * len(hdr))

    rows = []
    for c in levels:
        reqs = max(c * args.reqs_per_worker, c)
        r = run_level(args.url, args.model, args.api_key, args.prompt,
                      args.max_tokens, args.timeout, c, reqs)
        rows.append(r)
        print(f"{r['concurrency']:>4} {r['reqs']:>5} {r['ok']:>4} {r['err']:>4} "
              f"{r['wall']:>7.1f} {r['agg_tps']:>10.1f} {r['stream_tps_median']:>11.1f} "
              f"{r['ttft_p50']:>9.2f} {r['ttft_p95']:>9.2f} "
              f"{r['lat_p50']:>8.2f} {r['lat_p95']:>8.2f}", flush=True)
        if r["err_samples"]:
            print(f"     ⚠ Fehler-Beispiele: {r['err_samples']}", flush=True)

    # Interpretation: Knick finden, wo Aggregat-Durchsatz nicht mehr steigt
    print()
    print("# Interpretation")
    best = max(rows, key=lambda x: x["agg_tps"])
    print(f"#   Max Aggregat-Durchsatz: {best['agg_tps']:.1f} tok/s bei Concurrency {best['concurrency']}")
    print(f"#   → Darüber bringt mehr Parallelität kaum mehr Durchsatz, nur mehr Latenz.")
    # Latenz-Grenze: erste Stufe wo p95-Latenz > 30s
    slow = next((r for r in rows if r["lat_p95"] > 30), None)
    if slow:
        print(f"#   p95-Latenz überschreitet 30s ab Concurrency {slow['concurrency']} "
              f"({slow['lat_p95']:.1f}s) → ab hier fühlt es sich 'hängend' an.")
    else:
        print(f"#   p95-Latenz bleibt unter 30s bis Concurrency {rows[-1]['concurrency']}.")
    print("#   Faustregel Nutzer-Deckung: nutzbare Concurrency × (1 / Aktiv-Anteil).")
    print("#   Bsp: nutzbar 4 parallel, 15% aktiv → ~26 Nutzer; 25% aktiv → ~16 Nutzer.")


if __name__ == "__main__":
    main()